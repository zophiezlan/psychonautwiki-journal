package com.isaakhanimann.journal.data.secrets

/**
 * Storage for credentials and other secrets that must NEVER land in the SQLite
 * preferences table.
 *
 * Why a separate abstraction: [com.isaakhanimann.journal.data.repository.PreferencesRepository]
 * persists everything in a plaintext SQLite database next to the user's experience
 * log. Mixing an OpenAI key (or any other bearer token) into that table means
 * anyone who reads the database file gets the credential.
 *
 * Implementations of this interface MUST satisfy at least:
 *  - persistence outside the journal SQLite database;
 *  - file permissions restricted to the running user;
 *  - secrets cleared from in-memory caches on [removeSecret].
 *
 * Implementations SHOULD, but are not required to:
 *  - delegate to an OS-managed credential store (macOS Keychain, Windows
 *    Credential Manager, freedesktop secret-service / GNOME Keyring on Linux);
 *  - mark [isHardwareBacked] true when keys are sealed by a TPM / Secure Enclave;
 *  - return null rather than empty string for "not set", so callers can
 *    distinguish missing secrets from intentionally-blank ones.
 *
 * Callers MUST NOT log retrieved secrets, MUST NOT pass them through
 * `toString()` on data classes, and SHOULD wipe them from any [String] copies
 * as soon as they have been used.
 */
interface SecretStorage {
    /** Returns the stored secret, or null if no value has been set for [key]. */
    suspend fun getSecret(key: String): String?

    /** Stores [value] under [key], replacing any existing value. */
    suspend fun setSecret(key: String, value: String)

    /** Removes the secret stored under [key]. No-op if absent. */
    suspend fun removeSecret(key: String)

    /**
     * True when the underlying storage is backed by an OS keychain or hardware
     * security module. Callers gating risky features (e.g. enabling cloud AI)
     * SHOULD require this to be true and refuse to store credentials otherwise.
     */
    val isHardwareBacked: Boolean

    companion object Keys {
        /** OpenAI API key. Used by [com.isaakhanimann.journal.ai.OpenAIProvider]. */
        const val OPENAI_API_KEY = "openai.api_key"
    }
}
