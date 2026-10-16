package com.mrndtvndv.term.data.prefs

import android.view.KeyEvent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What a key-bar item does. Swipe directions accept only [Key], [Text], [Macro] and [Host]. */
@Serializable
sealed interface KeyBarAction {
    /** A terminal key from [KeyBarKeyCatalog], encoded by the existing key path. */
    @Serializable
    @SerialName("key")
    data class Key(val keyCode: Int) : KeyBarAction

    /** Literal UTF-8 terminal input, sent in one write with nothing appended. */
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : KeyBarAction

    /** Toggles Ctrl on tap and locks it on long press. */
    @Serializable
    @SerialName("ctrl")
    data object Ctrl : KeyBarAction

    @Serializable
    @SerialName("alt")
    data object Alt : KeyBarAction

    /** The arrow joystick. It is its own gesture target and has no swipe actions. */
    @Serializable
    @SerialName("joystick")
    data object Joystick : KeyBarAction

    /** Steps sent in order in one write. Nothing is appended and no shell runs. Enter is sent only if a step has it. */
    @Serializable
    @SerialName("macro")
    data class Macro(val steps: List<MacroStep>) : KeyBarAction

    /** Opens a Herdr host sheet. Only the Herdr terminal route can run it; elsewhere it is not offered. */
    @Serializable
    @SerialName("host")
    data class Host(val kind: KeyBarHostAction) : KeyBarAction
}

/** The Herdr host sheets a key-bar item can open: Git and the SFTP file browser. */
@Serializable
enum class KeyBarHostAction {
    @SerialName("git")
    Git,

    @SerialName("sftp")
    Sftp,
}

/** One step of a [KeyBarAction.Macro]. Ctrl, Alt and Shift on a key step apply to that step only. */
@Serializable
sealed interface MacroStep {
    /** Literal UTF-8 text, sent as written. */
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : MacroStep

    /** A key from [MacroKeyCatalog], with its own modifiers. */
    @Serializable
    @SerialName("key")
    data class Key(
        val keyCode: Int,
        val ctrl: Boolean = false,
        val alt: Boolean = false,
        val shift: Boolean = false,
    ) : MacroStep
}

/** One key-bar item. Swipe fields are physical screen directions; null means the direction is unassigned. */
@Serializable
data class KeyBarItem(
    val id: String,
    val label: String,
    val tap: KeyBarAction,
    val swipeUp: KeyBarAction? = null,
    val swipeDown: KeyBarAction? = null,
    val swipeLeft: KeyBarAction? = null,
    val swipeRight: KeyBarAction? = null,
)

data class KeyBarLayout(val items: List<KeyBarItem>)

/** The bar users get when no layout is saved: Esc, Ctrl, Alt, Tab, then the arrow joystick. */
fun defaultKeyBarLayout(): KeyBarLayout = KeyBarLayout(
    listOf(
        KeyBarItem(id = "esc", label = "Esc", tap = KeyBarAction.Key(KeyEvent.KEYCODE_ESCAPE)),
        KeyBarItem(id = "ctrl", label = "Ctrl", tap = KeyBarAction.Ctrl),
        KeyBarItem(id = "alt", label = "Alt", tap = KeyBarAction.Alt),
        KeyBarItem(id = "tab", label = "Tab", tap = KeyBarAction.Key(KeyEvent.KEYCODE_TAB)),
        KeyBarItem(id = "joystick", label = "Arrows", tap = KeyBarAction.Joystick),
    ),
)

/** [spokenName] is what TalkBack says for the key, so abbreviated labels are spelled out. */
data class KeyBarCatalogKey(val keyCode: Int, val label: String, val spokenName: String = label)

/** Terminal keys an item may send. Each one has a keycode that the existing key encoder handles. */
val KeyBarKeyCatalog: List<KeyBarCatalogKey> = listOf(
    KeyBarCatalogKey(KeyEvent.KEYCODE_ESCAPE, "Esc", "Escape"),
    KeyBarCatalogKey(KeyEvent.KEYCODE_TAB, "Tab"),
    KeyBarCatalogKey(KeyEvent.KEYCODE_ENTER, "Enter"),
    KeyBarCatalogKey(KeyEvent.KEYCODE_DEL, "Bksp", "Backspace"),
    KeyBarCatalogKey(KeyEvent.KEYCODE_FORWARD_DEL, "Del", "Delete"),
    KeyBarCatalogKey(KeyEvent.KEYCODE_INSERT, "Ins", "Insert"),
    KeyBarCatalogKey(KeyEvent.KEYCODE_MOVE_HOME, "Home"),
    KeyBarCatalogKey(KeyEvent.KEYCODE_MOVE_END, "End"),
    KeyBarCatalogKey(KeyEvent.KEYCODE_PAGE_UP, "PgUp", "Page up"),
    KeyBarCatalogKey(KeyEvent.KEYCODE_PAGE_DOWN, "PgDn", "Page down"),
    KeyBarCatalogKey(KeyEvent.KEYCODE_DPAD_UP, "Up", "Up arrow"),
    KeyBarCatalogKey(KeyEvent.KEYCODE_DPAD_DOWN, "Down", "Down arrow"),
    KeyBarCatalogKey(KeyEvent.KEYCODE_DPAD_LEFT, "Left", "Left arrow"),
    KeyBarCatalogKey(KeyEvent.KEYCODE_DPAD_RIGHT, "Right", "Right arrow"),
) + (1..12).map { KeyBarCatalogKey(KeyEvent.KEYCODE_F1 + it - 1, "F$it") }

