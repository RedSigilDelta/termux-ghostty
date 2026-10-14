package com.termux.app.workspace.ui

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCode
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.markdownPadding
import com.termux.R
import com.termux.app.terminal.io.FloatingBar
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import dev.snipme.highlights.Highlights
import dev.snipme.highlights.model.SyntaxThemes
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val MaxPreviewBytes = 2L * 1024 * 1024
private const val BinarySniffBytes = 8192
// The bottom inset keeps the end of the content above the floating bar.
private val ContentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp)
private val MarkdownExtensions = setOf("md", "markdown")
private val HtmlExtensions = setOf("html", "htm")
private val TextExtensions = setOf(
    "txt", "log", "json", "yaml", "yml", "toml", "xml", "csv", "sh", "bash", "zsh", "fish", "py", "kt", "kts",
    "java", "js", "ts", "c", "h", "cpp", "rs", "go", "zig", "gradle", "properties", "ini", "conf", "cfg",
)

internal enum class ViewerKind { MARKDOWN, HTML, TEXT }

internal fun viewerKindFor(name: String): ViewerKind? {
    val dot = name.lastIndexOf('.')
    val extension = if (dot > 0) name.substring(dot + 1).lowercase() else ""
    return when (extension) {
        in MarkdownExtensions -> ViewerKind.MARKDOWN
        in HtmlExtensions -> ViewerKind.HTML
        "" -> ViewerKind.TEXT
        in TextExtensions -> ViewerKind.TEXT
        else -> null
    }
}

private sealed interface ViewerState {
    data object Loading : ViewerState
    data object TooLarge : ViewerState
    data object Binary : ViewerState
    data object Unreadable : ViewerState
    data class Ready(val text: String) : ViewerState
}

@Composable
fun ViewerTab(
    path: String,
    tabsButton: @Composable () -> Unit,
    onOpenFile: (path: String) -> Unit,
    onOpenExternal: (path: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val file = remember(path) { File(path) }
    val kind = remember(path) { viewerKindFor(file.name) ?: ViewerKind.TEXT }
    var reloadKey by remember { mutableIntStateOf(0) }
    val hazeState = rememberHazeState()
    val state by produceState<ViewerState>(initialValue = ViewerState.Loading, key1 = path, key2 = reloadKey) {
        value = ViewerState.Loading
        value = withContext(Dispatchers.IO) { readViewerFile(file, kind) }
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (val current = state) {
                ViewerState.Loading -> Unit
                ViewerState.TooLarge -> Message(stringResource(R.string.viewer_too_large)) {
                    TextButton(onClick = { onOpenExternal(path) }) {
                        Text(stringResource(R.string.viewer_open_with))
                    }
                }
                ViewerState.Binary -> Message(stringResource(R.string.viewer_binary)) {
                    TextButton(onClick = { onOpenExternal(path) }) {
                        Text(stringResource(R.string.viewer_open_with))
                    }
                }
                ViewerState.Unreadable -> Message(stringResource(R.string.viewer_unreadable))
                is ViewerState.Ready -> when (kind) {
                    ViewerKind.MARKDOWN -> MarkdownDocument(current.text, file, onOpenFile, hazeState)
                    ViewerKind.HTML -> key(path, reloadKey) { HtmlDocument(file, onOpenFile) }
                    ViewerKind.TEXT -> TextDocument(current.text, hazeState)
                }
            }
            FloatingBar(
                tabsButton = tabsButton,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                hazeState = hazeState,
            ) {
                IconButton(onClick = { reloadKey++ }) {
                    Icon(Icons.Rounded.Refresh, contentDescription = stringResource(R.string.viewer_reload))
                }
                IconButton(onClick = { onOpenExternal(path) }) {
                    Icon(
                        Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = stringResource(R.string.viewer_open_with),
                    )
                }
            }
        }
    }
}

/** The first [BinarySniffBytes] bytes of [file], or fewer if the file is shorter. */
internal fun readHead(file: File): ByteArray = file.inputStream().use { input ->
    val buffer = ByteArray(BinarySniffBytes)
    buffer.copyOf(maxOf(input.read(buffer), 0))
}

/** Text files have no NUL bytes, so one in the first [BinarySniffBytes] bytes marks the file as binary. */
internal fun looksBinary(bytes: ByteArray): Boolean =
    bytes.copyOf(minOf(bytes.size, BinarySniffBytes)).contains(0.toByte())

