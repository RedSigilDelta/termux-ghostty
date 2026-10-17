package com.mrndtvndv.term.domain

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class ServerConfig(
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val host: String = "",
    val port: Int = 22,
    val username: String = "",
    val auth: AuthType? = null,
    val useGhosttyTerm: Boolean = false,
    val isLocal: Boolean = false,
    val startupCommand: String? = null,
    val imagePasteEnabled: Boolean = false,
    val imagePasteDirectory: String? = null,
    val imagePasteAutoCleanup: Boolean = true,
    val imagePasteMaxFiles: Int = DEFAULT_IMAGE_PASTE_MAX_FILES,
    /** Shows the server's herdr workspaces and attaches to their terminals. Plain tabs stay login shells. */
    val herdrEnabled: Boolean = false,
    /** Absolute path of the herdr API socket on the server. Null uses herdr's default under the server home. */
    val herdrSocketPath: String? = null,
    /** Pane focus uses the herdr focus bridge helper on this server. Off uses the direct herdr socket only. */
    val herdrFocusBridgeEnabled: Boolean = false,
) {
    companion object {
        const val DEFAULT_IMAGE_PASTE_MAX_FILES = 20
    }

    val isImagePasteActive: Boolean
        get() = imagePasteEnabled && !imagePasteDirectory.isNullOrBlank()

    /** Normalizes persisted or externally supplied retention limits before use. */
    val safeImagePasteMaxFiles: Int
        get() = imagePasteMaxFiles.coerceAtLeast(1)

    /** Unique stable key for SharedPreferences lookups */
    val prefsKey: String get() = if (isLocal) {
        "${id}_local"
    } else {
        "${id}_${username}_${host}"
    }

    /** Terminal type string sent to the SSH server */
    val termType: String get() = if (useGhosttyTerm) "xterm-ghostty" else "xterm-256color"
}

@Serializable
sealed interface AuthType {
    @Serializable
    data class Password(val password: String) : AuthType

    @Serializable
    data class PublicKey(
        val privateKeyPem: String,
        val passphrase: String? = null,
    ) : AuthType
}
