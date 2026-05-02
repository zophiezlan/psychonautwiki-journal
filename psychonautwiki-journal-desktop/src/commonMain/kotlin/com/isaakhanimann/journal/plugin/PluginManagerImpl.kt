package com.isaakhanimann.journal.plugin

import com.isaakhanimann.journal.data.repository.ExperienceRepository
import com.isaakhanimann.journal.data.repository.SubstanceRepository
import com.isaakhanimann.journal.data.repository.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.jar.JarFile
import kotlin.reflect.KClass

/**
 * Plugin loader.
 *
 * SECURITY MODEL (current state — read before changing):
 *
 * Plugin code is loaded with [URLClassLoader] and runs with the full privileges of the
 * host JVM (filesystem, network, reflection). The [Permission] enum on [PluginManifest]
 * is informational only — there is no enforcement layer yet. Until one exists, this
 * class deliberately:
 *
 *  - does NOT auto-load plugins on application startup, even if the user previously
 *    enabled them. Users must re-confirm load each session;
 *  - rejects any plugin whose manifest declares privileged permissions
 *    ([Permission.NETWORK_ACCESS], [Permission.FILE_SYSTEM_ACCESS], [Permission.BIOMETRIC_DATA]);
 *  - canonicalises [manifest.id] and refuses path-traversal characters so the id
 *    cannot escape [getPluginDirectory] when used as a filename;
 *  - canonicalises any path passed to [loadPlugin] and refuses paths outside
 *    the plugin directory, regardless of symlink targets;
 *  - does NOT auto-load a freshly installed plugin from [installPlugin] — the user
 *    must invoke [enablePlugin] / [loadPlugin] explicitly.
 *
 * To remove these restrictions, you MUST first wire up real enforcement:
 *  1. A custom [SecurityManager] (or post-JEP-411 equivalent) gating the JDK calls
 *     a plugin can make to those declared in its manifest.
 *  2. A [ClassLoader] that constrains the classes a plugin can resolve from the
 *     host (whitelist approach, not blacklist).
 *  3. Code-signing verification on the JAR before [URLClassLoader] sees it.
 *  4. Replacing [PluginContextImpl]'s direct repository injection with capability
 *     wrappers that consult [PluginDataAccess.hasPermission] on every call.
 */
class PluginManagerImpl : PluginManager, KoinComponent {
    private val experienceRepository: ExperienceRepository by inject()
    private val substanceRepository: SubstanceRepository by inject()
    private val preferencesRepository: PreferencesRepository by inject()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val json = Json { ignoreUnknownKeys = true }

    private val _installedPlugins = MutableStateFlow<List<PluginInfo>>(emptyList())
    override val installedPlugins: StateFlow<List<PluginInfo>> = _installedPlugins.asStateFlow()

    private val _enabledPlugins = MutableStateFlow<List<Plugin>>(emptyList())
    override val enabledPlugins: StateFlow<List<Plugin>> = _enabledPlugins.asStateFlow()

    private val loadedPlugins = mutableMapOf<String, Plugin>()
    private val pluginClassLoaders = mutableMapOf<String, URLClassLoader>()

    init {
        scope.launch {
            discoverInstalledPlugins()
        }
    }

    /**
     * Enumerates JARs in the plugin directory and reports them as [PluginInfo],
     * but does NOT load any code. Until the sandbox in the kdoc above exists, the
     * "previously enabled" preference is treated as a hint, not a license to execute.
     */
    private suspend fun discoverInstalledPlugins() {
        val pluginDir = File(getPluginDirectory())
        if (!pluginDir.exists()) {
            pluginDir.mkdirs()
            tightenDirectoryPermissions(pluginDir)
            return
        }
        // Re-apply on every startup in case the directory was created earlier
        // with looser perms.
        tightenDirectoryPermissions(pluginDir)

        val pluginInfos = mutableListOf<PluginInfo>()

        pluginDir.listFiles { file -> file.extension == "jar" }?.forEach { jarFile ->
            try {
                val manifest = readPluginManifest(jarFile)
                val isEnabled = isPluginEnabled(manifest.id)
                val pluginInfo = PluginInfo(
                    manifest = manifest,
                    isEnabled = isEnabled,
                    // Always false at startup: code is never executed without an
                    // explicit user gesture this session.
                    isLoaded = false
                )
                pluginInfos.add(pluginInfo)
            } catch (e: Exception) {
                val errorInfo = PluginInfo(
                    manifest = PluginManifest(
                        id = jarFile.nameWithoutExtension,
                        name = "Unknown",
                        version = "Unknown",
                        description = "Failed to load",
                        author = "Unknown",
                        permissions = emptyList(),
                        entryPoint = ""
                    ),
                    isEnabled = false,
                    isLoaded = false,
                    error = e.message
                )
                pluginInfos.add(errorInfo)
            }
        }
        
        _installedPlugins.value = pluginInfos
    }
    
