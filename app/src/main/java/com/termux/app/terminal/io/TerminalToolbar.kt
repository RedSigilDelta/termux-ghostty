package com.termux.app.terminal.io

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import com.termux.shared.termux.extrakeys.ExtraKeyButton
import com.termux.shared.termux.extrakeys.ExtraKeysConstants
import com.termux.shared.termux.extrakeys.ExtraKeysInfo
import com.termux.terminal.KeyHandler
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

private val ToolbarRowHeight = 37.5.dp

private data class ExtraKeyEncodingOptions(
    val cursorKeysApplicationMode: Boolean,
    val keypadApplicationMode: Boolean,
    val kittyKeyboardFlags: Int
)

fun interface ExtraKeyListener {
    fun onExtraKey(key: String, ctrl: Boolean, alt: Boolean, shift: Boolean, fn: Boolean)
}

fun interface TextInputListener {
    fun onText(text: String)
}

class TerminalToolbarState(visible: Boolean) {
    var extraKeysInfo: ExtraKeysInfo? by mutableStateOf(null)
    var textAllCaps by mutableStateOf(true)
    var heightScale by mutableFloatStateOf(1f)
    var visible by mutableStateOf(visible)
    var backgroundColor by mutableIntStateOf(0)
    var textInput by mutableStateOf("")
    var page by mutableIntStateOf(0)
    var textInputFocusRequest by mutableIntStateOf(0)
        private set
    val modifiers = ExtraKeysController()

    fun isTextInputSelected(): Boolean = page == 1

    fun requestTextInputFocus() {
        textInputFocusRequest++
    }
}

fun bindTerminalToolbar(
    view: ComposeView,
    state: TerminalToolbarState,
    session: () -> TerminalSession?,
    onKey: ExtraKeyListener,
    onToggleKeyboard: Runnable,
    onSendText: TextInputListener,
    onKeysPageSelected: Runnable,
) {
    view.setContent {
        TerminalToolbar(state, session, onKey, onToggleKeyboard, onSendText, onKeysPageSelected)
    }
}

@Composable
fun TerminalToolbar(
    state: TerminalToolbarState,
    session: () -> TerminalSession?,
    onKey: ExtraKeyListener,
    onToggleKeyboard: Runnable,
    onSendText: TextInputListener,
    onKeysPageSelected: Runnable,
) {
    if (!state.visible) return
    val extraKeysInfo = state.extraKeysInfo ?: return
    val background = Color(state.backgroundColor)
    val pagerState = rememberPagerState(initialPage = state.page, pageCount = { 2 })
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.drop(1).collect { page ->
            state.page = page
            if (page == 0) onKeysPageSelected.run()
        }
    }

    MaterialTheme(colorScheme = if (background.luminance() < 0.5f) darkColorScheme() else lightColorScheme()) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxWidth()
                .height(ToolbarRowHeight * state.heightScale * extraKeysInfo.matrix.size)
                .background(background),
        ) { page ->
            if (page == 0) {
                ExtraKeyRows(extraKeysInfo, state, session, onKey, onToggleKeyboard)
            } else {
                TerminalTextInput(state, focusRequester, onSendText)
            }
        }
    }
}

@Composable
private fun ExtraKeyRows(
    extraKeysInfo: ExtraKeysInfo,
    state: TerminalToolbarState,
    session: () -> TerminalSession?,
    onKey: ExtraKeyListener,
    onToggleKeyboard: Runnable,
) {
    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
        for (row in extraKeysInfo.matrix) {
            Row(
                modifier = Modifier.fillMaxWidth().height(ToolbarRowHeight * state.heightScale),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                for (buttonInfo in row) {
                    ExtraKeyButtonComponent(
                        buttonInfo = buttonInfo,
                        state = state,
                        session = session,
                        onKey = onKey,
                        onToggleKeyboard = onToggleKeyboard,
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    )
                }
            }
        }
    }
}

@Composable
private fun TerminalTextInput(
    state: TerminalToolbarState,
    focusRequester: FocusRequester,
    onSendText: TextInputListener,
) {
    LaunchedEffect(state.page, state.textInputFocusRequest) {
        if (state.isTextInputSelected()) focusRequester.requestFocus()
    }
    TextField(
        value = state.textInput,
        onValueChange = { state.textInput = it },
        modifier = Modifier.fillMaxSize().focusRequester(focusRequester),
        singleLine = true,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = {
            onSendText.onText(state.textInput)
            state.textInput = ""
        }),
    )
}

