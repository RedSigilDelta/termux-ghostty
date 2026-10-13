package com.termux.app.settings

import com.termux.shared.logger.Logger
import com.termux.shared.termux.TermuxConstants
import com.termux.terminal.TerminalConstants
import java.io.File
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray

@Serializable
enum class BellBehaviour {
    @SerialName("vibrate")
    VIBRATE,

    @SerialName("beep")
    BEEP,

    @SerialName("ignore")
    IGNORE,
}

@Serializable
enum class CursorStyle(val terminalValue: Int) {
    @SerialName("block")
    BLOCK(TerminalConstants.TERMINAL_CURSOR_STYLE_BLOCK),

    @SerialName("underline")
    UNDERLINE(TerminalConstants.TERMINAL_CURSOR_STYLE_UNDERLINE),

    @SerialName("bar")
    BAR(TerminalConstants.TERMINAL_CURSOR_STYLE_BAR),
}

@Serializable
enum class BackKeyBehaviour {
    @SerialName("back")
    BACK,

    @SerialName("escape")
    ESCAPE,
}

@Serializable
enum class VolumeKeysBehaviour {
    @SerialName("virtual")
    VIRTUAL,

    @SerialName("volume")
    VOLUME,
}

@Serializable
enum class SoftKeyboardToggleBehaviour {
    @SerialName("show/hide")
    SHOW_HIDE,

    @SerialName("enable/disable")
    ENABLE_DISABLE,
}

@Serializable
enum class NightModeSetting(val value: String) {
    @SerialName("true")
    TRUE("true"),

    @SerialName("false")
    FALSE("false"),

    @SerialName("system")
    SYSTEM("system"),
}

@Serializable
enum class MaterialYouTheme(val value: String) {
    @SerialName("disabled")
    DISABLED("disabled"),

    @SerialName("light")
    LIGHT("light"),

    @SerialName("dark")
    DARK("dark"),

    @SerialName("black")
    BLACK("black"),

    @SerialName("system")
    SYSTEM("system"),
}

@Serializable
enum class TabBarPosition {
    @SerialName("top")
    TOP,

    @SerialName("bottom")
    BOTTOM,
}

@Serializable
enum class TabBarAlign {
    @SerialName("left")
    LEFT,

    @SerialName("center")
    CENTER,

    @SerialName("right")
    RIGHT,
}

private val DEFAULT_EXTRA_KEYS: JsonArray = Json.parseToJsonElement(
    """[["ESC","/",{"key":"-","popup":"|"},"HOME","UP","END","PGUP"],["TAB","CTRL","ALT","LEFT","DOWN","RIGHT","PGDN"]]""",
) as JsonArray

private const val TAG = "TermuxSettings"

