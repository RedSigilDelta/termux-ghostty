package com.mrndtvndv.term.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val PreviewBytes = 4 * 1024
private const val PreviewLines = 40

/** Centered tab icon on the card's preview background. */
@Composable
fun IconPreview(icon: ImageVector, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(40.dp),
        )
    }
}

/** First lines of the downloaded file, read off the main thread. Shows the viewer icon until it can be read. */
@Composable
fun ViewerPreview(file: File, modifier: Modifier = Modifier) {
    val content by produceState(null as String?, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                file.inputStream().use { input ->
                    val buffer = ByteArray(PreviewBytes)
                    val read = maxOf(input.read(buffer), 0)
                    String(buffer, 0, read, Charsets.UTF_8).lines().take(PreviewLines).joinToString("\n")
                }
            }.getOrNull()
        }
    }
    val text = content
    if (text == null) {
        IconPreview(Icons.AutoMirrored.Filled.Article, modifier)
    } else {
        BasicText(
            text = text,
            modifier = modifier
                .background(MaterialTheme.colorScheme.surface)
                .clipToBounds()
                .padding(6.dp),
            style = TextStyle(
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
                fontSize = 6.sp,
                lineHeight = 6.sp * 1.15f,
            ),
            softWrap = false,
        )
    }
}