/**
 * Punctuation offered as Text items. The key encoder has no keycodes for these, so they are sent as literal text.
 */
val KeyBarPunctuation: List<String> = listOf("|", "~", "/", "\\", "-", "`", "*", "&", ";", "\$")

/** Single source of truth for editor validation and runtime bounds. Editors must use these, not local copies. */
object KeyBarLimits {
    const val MaxItems = 24
    const val MaxIdLength = 64
    const val MaxLabelLength = 12
    const val MaxTextLength = 256
    const val MaxMacroSteps = 16

    fun isValidLabel(label: String): Boolean {
        val trimmed = label.trim()
        return trimmed.isNotEmpty() && trimmed.length <= MaxLabelLength
    }

    fun isValidText(text: String): Boolean = text.isNotEmpty() && text.length <= MaxTextLength
}

fun KeyBarAction.isSwipeable(): Boolean =
    this is KeyBarAction.Key || this is KeyBarAction.Text || this is KeyBarAction.Macro || this is KeyBarAction.Host

/**
 * True when [normalized] would change nothing. Save paths use this as a gate and reject a layout that is not valid,
 * so an unsupported entry is reported to the editor instead of being silently dropped.
 */
fun KeyBarLayout.isValid(): Boolean = normalized() == this

/**
 * Drops anything that cannot run and bounds what remains: unknown keys, empty or oversized macros, blank labels,
 * duplicate ids, swipe actions that are not swipeable, and items past [KeyBarLimits.MaxItems].
 */
fun KeyBarLayout.normalized(): KeyBarLayout = KeyBarLayout(
    items.asSequence()
        .mapNotNull { it.normalizedOrNull() }
        .distinctBy { it.id }
        .take(KeyBarLimits.MaxItems)
        .toList(),
)

private fun KeyBarItem.normalizedOrNull(): KeyBarItem? {
    val trimmedLabel = label.trim().clipLabel()
    val boundedTap = tap.bounded()
    val validId = id.isNotBlank() && id.length <= KeyBarLimits.MaxIdLength
    if (!validId || !KeyBarLimits.isValidLabel(trimmedLabel) || boundedTap == null) return null
    return copy(
        label = trimmedLabel,
        tap = boundedTap,
        swipeUp = swipeUp.boundedSwipe(),
        swipeDown = swipeDown.boundedSwipe(),
        swipeLeft = swipeLeft.boundedSwipe(),
        swipeRight = swipeRight.boundedSwipe(),
    )
}

private fun String.clipLabel(): String {
    if (length <= KeyBarLimits.MaxLabelLength) return this
    val end = if (Character.isHighSurrogate(this[KeyBarLimits.MaxLabelLength - 1])) {
        KeyBarLimits.MaxLabelLength - 1
    } else {
        KeyBarLimits.MaxLabelLength
    }
    return substring(0, end).trimEnd()
}

private fun KeyBarAction.bounded(): KeyBarAction? = when (this) {
    is KeyBarAction.Key -> takeIf { key -> KeyBarKeyCatalog.any { it.keyCode == key.keyCode } }
    is KeyBarAction.Text -> takeIf { KeyBarLimits.isValidText(it.text) }
    is KeyBarAction.Macro -> takeIf { isValidMacro(it.steps) }
    is KeyBarAction.Host -> this
    KeyBarAction.Ctrl, KeyBarAction.Alt, KeyBarAction.Joystick -> this
}

private fun KeyBarAction?.boundedSwipe(): KeyBarAction? = this?.bounded()?.takeIf { it.isSwipeable() }

private const val KeyBarLayoutVersion = 1

@Serializable
private class StoredKeyBarLayout(val version: Int, val items: List<KeyBarItem>)

private val KeyBarJson = Json { ignoreUnknownKeys = true }

fun encodeKeyBarLayout(layout: KeyBarLayout): String =
    KeyBarJson.encodeToString(StoredKeyBarLayout(KeyBarLayoutVersion, layout.normalized().items))

/**
 * Strict decode. Returns null for unreadable data, an unknown version, or any entry that normalization would
 * change (invalid, duplicate, oversized, or past the item cap). The caller then falls back to
 * [defaultKeyBarLayout]. Only a stored list with no items stays empty, because it was saved on purpose.
 */
fun decodeKeyBarLayout(raw: String): KeyBarLayout? {
    val stored = runCatching { KeyBarJson.decodeFromString<StoredKeyBarLayout>(raw) }.getOrNull() ?: return null
    val layout = KeyBarLayout(stored.items)
    return layout.takeIf { stored.version == KeyBarLayoutVersion && layout.normalized() == layout }
}