@Composable
private fun ExtraKeyButtonComponent(
    buttonInfo: ExtraKeyButton,
    state: TerminalToolbarState,
    session: () -> TerminalSession?,
    onKey: ExtraKeyListener,
    onToggleKeyboard: Runnable,
    modifier: Modifier = Modifier,
) {
    val view = LocalView.current
    val extraKeysController = state.modifiers
    var isPressed by remember { mutableStateOf(false) }
    var showPopup by remember { mutableStateOf(false) }

    val isModifier = buttonInfo.key == "CTRL" || buttonInfo.key == "ALT" || buttonInfo.key == "SHIFT" || buttonInfo.key == "FN"
    val isKeyboardToggle = buttonInfo.key == "KEYBOARD"

    val modifierState = when (buttonInfo.key) {
        "CTRL" -> extraKeysController.ctrlState
        "ALT" -> extraKeysController.altState
        "SHIFT" -> extraKeysController.shiftState
        "FN" -> extraKeysController.fnState
        else -> ModifierState.INACTIVE
    }

    val isActive = modifierState != ModifierState.INACTIVE
    val isLocked = modifierState == ModifierState.LOCKED

    fun dispatch(key: ExtraKeyButton) {
        dispatchExtraKey(key, extraKeysController, session(), onKey, onToggleKeyboard)
    }

    fun onModifierClick() {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        when (buttonInfo.key) {
            "CTRL" -> extraKeysController.toggleControl()
            "ALT" -> extraKeysController.toggleAlt()
            "SHIFT" -> extraKeysController.toggleShift()
            "FN" -> extraKeysController.toggleFn()
        }
    }

    fun onModifierLongClick() {
        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        when (buttonInfo.key) {
            "CTRL" -> extraKeysController.lockControl()
            "ALT" -> extraKeysController.lockAlt()
            "SHIFT" -> extraKeysController.lockShift()
            "FN" -> extraKeysController.lockFn()
        }
    }

    val backgroundColor = when {
        isLocked -> MaterialTheme.colorScheme.primary
        isActive -> MaterialTheme.colorScheme.primaryContainer
        isPressed -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
        else -> Color.Transparent
    }

    val textColor = when {
        isLocked -> MaterialTheme.colorScheme.onPrimary
        isActive -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    val borderModifier = if (isLocked) {
        Modifier.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .then(borderModifier)
            .clip(CircleShape)
            .background(backgroundColor)
            .pointerInput(buttonInfo) {
                coroutineScope {
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown()
                            isPressed = true
                            var isLongPressed = false
                            var isSwipedUp = false
                            showPopup = false

                            val longPressJob = launch {
                                delay(400) // Default Android long press timeout is 400ms
                                isLongPressed = true
                                if (isModifier) {
                                    onModifierLongClick()
                                } else if (ExtraKeysConstants.PRIMARY_REPETITIVE_KEYS.contains(buttonInfo.key)) {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                    while (true) {
                                        dispatch(buttonInfo)
                                        delay(80) // Repeat delay (80ms)
                                    }
                                }
                            }

                            val pointerId = down.id
                            var active = true
                            while (active) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == pointerId }
                                if (change == null) {
                                    active = false
                                    longPressJob.cancel()
                                    isPressed = false
                                    showPopup = false
                                } else if (change.pressed) {
                                    val isAbove = change.position.y < 0
                                    if (buttonInfo.popup != null) {
                                        if (isAbove && !isSwipedUp) {
                                            isSwipedUp = true
                                            showPopup = true
                                            longPressJob.cancel()
                                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        } else if (!isAbove && isSwipedUp) {
                                            isSwipedUp = false
                                            showPopup = false
                                        }
                                    }
                                    change.consume()
                                } else {
                                    active = false
                                    longPressJob.cancel()
                                    isPressed = false
                                    showPopup = false

                                    if (isSwipedUp && buttonInfo.popup != null) {
                                        dispatch(buttonInfo.popup!!)
                                    } else if (isKeyboardToggle) {
                                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                        onToggleKeyboard.run()
                                    } else if (!isLongPressed || isModifier) {
                                        if (isModifier) {
                                            if (!isLongPressed) onModifierClick()
                                        } else {
                                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                            dispatch(buttonInfo)
                                        }
                                    }
                                    change.consume()
                                }
                            }
                        }
                    }
                }
            }
            .padding(horizontal = 2.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (state.textAllCaps) buttonInfo.display.uppercase() else buttonInfo.display,
            color = textColor,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1
        )

        if (showPopup && buttonInfo.popup != null) {
            Popup(
                alignment = Alignment.TopCenter,
                offset = IntOffset(0, -110)
            ) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = buttonInfo.popup!!.display,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            }
        }
    }
}