/** Out-of-range values are rejected so they fall back to the stored value instead of being clamped. */
@Serializable
data class TermuxSettings(
    @SerialName("allow-external-apps") val allowExternalApps: Boolean = false,
    @SerialName("disable-file-share-receiver") val disableFileShareReceiver: Boolean = false,
    @SerialName("disable-file-view-receiver") val disableFileViewReceiver: Boolean = false,
    @SerialName("disable-hardware-keyboard-shortcuts") val disableHardwareKeyboardShortcuts: Boolean = false,
    @SerialName("disable-terminal-session-change-toast") val disableTerminalSessionChangeToast: Boolean = false,
    @SerialName("enforce-char-based-input") val enforceCharBasedInput: Boolean = false,
    @SerialName("extra-keys-text-all-caps") val extraKeysTextAllCaps: Boolean = true,
    @SerialName("hide-soft-keyboard-on-startup") val hideSoftKeyboardOnStartup: Boolean = false,
    @SerialName("remember-soft-keyboard-state") val rememberSoftKeyboardState: Boolean = false,
    @SerialName("run-termux-am-socket-server") val runTermuxAmSocketServer: Boolean = true,
    @SerialName("terminal-onclick-url-open") val terminalOnclickUrlOpen: Boolean = false,
    @SerialName("terminal-onclick-url-open-when-mouse-tracking-active")
    val terminalOnclickUrlOpenWhenMouseTrackingActive: Boolean = false,
    @SerialName("unconditional-soft-keyboard-on-tap") val unconditionalSoftKeyboardOnTap: Boolean = true,
    @SerialName("ctrl-space-workaround") val ctrlSpaceWorkaround: Boolean = false,
    @SerialName("fullscreen") val fullscreen: Boolean = false,
    @SerialName("use-session-tabs") val useSessionTabs: Boolean = false,
    @SerialName("bell-character") val bellBehaviour: BellBehaviour = BellBehaviour.VIBRATE,
    @SerialName("delete-tmpdir-files-older-than-x-days-on-exit") val deleteTmpdirFilesOlderThanXDaysOnExit: Int = 3,
    /** 0 disables blinking. */
    @SerialName("terminal-cursor-blink-rate") val terminalCursorBlinkRate: Int = 0,
    @SerialName("terminal-cursor-style") val terminalCursorStyle: CursorStyle = CursorStyle.BLOCK,
    @SerialName("terminal-margin-horizontal") val terminalMarginHorizontal: Int = 3,
    @SerialName("terminal-margin-vertical") val terminalMarginVertical: Int = 0,
    @SerialName("terminal-transcript-rows") val terminalTranscriptRows: Int = TerminalConstants.DEFAULT_TERMINAL_TRANSCRIPT_ROWS,
    @SerialName("terminal-toolbar-height") val terminalToolbarHeight: Float = 1f,
    /** `ctrl+<key>` shortcuts, null when unset. */
    @SerialName("shortcut-create-session") val shortcutCreateSession: String? = null,
    @SerialName("shortcut-next-session") val shortcutNextSession: String? = null,
    @SerialName("shortcut-previous-session") val shortcutPreviousSession: String? = null,
    @SerialName("shortcut-rename-session") val shortcutRenameSession: String? = null,
    @SerialName("back-key") val backKey: BackKeyBehaviour = BackKeyBehaviour.BACK,
    @SerialName("default-working-directory") val defaultWorkingDirectory: String = TermuxConstants.TERMUX_HOME_DIR_PATH,
    @SerialName("extra-keys") val extraKeys: JsonArray = DEFAULT_EXTRA_KEYS,
    @SerialName("extra-keys-style") val extraKeysStyle: String = "default",
    @SerialName("night-mode") val nightMode: NightModeSetting = NightModeSetting.SYSTEM,
    @SerialName("material-you-theme") val materialYouTheme: MaterialYouTheme = MaterialYouTheme.DISABLED,
    @SerialName("soft-keyboard-toggle-behaviour")
    val softKeyboardToggleBehaviour: SoftKeyboardToggleBehaviour = SoftKeyboardToggleBehaviour.SHOW_HIDE,
    @SerialName("volume-keys") val volumeKeys: VolumeKeysBehaviour = VolumeKeysBehaviour.VIRTUAL,
    @SerialName("session-tab-bar-position") val sessionTabBarPosition: TabBarPosition = TabBarPosition.BOTTOM,
    @SerialName("session-tab-bar-align") val sessionTabBarAlign: TabBarAlign = TabBarAlign.LEFT,
) {
    init {
        require(deleteTmpdirFilesOlderThanXDaysOnExit in -1..100000) { "delete-tmpdir-files-older-than-x-days-on-exit" }
        require(
            terminalCursorBlinkRate == 0 ||
                terminalCursorBlinkRate in TerminalConstants.TERMINAL_CURSOR_BLINK_RATE_MIN..TerminalConstants.TERMINAL_CURSOR_BLINK_RATE_MAX,
        ) { "terminal-cursor-blink-rate" }
        require(terminalMarginHorizontal in 0..100) { "terminal-margin-horizontal" }
        require(terminalMarginVertical in 0..100) { "terminal-margin-vertical" }
        require(
            terminalTranscriptRows in TerminalConstants.TERMINAL_TRANSCRIPT_ROWS_MIN..TerminalConstants.TERMINAL_TRANSCRIPT_ROWS_MAX,
        ) { "terminal-transcript-rows" }
        require(terminalToolbarHeight in 0.4f..3f) { "terminal-toolbar-height" }
    }

    /** The configured working directory, or the home directory if it is not a readable directory. */
    fun resolveDefaultWorkingDirectory(): String {
        val dir = File(defaultWorkingDirectory)
        if (dir.isDirectory && dir.canRead()) return defaultWorkingDirectory
        Logger.logError(TAG, "default-working-directory \"$defaultWorkingDirectory\" is not a readable directory, using home")
        return TermuxConstants.TERMUX_HOME_DIR_PATH
    }

    companion object {
        /** Parses a `ctrl+<key>` shortcut into the key's code point, or null if unset or invalid. */
        @JvmStatic
        fun shortcutCodePoint(shortcut: String?): Int? {
            if (shortcut == null) return null
            val parts = shortcut.lowercase().trim().split("+")
            val key = parts.getOrNull(1)?.trim()
            if (parts.size != 2 || parts[0].trim() != "ctrl" || key.isNullOrEmpty() || key.codePointCount(0, key.length) != 1) {
                Logger.logError(TAG, "Keyboard shortcut \"$shortcut\" is not Ctrl+<something>")
                return null
            }
            return key.codePointAt(0)
        }
    }
}
