package com.mrndtvndv.term.ui.sftp

import com.mrndtvndv.term.domain.SftpClient
import com.mrndtvndv.term.domain.SftpFile
import java.io.File
import java.io.IOException
import kotlin.coroutines.Continuation
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.suspendCancellableCoroutine

fun folder(path: String) = SftpFile(path.substringAfterLast('/'), path, isDirectory = true, 0, 0, 0)

fun file(path: String) = SftpFile(path.substringAfterLast('/'), path, isDirectory = false, 0, 0, 0)

/**
 * Lists [folders]; a missing folder fails. A held folder waits for [release]. [Hold.IgnoresCancellation] behaves like
 * a blocking native call: the result still arrives after the caller was cancelled.
 */
class FakeSftpClient(private val folders: Map<String, List<SftpFile>>) : SftpClient {
    enum class Hold { Cancellable, IgnoresCancellation }

    val requests = mutableListOf<String>()
    val cancelled = mutableListOf<String>()
    private val holds = mutableMapOf<String, Hold>()
    private val waiting = mutableMapOf<String, ArrayDeque<Continuation<List<SftpFile>>>>()

    fun hold(path: String, mode: Hold = Hold.IgnoresCancellation) {
        holds[path] = mode
    }

    fun release(path: String, result: Result<List<SftpFile>> = listing(path)) {
        waiting.getValue(path).removeFirst().resumeWith(result)
    }

    fun waitingFor(path: String): Int = waiting[path]?.size ?: 0

    override suspend fun listFiles(path: String): List<SftpFile> {
        requests += path
        return when (holds[path]) {
            null -> listing(path).getOrThrow()
            Hold.Cancellable -> suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { cancelled += path }
                waiting.getOrPut(path) { ArrayDeque() }.add(continuation)
            }
            Hold.IgnoresCancellation -> suspendCoroutine { waiting.getOrPut(path) { ArrayDeque() }.add(it) }
        }
    }

    private fun listing(path: String): Result<List<SftpFile>> =
        folders[path]?.let { Result.success(it) } ?: Result.failure(IOException("No such folder: $path"))

    override suspend fun createDirectory(path: String) = Unit
    override suspend fun deleteFile(path: String) = Unit
    override suspend fun renameFile(oldPath: String, newPath: String) = Unit
    override suspend fun downloadFile(remotePath: String, destination: File, onProgress: (Long) -> Unit) = Unit
    override suspend fun uploadFile(source: File, remotePath: String, onProgress: (Long) -> Unit) = Unit
    override fun close() = Unit
}