private fun dispatchExtraKey(
    buttonInfo: ExtraKeyButton,
    extraKeysController: ExtraKeysController,
    session: TerminalSession?,
    onKey: ExtraKeyListener,
    onToggleKeyboard: Runnable,
) {
    val key = buttonInfo.key
    if (key == "KEYBOARD") {
        onToggleKeyboard.run()
        return
    }
    // Treat as macro if: the ExtraKeyButton is explicitly a macro ({macro: '...'}),
    // OR it's a plain string key that contains spaces (e.g. 'CTRL b n') — convenience format.
    val isMacro = buttonInfo.isMacro ||
        (key.contains(" ") && !ExtraKeysConstants.PRIMARY_KEY_CODES_FOR_STRINGS.containsKey(key))

    if (isMacro) {
        if (session != null) sendMacro(key, session)
        return
    }

    // Single key: read and consume the one-shot modifiers, then hand them to the terminal.
    val ctrl = extraKeysController.readControl()
    val alt = extraKeysController.readAlt()
    val shift = extraKeysController.readShift()
    val fn = extraKeysController.readFn()
    try {
        onKey.onExtraKey(key, ctrl, alt, shift, fn)
    } finally {
        extraKeysController.clearConsumedModifiers()
    }
}

private fun sendMacro(macro: String, session: TerminalSession) {
    session.setGhosttyTopRow(0)
    val sequence = encodeExtraKeyMacro(
        macro = macro,
        cursorKeysApplicationMode = session.isCursorKeysApplicationMode,
        keypadApplicationMode = session.isKeypadApplicationMode,
        kittyKeyboardFlags = session.getKittyKeyboardFlags()
    )
    if (sequence.isNotEmpty()) {
        session.setCursorBlinkState(true)
        session.write(sequence)
    }
}

private fun encodeExtraKeyMacro(
    macro: String,
    cursorKeysApplicationMode: Boolean,
    keypadApplicationMode: Boolean,
    kittyKeyboardFlags: Int = 0
): String {
    val sequence = StringBuilder()
    val encodingOptions = ExtraKeyEncodingOptions(
        cursorKeysApplicationMode = cursorKeysApplicationMode,
        keypadApplicationMode = keypadApplicationMode,
        kittyKeyboardFlags = kittyKeyboardFlags
    )
    var ctrl = false
    var alt = false
    var shift = false
    var fn = false
    for (part in macro.split(" ")) {
        when (part.uppercase()) {
            "CTRL", "CONTROL" -> ctrl = true
            "ALT" -> alt = true
            "SHIFT", "SHFT" -> shift = true
            "FN", "FUNCTION" -> fn = true
            else -> {
                sequence.append(
                    encodeExtraKeyPart(
                        key = part,
                        ctrl = ctrl,
                        alt = alt,
                        shift = shift,
                        fn = fn,
                        options = encodingOptions
                    )
                )
                ctrl = false
                alt = false
                shift = false
                fn = false
            }
        }
    }
    return sequence.toString()
}

private fun encodeExtraKeyPart(
    key: String,
    ctrl: Boolean,
    alt: Boolean,
    shift: Boolean,
    fn: Boolean,
    options: ExtraKeyEncodingOptions
): String {
    val keyCode = ExtraKeysConstants.PRIMARY_KEY_CODES_FOR_STRINGS[key]
    val keyCodeSequence = if (keyCode != null && !fn) {
        var keyModifiers = 0
        if (ctrl) keyModifiers = keyModifiers or KeyHandler.KEYMOD_CTRL
        if (alt) keyModifiers = keyModifiers or KeyHandler.KEYMOD_ALT
        if (shift) keyModifiers = keyModifiers or KeyHandler.KEYMOD_SHIFT
        KeyHandler.getCode(
            keyCode,
            keyModifiers,
            options.cursorKeysApplicationMode,
            options.keypadApplicationMode,
            options.kittyKeyboardFlags
        ).orEmpty()
    } else {
        null
    }
    if (keyCodeSequence != null) return keyCodeSequence
    if (key.length != 1) return key
    var codePoint = key.codePointAt(0)
    var isUpperCase = codePoint in 'A'.code..'Z'.code
    if (shift && codePoint in 'a'.code..'z'.code) {
        codePoint -= 'a'.code - 'A'.code
        isUpperCase = true
    }
    val textSequence = if (ctrl && isUpperCase && shift) {
        val modifier = if (alt) 14 else 6
        "\u001b[${codePoint};${modifier}u"
    } else {
        val mappedCodePoint = if (ctrl) controlCodePoint(codePoint) else codePoint
        buildString {
            if (alt) append('\u001b')
            append(String(Character.toChars(mappedCodePoint)))
        }
    }
    return textSequence
}

private fun controlCodePoint(codePoint: Int): Int = when (codePoint) {
    in 'a'.code..'z'.code -> codePoint - 'a'.code + 1
    in 'A'.code..'Z'.code -> codePoint - 'A'.code + 1
    ' '.code, '2'.code -> 0
    '['.code, '3'.code -> 27
    '\\'.code, '4'.code -> 28
    ']'.code, '5'.code -> 29
    '^'.code, '6'.code -> 30
    '_'.code, '7'.code, '/'.code -> 31
    '8'.code -> 127
    else -> codePoint
}