    override suspend fun loadPlugin(pluginPath: String): Result<Plugin> {
        return try {
            val jarFile = resolvePluginPath(pluginPath)
                ?: return Result.failure(SecurityException(
                    "Refusing to load plugin from outside ${getPluginDirectory()}"
                ))
            val manifest = readPluginManifest(jarFile)
            validatePluginManifest(manifest)
            requireNonPrivilegedManifest(manifest)

            if (loadedPlugins.containsKey(manifest.id)) {
                return Result.failure(Exception("Plugin already loaded: ${manifest.id}"))
            }

            val classLoader = URLClassLoader(arrayOf(jarFile.toURI().toURL()))
            val pluginClass = classLoader.loadClass(manifest.entryPoint)
            val plugin = pluginClass.getDeclaredConstructor().newInstance() as Plugin

            val context = PluginContextImpl()
            plugin.initialize(context).getOrThrow()

            loadedPlugins[manifest.id] = plugin
            pluginClassLoaders[manifest.id] = classLoader

            updateEnabledPlugins()
            updatePluginInfo(manifest.id, isLoaded = true)

            Result.success(plugin)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Canonicalises [pluginPath] and returns the [File] only if it sits under
     * [getPluginDirectory]. Symlinks pointing outside the dir are rejected because
     * we compare canonical paths.
     */
    private fun resolvePluginPath(pluginPath: String): File? {
        val pluginDir = File(getPluginDirectory()).canonicalFile
        val candidate = File(pluginPath).canonicalFile
        if (!candidate.exists() || !candidate.isFile) return null
        if (candidate.extension != "jar") return null
        var parent: File? = candidate.parentFile
        while (parent != null) {
            if (parent == pluginDir) return candidate
            parent = parent.parentFile
        }
        return null
    }

    /**
     * Rejects manifests asking for capabilities the runtime cannot enforce yet.
     * Built-in plugins should declare only the minimal set they actually use.
     */
    private fun requireNonPrivilegedManifest(manifest: PluginManifest) {
        val unsupported = manifest.permissions.filter {
            it == Permission.NETWORK_ACCESS ||
                it == Permission.FILE_SYSTEM_ACCESS ||
                it == Permission.BIOMETRIC_DATA
        }
        if (unsupported.isNotEmpty()) {
            throw SecurityException(
                "Plugin ${manifest.id} requests permissions that are not yet enforced " +
                    "by a sandbox: $unsupported. Loading is blocked until enforcement lands."
            )
        }
    }
    
    override suspend fun unloadPlugin(pluginId: String): Result<Unit> {
        return try {
            val plugin = loadedPlugins[pluginId] 
                ?: return Result.failure(Exception("Plugin not loaded: $pluginId"))
            
            plugin.shutdown().getOrThrow()
            
            loadedPlugins.remove(pluginId)
            pluginClassLoaders[pluginId]?.close()
            pluginClassLoaders.remove(pluginId)
            
            updateEnabledPlugins()
            updatePluginInfo(pluginId, isLoaded = false)
            
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun enablePlugin(pluginId: String): Result<Unit> {
        return try {
            val safeId = sanitizePluginId(pluginId)
            val pluginInfo = _installedPlugins.value.find { it.manifest.id == safeId }
                ?: return Result.failure(Exception("Plugin not found: $safeId"))

            if (!loadedPlugins.containsKey(safeId)) {
                // Resolve via the safe loader, never trust a constructed string path.
                val pluginPath = File(getPluginDirectory(), "$safeId.jar").absolutePath
                loadPlugin(pluginPath).getOrThrow()
            }

            // Persist the enabled bit only after a successful load. Auto-load on
            // next startup is intentionally not honoured (see class kdoc).
            setPluginEnabled(safeId, true)
            updatePluginInfo(safeId, isEnabled = true)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun disablePlugin(pluginId: String): Result<Unit> {
        return try {
            setPluginEnabled(pluginId, false)
            unloadPlugin(pluginId).getOrThrow()
            updatePluginInfo(pluginId, isEnabled = false)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    override suspend fun installPlugin(pluginPackage: ByteArray): Result<PluginInfo> {
        var tempFile: File? = null
        return try {
            if (pluginPackage.size > MAX_PLUGIN_BYTES) {
                return Result.failure(SecurityException(
                    "Plugin exceeds size limit ($MAX_PLUGIN_BYTES bytes)"
                ))
            }

            tempFile = File.createTempFile("plugin", ".jar").apply {
                deleteOnExit()
                writeBytes(pluginPackage)
            }

            val manifest = readPluginManifest(tempFile)
            validatePluginManifest(manifest)
            requireNonPrivilegedManifest(manifest)

            // manifest.id is now sanitised so this filename cannot escape the plugin dir.
            val pluginFile = File(getPluginDirectory(), "${manifest.id}.jar")
            tempFile.copyTo(pluginFile, overwrite = true)

            val pluginInfo = PluginInfo(
                manifest = manifest,
                isEnabled = false,
                isLoaded = false
            )

            _installedPlugins.value = _installedPlugins.value
                .filterNot { it.manifest.id == manifest.id } + pluginInfo

            // Deliberately not auto-loading. Caller must invoke enablePlugin().
            Result.success(pluginInfo)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            tempFile?.delete()
        }
    }
    
    override suspend fun uninstallPlugin(pluginId: String): Result<Unit> {
        return try {
            val safeId = sanitizePluginId(pluginId)
            disablePlugin(safeId)

            val pluginDir = File(getPluginDirectory()).canonicalFile
            val pluginFile = File(pluginDir, "$safeId.jar").canonicalFile
            if (pluginFile.parentFile == pluginDir && pluginFile.exists()) {
                pluginFile.delete()
            }

            _installedPlugins.value = _installedPlugins.value.filter {
                it.manifest.id != safeId
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    @Suppress("UNCHECKED_CAST")
    override fun getPluginCapabilities(type: KClass<out PluginCapability>): List<PluginCapability> {
        return _enabledPlugins.value.flatMap { plugin ->
            plugin.getCapabilities().filter { capability ->
                type.isInstance(capability)
            }
        }
    }
    
    override suspend fun executeAnalytics(context: AnalyticsContext): List<AnalyticsResult> {
        val analyticsCapabilities = getPluginCapabilities(AnalyticsCapability::class)
        return analyticsCapabilities.mapNotNull { capability ->
            try {
                (capability as AnalyticsCapability).analyzeFunction(context)
            } catch (e: Exception) {
                null
            }
        }
    }
    
    override suspend fun queryAI(context: AIContext): List<AIResult> {
        val aiCapabilities = getPluginCapabilities(AICapability::class)
        return aiCapabilities.mapNotNull { capability ->
            try {
                (capability as AICapability).processFunction(context)
            } catch (e: Exception) {
                null
            }
        }
    }
    
    private fun readPluginManifest(jarFile: File): PluginManifest {
        JarFile(jarFile).use { jar ->
            val manifestEntry = jar.getJarEntry("plugin.json")
                ?: throw Exception("Plugin manifest not found in ${jarFile.name}")
            
            val manifestContent = jar.getInputStream(manifestEntry).readBytes().decodeToString()
            return json.decodeFromString(PluginManifest.serializer(), manifestContent)
        }
    }
    
    private fun validatePluginManifest(manifest: PluginManifest) {
        require(manifest.id.isNotBlank()) { "Plugin ID cannot be blank" }
        require(manifest.name.isNotBlank()) { "Plugin name cannot be blank" }
        require(manifest.version.isNotBlank()) { "Plugin version cannot be blank" }
        require(manifest.entryPoint.isNotBlank()) { "Plugin entry point cannot be blank" }
        // id becomes "$id.jar" in the plugin directory — refuse anything that could
        // navigate out of it or pollute the filesystem.
        require(manifest.id == sanitizePluginId(manifest.id)) {
            "Plugin ID contains illegal characters: '${manifest.id}'"
        }
        require(manifest.id.length <= 128) { "Plugin ID too long" }
        // entryPoint is fed to ClassLoader.loadClass, so it must look like a JVM
        // class name — letters, digits, underscores, dots, dollar signs. This
        // does not stop a malicious plugin; it stops typos becoming weird crashes.
        require(manifest.entryPoint.all { it.isLetterOrDigit() || it == '.' || it == '_' || it == '$' }) {
            "Plugin entry point is not a valid class name"
        }
    }

    private fun sanitizePluginId(id: String): String =
        id.filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
            .take(128)
    
    private suspend fun isPluginEnabled(pluginId: String): Boolean {
        return preferencesRepository.getBoolean("plugin_enabled_$pluginId", false)
    }
    
    private suspend fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        preferencesRepository.setBoolean("plugin_enabled_$pluginId", enabled)
    }
    
    private fun updateEnabledPlugins() {
        _enabledPlugins.value = loadedPlugins.values.toList()
    }
    
    private fun updatePluginInfo(pluginId: String, isEnabled: Boolean? = null, isLoaded: Boolean? = null) {
        _installedPlugins.value = _installedPlugins.value.map { info ->
            if (info.manifest.id == pluginId) {
                info.copy(
                    isEnabled = isEnabled ?: info.isEnabled,
                    isLoaded = isLoaded ?: info.isLoaded
                )
            } else {
                info
            }
        }
    }
    
    private fun getPluginDirectory(): String {
        val userHome = System.getProperty("user.home")
        return "$userHome/.psychonautwiki-journal/plugins"
    }

    /**
     * Best-effort tightening to 0700 on POSIX, owner-only on Windows.
     * Kept silent on failure (FAT mounts, etc) — matches AppModule behaviour.
     */
    private fun tightenDirectoryPermissions(dir: File) {
        try {
            val path = dir.toPath()
            if (path.fileSystem.supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
            } else {
                dir.setReadable(false, false); dir.setReadable(true, true)
                dir.setWritable(false, false); dir.setWritable(true, true)
                dir.setExecutable(false, false); dir.setExecutable(true, true)
            }
        } catch (_: Exception) {
            // Non-fatal.
        }
    }
    
    companion object {
        // 32 MB ceiling on a single plugin JAR. Plain harm-reduction analytics
        // plugins should be a small fraction of this; the cap exists to bound
        // memory use in [installPlugin] before any parsing happens.
        private const val MAX_PLUGIN_BYTES = 32L * 1024 * 1024
    }

    private inner class PluginContextImpl : PluginContext {
        override val experienceRepository = this@PluginManagerImpl.experienceRepository
        override val substanceRepository = this@PluginManagerImpl.substanceRepository
        override val dataAccess = PluginDataAccessImpl()
        override val notifications = PluginNotificationServiceImpl()
        override val preferences = PluginPreferencesImpl()
    }
    
    private inner class PluginDataAccessImpl : PluginDataAccess {
        override suspend fun readExperiences() = experienceRepository.getAllExperiences()
        override suspend fun readSubstances(): Flow<List<com.isaakhanimann.journal.data.model.Substance>> = flow { 
            emit(emptyList<com.isaakhanimann.journal.data.model.Substance>()) // Simplified implementation
        }
        override suspend fun hasPermission(permission: Permission): Boolean {
            // TODO: Implement permission checking based on loaded plugin manifest
            return true
        }
    }
    
    private inner class PluginNotificationServiceImpl : PluginNotificationService {
        override suspend fun showNotification(title: String, message: String, severity: NotificationSeverity) {
            // TODO: Integrate with system notification service
        }
        
        override suspend fun showDialog(title: String, message: String, actions: List<DialogAction>): DialogResult {
            // TODO: Integrate with UI dialog system
            return DialogResult("", true)
        }
    }
    
    private inner class PluginPreferencesImpl : PluginPreferences {
        override suspend fun getString(key: String, default: String): String {
            return preferencesRepository.getString("plugin_pref_$key", default)
        }
        
        override suspend fun setString(key: String, value: String) {
            preferencesRepository.setString("plugin_pref_$key", value)
        }
        
        override suspend fun getBoolean(key: String, default: Boolean): Boolean {
            return preferencesRepository.getBoolean("plugin_pref_$key", default)
        }
        
        override suspend fun setBoolean(key: String, value: Boolean) {
            preferencesRepository.setBoolean("plugin_pref_$key", value)
        }
    }
}