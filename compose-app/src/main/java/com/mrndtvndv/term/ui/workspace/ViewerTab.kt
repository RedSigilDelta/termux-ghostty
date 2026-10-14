@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.mrndtvndv.term.ui.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mrndtvndv.term.domain.SftpClient
import com.mrndtvndv.term.ui.sftp.SftpFileViewerScreen
import com.mrndtvndv.term.workspace.Tab
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private sealed interface ViewerFile {
    data object Loading : ViewerFile
    data class Ready(val file: File) : ViewerFile
    data class Failed(val message: String) : ViewerFile
}

/** Downloads the tab's remote file once per tab, then shows it in the text viewer. */
@Composable
fun ViewerTab(client: SftpClient?, tab: Tab.Viewer, onClose: () -> Unit) {
    val cacheDir = LocalContext.current.cacheDir
    val state by produceState<ViewerFile>(ViewerFile.Loading, tab.id) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val source = checkNotNull(client) { "No SFTP connection" }
                val name = File(tab.path).name
                val target = File(cacheDir, "viewer/${tab.id}/$name")
                target.parentFile?.mkdirs()
                source.downloadFile(tab.path, target) {}
                ViewerFile.Ready(target)
            }.getOrElse { error ->
                ViewerFile.Failed(error.message ?: "Couldn't open the file")
            }
        }
    }

    when (val current = state) {
        ViewerFile.Loading -> Box(modifier = Modifier.fillMaxSize()) {
            LoadingIndicator(modifier = Modifier.align(Alignment.Center))
        }
        is ViewerFile.Failed -> Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = current.message,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onClose) { Text("Close") }
        }
        is ViewerFile.Ready -> SftpFileViewerScreen(file = current.file, onClose = onClose)
    }
}
