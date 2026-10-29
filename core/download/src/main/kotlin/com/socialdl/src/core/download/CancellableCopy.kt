package com.socialdl.src.core.download

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.InputStream
import java.io.OutputStream

/** Chunk size for export copies: large enough to keep I/O cheap, small enough to stop promptly. */
internal const val EXPORT_COPY_BUFFER_SIZE: Int = 64 * 1024

/**
 * Copies [input] to [output] in fixed-size chunks, cooperatively checking the
 * caller's coroutine for cancellation before every chunk (Phase 5 / finding #5).
 *
 * `InputStream.copyTo` cannot be cancelled: a WorkManager stop request would be
 * ignored until the whole file had been copied and published. With this loop a
 * cancelled export stops at the next chunk boundary, and the caller can delete
 * the MediaStore row it had already created.
 *
 * Cancellation is deliberately NOT translated into a result: [kotlinx.coroutines.CancellationException]
 * propagates so the worker observes a cancellation instead of an export failure.
 * Stream failures propagate unchanged as well.
 *
 * @return the number of bytes copied.
 */
internal suspend fun copyStreamCancellable(
    input: InputStream,
    output: OutputStream,
    bufferSize: Int = EXPORT_COPY_BUFFER_SIZE,
): Long {
    require(bufferSize > 0) { "bufferSize must be positive, was $bufferSize" }
    val buffer = ByteArray(bufferSize)
    var copied = 0L
    while (true) {
        // Cancellation point for the whole copy: checked before blocking on read,
        // so at most one chunk is written after a cancel request lands.
        currentCoroutineContext().ensureActive()
        val read = input.read(buffer)
        if (read == -1) break
        output.write(buffer, 0, read)
        copied += read
    }
    return copied
}
