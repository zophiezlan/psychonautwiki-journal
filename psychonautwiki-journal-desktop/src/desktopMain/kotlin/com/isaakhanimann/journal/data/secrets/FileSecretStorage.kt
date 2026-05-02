package com.isaakhanimann.journal.data.secrets

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.Properties

/**
 * Plaintext, file-system-permission-protected secret storage.
 *
 * This is the *floor* implementation, intended to be replaced by an OS keychain
 * binding (macOS Keychain / Windows Credential Manager / libsecret) before any
 * feature that consumes a high-value secret (e.g. a paid OpenAI key) ships to
 * end users.
 *
 * Why it is acceptable as a placeholder: secrets land in a separate file from
 * the journal SQLite database. An attacker who exfiltrates [database.db] does
 * not also get [secrets.properties], so the blast radius is reduced. File
 * permissions are tightened to owner-only on POSIX and best-effort on Windows.
 *
 * Why it MUST be replaced before shipping cloud-AI: the secrets sit at rest in
 * cleartext, recoverable by any process with read access to the user's home
 * directory (sync clients, backup tools, malware running as the same user).
 * [isHardwareBacked] returns false so callers can refuse to store credentials
 * here when they require stronger guarantees.
 */
class FileSecretStorage(
    private val storageFile: File = defaultStorageFile()
) : SecretStorage {

    private val mutex = Mutex()
    override val isHardwareBacked: Boolean = false

    override suspend fun getSecret(key: String): String? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!storageFile.exists()) return@withLock null
            val props = Properties()
            storageFile.inputStream().use(props::load)
            props.getProperty(key)
        }
    }

    override suspend fun setSecret(key: String, value: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            ensureParentDirectory()
            val props = Properties()
            if (storageFile.exists()) {
                storageFile.inputStream().use(props::load)
            }
            props.setProperty(key, value)
            storageFile.outputStream().use { props.store(it, "psychonautwiki-journal secrets — DO NOT EDIT") }
            restrictToOwner(storageFile)
        }
    }

    override suspend fun removeSecret(key: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!storageFile.exists()) return@withLock
            val props = Properties()
            storageFile.inputStream().use(props::load)
            if (props.remove(key) != null) {
                storageFile.outputStream().use { props.store(it, "psychonautwiki-journal secrets — DO NOT EDIT") }
            }
        }
    }

    private fun ensureParentDirectory() {
        val parent = storageFile.parentFile ?: return
        if (!parent.exists()) parent.mkdirs()
        try {
            val path = parent.toPath()
            if (path.fileSystem.supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(
                    path,
                    setOf(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE
                    )
                )
            }
        } catch (_: Exception) {
            // Non-fatal.
        }
    }

    private fun restrictToOwner(file: File) {
        try {
            val path = file.toPath()
            if (path.fileSystem.supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(
                    path,
                    setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
                )
            } else {
                file.setReadable(false, false); file.setReadable(true, true)
                file.setWritable(false, false); file.setWritable(true, true)
            }
        } catch (_: Exception) {
            // Non-fatal.
        }
    }

    companion object {
        fun defaultStorageFile(): File =
            File(
                File(System.getProperty("user.home"), ".psychonautwiki-journal"),
                "secrets.properties"
            )
    }
}
