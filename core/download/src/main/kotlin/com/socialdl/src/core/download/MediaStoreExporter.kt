package com.socialdl.src.core.download

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.socialdl.src.core.common.util.Logger
import com.socialdl.src.core.model.FormatKind
import com.socialdl.src.core.model.MediaContainer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles exporting completed downloaded/muxed media files into Android's system MediaStore
 * following Scoped Storage best practices (§9.7).
 *
 * Export hardening (P1-3, extended by Phase 5 / finding #5):
 * 1. The row is created with IS_PENDING=1 and the resulting content URI is reported
 *    to the worker through `onPendingRowCreated` so it can be persisted in the job
 *    entity (durable across process death) and so a mid-copy cancellation guard can
 *    run right after creation.
 * 2. The copy is chunked and checks for cancellation between chunks
 *    ([copyStreamCancellable]), so a cancelled worker stops instead of finishing a
 *    multi-hundred-MB copy.
 * 3. Copy and publish are separate steps. When the bytes are copied the row is
 *    still IS_PENDING=1; the worker's `onBeforePublish` callback confirms the job is
 *    still alive, and only then is IS_PENDING cleared. A cancel that lands after the
 *    copy deletes the pending row instead of publishing a file with no history row.
 * 4. Every failure path deletes exactly the row this export created — a failed copy,
 *    a failed `onPendingRowCreated`, a refused publish, or a cancellation. Cancellation
 *    is rethrown (never reported as `null`), so the worker sees a cancellation rather
 *    than ExportFailed.
 * 5. Pending rows orphaned by process death are swept by startup reconciliation
 *    ([DownloadManager.reconcileOnStartup]).
 *
 * Residual window: once IS_PENDING=0 is written there is no cross-provider
 * transaction with the Room history insert, so a process death in that gap leaves a
 * published file without a history record. Reconciliation cannot see it (the row is
 * no longer pending); that is why publishing happens as late as possible and why the
 * worker compensates when the history write is refused.
 *
 * Pre-Q (API 24-28) has no IS_PENDING: the inserted row is visible while it is being
 * written, but the same delete-on-failure/cancel/publish-abort paths still apply.
 */
@Singleton
class MediaStoreExporter @Inject constructor(
    private val store: MediaStoreGateway,
) {
    private val tag = "MediaStoreExporter"

    /**
     * @param onPendingRowCreated called with the created IS_PENDING row (Q+ only);
     *   return false to abort the copy (e.g. the job row was deleted by a cancel —
     *   the pending row is deleted before returning).
     * @param onBeforePublish called after the copy completed and before the row is
     *   published (IS_PENDING=0); return false to delete the row and abort (e.g.
     *   the job was cancelled while the bytes were being copied).
     * @return the finalized content URI, or null on failure. Cancellation is not a
     *   failure result: the row is deleted and [CancellationException] is rethrown.
     */
    suspend fun export(
        file: File,
        title: String,
        author: String?,
        container: String,
        kind: FormatKind,
        onPendingRowCreated: suspend (Uri) -> Boolean,
        onBeforePublish: suspend () -> Boolean,
    ): Uri? {
        val isAudio = kind == FormatKind.AUDIO
        val isImage = kind == FormatKind.IMAGE
        val ext = container.lowercase(Locale.ROOT)
        val sanitizedTitle = title.replace(Regex("[^a-zA-Z0-9.-]"), "_")
            .replace(Regex("_+"), "_")
            .take(50)
            .trim('_')
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "${sanitizedTitle}_$timestamp.$ext"

        val mimeType = MediaContainer.mimeTypeFor(container, kind)

        // MediaProvider rejects some (extension, MIME) pairs outright — notably
        // audio/webm in the audio collection. Fail as a typed error before building a
        // row instead of letting the provider's IllegalArgumentException escape as
        // "Unknown" from the worker's catch-all.
        if (!MediaContainer.isExportableMimeType(container, kind)) {
            throw UnsupportedMediaTypeException(
                "Cannot export .$ext as $mimeType into the $kind collection"
            )
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    when {
                        isImage -> "${Environment.DIRECTORY_PICTURES}/SocialDL"
                        isAudio -> "${Environment.DIRECTORY_MUSIC}/SocialDL"
                        else -> "${Environment.DIRECTORY_MOVIES}/SocialDL"
                    }
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val collection = when {
            isImage -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
            }
            isAudio -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
                }
            }
            else -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                }
            }
        }

        Logger.d(tag, "Exporting $fileName as $mimeType into $collection")

        val itemUri = try {
            store.insert(collection, contentValues) ?: return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Provider-side rejection (MIME validation, permission, missing volume).
            // Never leave the worker to classify it as an unknown failure.
            Logger.e(tag, "MediaStore rejected the export row ($fileName, $mimeType)", e)
            return null
        }
        val pending = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        if (pending) {
            // P1-3: persist the pending URI (durable across process death) and run
            // the mid-copy cancellation guard right after creation.
            val proceed = try {
                onPendingRowCreated(itemUri)
            } catch (e: CancellationException) {
                Logger.i(tag, "Export cancelled before the copy started; deleting pending row $itemUri")
                deleteRow(itemUri)
                throw e
            } catch (e: Exception) {
                Logger.e(tag, "Pending-row callback failed; deleting pending row $itemUri", e)
                deleteRow(itemUri)
                throw e
            }
            if (!proceed) {
                Logger.i(tag, "Copy aborted right after pending-row creation; deleting pending row $itemUri")
                deleteRow(itemUri)
                return null
            }
        }

        return try {
            val output = store.openOutputStream(itemUri)
            if (output == null) {
                // openOutputStream returned null (no stream available) — same
                // failure class as a failed copy: delete the pending row so no
                // zero-byte IS_PENDING ghost is left in the gallery.
                Logger.e(tag, "openOutputStream returned null for $itemUri; deleting pending row")
                deleteRow(itemUri)
                return null
            }
            output.use { out -> file.inputStream().use { input -> copyStreamCancellable(input, out) } }

            // Copy finished; the row is still pending (Q+) / never marked published
            // (pre-Q). Publish only if the worker confirms the job is still alive: a
            // cancel during the copy must not leave a visible file behind.
            if (!onBeforePublish()) {
                Logger.i(tag, "Export abandoned before publish; deleting row $itemUri")
                deleteRow(itemUri)
                return null
            }

            if (pending) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                val updated = store.update(itemUri, contentValues, null, null)
                if (updated == 0) {
                    // The row vanished while copying (provider cleanup / user action).
                    Logger.w(tag, "IS_PENDING=0 update affected no rows for $itemUri")
                    return null
                }
            }
            itemUri
        } catch (e: CancellationException) {
            // Cancellation may land inside the copy loop or in onBeforePublish. The
            // row this export created must not stay behind; delete it with blocking
            // resolver calls (no suspension point) and let the worker observe the
            // cancellation instead of a failure.
            Logger.i(tag, "Export cancelled; deleting row $itemUri")
            deleteRow(itemUri)
            throw e
        } catch (e: Exception) {
            Logger.e(tag, "Failed to copy file to MediaStore URI: $itemUri", e)
            // finally-guarded: never leave a dangling pending row behind a failed copy
            deleteRow(itemUri)
            null
        }
    }

    /**
     * Deletes exactly the row this export inserted. Never called with anything
     * else, which is what keeps cleanup from touching another job's file or a
     * user's media.
     */
    private fun deleteRow(uri: Uri) {
        runCatching { store.delete(uri, null, null) }
            .onFailure { e -> Logger.w(tag, "Failed to delete MediaStore row $uri", e) }
    }
}

