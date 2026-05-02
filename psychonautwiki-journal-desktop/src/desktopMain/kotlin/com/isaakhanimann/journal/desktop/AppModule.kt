package com.isaakhanimann.journal.desktop

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.isaakhanimann.journal.database.Database
import com.isaakhanimann.journal.data.repository.*
import com.isaakhanimann.journal.data.experience.ExperienceTracker
import com.isaakhanimann.journal.data.secrets.SecretStorage
import com.isaakhanimann.journal.data.secrets.FileSecretStorage
import com.isaakhanimann.journal.data.export.ExportManager
import com.isaakhanimann.journal.data.export.ExportManagerImpl
import com.isaakhanimann.journal.data.import.ImportManager
import com.isaakhanimann.journal.data.import.ImportManagerImpl
import com.isaakhanimann.journal.plugin.PluginManager
import com.isaakhanimann.journal.plugin.PluginManagerImpl
import com.isaakhanimann.journal.ai.AIAssistant
import com.isaakhanimann.journal.ai.AIAssistantImpl
import com.isaakhanimann.journal.ai.PersonalizedInsightService
import com.isaakhanimann.journal.ai.PersonalizedInsightServiceImpl
import com.isaakhanimann.journal.gamification.GamificationService
import com.isaakhanimann.journal.gamification.GamificationServiceImpl
import com.isaakhanimann.journal.gamification.WeeklyChallengeService
import com.isaakhanimann.journal.gamification.WeeklyChallengeServiceImpl
import com.isaakhanimann.journal.ui.theme.ThemeManager
import com.isaakhanimann.journal.ui.utils.FileDialogHandler
import com.isaakhanimann.journal.ui.utils.DesktopFileDialogHandler
import com.isaakhanimann.journal.ui.viewmodel.*
import org.koin.dsl.module
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.sql.DriverManager

val appModule = module {

    single<SqlDriver> {
        val dataDir = File(System.getProperty("user.home"), ".psychonautwiki-journal")
        ensureUserPrivateDirectory(dataDir)
        val databaseFile = File(dataDir, "database.db")

        val driver = JdbcSqliteDriver("jdbc:sqlite:${databaseFile.absolutePath}")

        // Check if Experience table exists using JDBC
        try {
            val connection = DriverManager.getConnection("jdbc:sqlite:${databaseFile.absolutePath}")
            val resultSet = connection.metaData.getTables(null, null, "Experience", null)
            val tableExists = resultSet.next()
            resultSet.close()
            connection.close()

            if (!tableExists) {
                Database.Schema.create(driver)
            }
        } catch (e: Exception) {
            // If check fails, assume tables don't exist and create schema
            Database.Schema.create(driver)
        }

        // Restrict the database file itself to the owner — newly created on first run.
        restrictToOwner(databaseFile)

        driver
    }
    
    single<Database> {
        Database(get())
    }
    
    // Repositories
    single<ExperienceRepository> {
        ExperienceRepositoryImpl(get())
    }
    
    single<SubstanceRepository> {
        SubstanceRepositoryImpl(get())
    }
    
    single<PreferencesRepository> {
        PreferencesRepositoryImpl(get())
    }
    
    single<DraftManager> {
        DraftManagerImpl(get())
    }
    
    single<ExportManager> {
        ExportManagerImpl(get())
    }
    
    single<ImportManager> {
        ImportManagerImpl(get())
    }
    
    single<FileDialogHandler> {
        DesktopFileDialogHandler()
    }

    // SECURITY: secrets (e.g. OpenAI API key) live OUTSIDE the journal SQLite DB.
    // The desktop binding is FileSecretStorage; replace it with a keychain-backed
    // implementation before enabling any cloud-AI feature for end users.
    single<SecretStorage> {
        FileSecretStorage()
    }
    
    // Plugin System
    single<PluginManager> {
        PluginManagerImpl()
    }
    
    // AI Assistant
    single<AIAssistant> {
        AIAssistantImpl()
    }
    
    // Gamification System
    single<GamificationService> {
        GamificationServiceImpl()
    }
    
    single<WeeklyChallengeService> {
        WeeklyChallengeServiceImpl(get(), get())
    }
    
    single<PersonalizedInsightService> {
        PersonalizedInsightServiceImpl(get(), get(), get())
    }
    
    // Theme Management
    single<ThemeManager> {
        ThemeManager(get())
    }
    
    // Business Logic
    single<ExperienceTracker> {
        ExperienceTracker(get())
    }
    
    // UI Utils
    single<com.isaakhanimann.journal.ui.utils.FileDialogHandler> {
        com.isaakhanimann.journal.ui.utils.DesktopFileDialogHandler()
    }
    
    // ViewModels
    factory<DashboardViewModel> {
        DashboardViewModel(get())
    }
    
    factory<ExperiencesViewModel> {
        ExperiencesViewModel(get())
    }
    
    factory<ExperienceEditorViewModel> {
        ExperienceEditorViewModel(get(), get())
    }
    
    factory<IngestionEditorViewModel> {
        IngestionEditorViewModel(get(), get(), get())
    }
    
    factory<ExperienceTimelineViewModel> {
        ExperienceTimelineViewModel(get())
    }
    
    factory<SubstancesViewModel> {
        SubstancesViewModel(get())
    }
    
    factory<SettingsViewModel> {
        SettingsViewModel(get())
    }
    
    factory<AnalyticsViewModel> {
        AnalyticsViewModel(get(), get())
    }
    
    factory<AIAssistantViewModel> {
        AIAssistantViewModel(get())
    }
    
    factory<GamificationViewModel> {
        GamificationViewModel()
    }
}

/**
 * Ensures [dir] exists with permissions limited to the current user.
 *
 * On POSIX systems we set 0700 on the directory. On Windows we cannot rely on
 * POSIX bits, but the user's home directory is already ACL-restricted to that
 * user by default; we still call setReadable/Writable/Executable(false, false)
 * followed by (true, true) to drop "all users" access where supported.
 *
 * Failure to tighten permissions is logged but not fatal — the app must still
 * start on filesystems that don't support either model (e.g. FAT-formatted USB).
 */
private fun ensureUserPrivateDirectory(dir: File) {
    if (!dir.exists()) dir.mkdirs()
    try {
        val path = dir.toPath()
        val supportsPosix = path.fileSystem.supportedFileAttributeViews().contains("posix")
        if (supportsPosix) {
            val perms = PosixFilePermissions.fromString("rwx------")
            Files.setPosixFilePermissions(path, perms)
        } else {
            // Best-effort on Windows / other non-POSIX filesystems.
            dir.setReadable(false, false); dir.setReadable(true, true)
            dir.setWritable(false, false); dir.setWritable(true, true)
            dir.setExecutable(false, false); dir.setExecutable(true, true)
        }
    } catch (_: Exception) {
        // Non-fatal: some filesystems (FAT, network mounts) reject these calls.
    }
}

/** Restricts a regular file to read/write by its owner. Best-effort, see [ensureUserPrivateDirectory]. */
private fun restrictToOwner(file: File) {
    if (!file.exists()) return
    try {
        val path = file.toPath()
        val supportsPosix = path.fileSystem.supportedFileAttributeViews().contains("posix")
        if (supportsPosix) {
            val perms = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            Files.setPosixFilePermissions(path, perms)
        } else {
            file.setReadable(false, false); file.setReadable(true, true)
            file.setWritable(false, false); file.setWritable(true, true)
        }
    } catch (_: Exception) {
        // Non-fatal.
    }
}