private fun readViewerFile(file: File, kind: ViewerKind): ViewerState = try {
    when {
        !file.isFile -> ViewerState.Unreadable
        file.length() > MaxPreviewBytes -> ViewerState.TooLarge
        kind == ViewerKind.HTML -> ViewerState.Ready("")
        kind == ViewerKind.TEXT && looksBinary(readHead(file)) -> ViewerState.Binary
        else -> ViewerState.Ready(file.readText())
    }
} catch (e: IOException) {
    ViewerState.Unreadable
}

@Composable
private fun Message(text: String, action: @Composable () -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxSize().padding(ContentPadding),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action()
    }
}

@Composable
private fun MarkdownDocument(markdown: String, file: File, onOpenFile: (path: String) -> Unit, hazeState: HazeState) {
    val isDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val systemUriHandler = LocalUriHandler.current
    val linkHandler = remember(systemUriHandler, file, onOpenFile) {
        object : UriHandler {
            override fun openUri(uri: String) {
                if (uri.startsWith("http://") || uri.startsWith("https://") || uri.startsWith("mailto:")) {
                    systemUriHandler.openUri(uri)
                } else {
                    localLinkTarget(file, uri)?.let(onOpenFile)
                }
            }
        }
    }
    val highlights = remember(isDark) { Highlights.Builder().theme(SyntaxThemes.default(darkMode = isDark)) }
    val components = remember(highlights) {
        markdownComponents(
            codeFence = { model ->
                MarkdownCodeFence(model.content, model.node, model.typography.code) { code, language, style ->
                    MarkdownHighlightedCode(
                        code = code,
                        language = language,
                        style = style,
                        highlightsBuilder = highlights,
                    )
                }
            },
        )
    }

    CompositionLocalProvider(LocalUriHandler provides linkHandler) {
        SelectionContainer {
            Markdown(
                content = markdown,
                components = components,
                typography = viewerTypography(),
                padding = markdownPadding(
                    block = 8.dp,
                    listItemTop = 2.dp,
                    listItemBottom = 2.dp,
                    codeBlock = PaddingValues(12.dp),
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(hazeState)
                    .verticalScroll(rememberScrollState())
                    .padding(ContentPadding),
            )
        }
    }
}

@Composable
private fun viewerTypography(): MarkdownTypography {
    val body = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 24.sp)
    val code = body.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp)
    fun heading(size: Int) = body.copy(fontSize = size.sp, lineHeight = (size * 1.3f).sp, fontWeight = FontWeight.Bold)
    return markdownTypography(
        h1 = heading(26),
        h2 = heading(22),
        h3 = heading(19),
        h4 = heading(17),
        h5 = heading(16),
        h6 = heading(15),
        text = body,
        paragraph = body,
        ordered = body,
        bullet = body,
        list = body,
        table = body,
        quote = body.copy(fontStyle = FontStyle.Italic),
        code = code,
        inlineCode = code,
    )
}

/** Resolves a Markdown link to an existing local file, or null for anything else. */
private fun localLinkTarget(document: File, link: String): String? {
    val target = Uri.decode(link.substringBefore('#'))
    if (target.isEmpty()) return null
    val file = File(target).let { if (it.isAbsolute) it else File(document.parentFile, target) }
    return if (file.isFile) file.canonicalPath else null
}

@Composable
private fun HtmlDocument(file: File, onOpenFile: (path: String) -> Unit) {
    val uriHandler = LocalUriHandler.current
    val currentOnOpenFile by rememberUpdatedState(onOpenFile)
    AndroidView(
        modifier = Modifier.fillMaxSize().padding(bottom = ContentPadding.calculateBottomPadding()),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = true
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        when (request.url.scheme) {
                            "http", "https" -> uriHandler.openUri(request.url.toString())
                            "file" -> request.url.path?.let(currentOnOpenFile)
                        }
                        return true
                    }
                }
                loadUrl(file.toURI().toString())
            }
        },
        onRelease = { it.destroy() },
    )
}

@Composable
private fun TextDocument(text: String, hazeState: HazeState) {
    SelectionContainer {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            softWrap = false,
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(ContentPadding),
        )
    }
}