/**
 * The minimal MediaStore surface [MediaStoreExporter] needs: insert a row, open
 * its output stream, update the row, delete the row.
 *
 * Extracted as a seam (Phase 5 / finding #5) so the cancellation, pre-publish
 * abort and compensation paths can be unit-tested against a fake store instead
 * of a real provider. Every delete the exporter performs then becomes observable
 * in tests, which is what the plan's "compensation deletion is limited to the
 * URI created by this export" rule needs.
 */
interface MediaStoreGateway {
    fun insert(collection: Uri, values: ContentValues): Uri?

    fun openOutputStream(uri: Uri): OutputStream?

    fun update(
        uri: Uri,
        values: ContentValues,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int

    fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int
}

/** Production [MediaStoreGateway] over the app's [ContentResolver]. */
class ContentResolverMediaStoreGateway(
    private val resolver: ContentResolver,
) : MediaStoreGateway {

    override fun insert(collection: Uri, values: ContentValues): Uri? =
        resolver.insert(collection, values)

    override fun openOutputStream(uri: Uri): OutputStream? = resolver.openOutputStream(uri)

    override fun update(
        uri: Uri,
        values: ContentValues,
        selection: String?,
        selectionArgs: Array<String>?,
    ): Int = resolver.update(uri, values, selection, selectionArgs)

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int =
        resolver.delete(uri, selection, selectionArgs)
}

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
 * Cancellation is deliberately NOT translated into a result: [CancellationException]
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
