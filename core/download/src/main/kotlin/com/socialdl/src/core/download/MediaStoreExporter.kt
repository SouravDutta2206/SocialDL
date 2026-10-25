package com.socialdl.src.core.download

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.socialdl.src.core.common.util.Logger
import com.socialdl.src.core.common.util.MediaStorageUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Handles exporting completed downloaded/muxed media files into Android's system MediaStore
 * following Scoped Storage best practices (§9.7).
 *
 * Export hardening (P1-3):
 * 1. The row is created with IS_PENDING=1 and the resulting content URI is reported
 *    to the worker via [onPendingRowCreated] so it can be persisted in the job
 *    entity (durable across process death) and so a mid-copy cancellation guard
 *    can run right after creation.
 * 2. The worker copies while the row is pending, then IS_PENDING is cleared.
 * 3. Any copy failure deletes the pending row in a finally-guarded path.
 * 4. Pending rows orphaned by process death are swept by startup reconciliation
 *    ([DownloadManager.reconcileOnStartup]).
 */
@Singleton
class MediaStoreExporter @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tag = "MediaStoreExporter"

    /**
     * @param onPendingRowCreated called with the created IS_PENDING row; return
     *   false to abort the copy (e.g. the job row was deleted by a cancel — the
     *   pending row is deleted before returning).
     * @return the finalized content URI, or null on failure.
     */
    suspend fun export(
        file: File,
        title: String,
        author: String?,
        container: String,
        isAudio: Boolean,
        onPendingRowCreated: suspend (Uri) -> Boolean
    ): Uri? {
        val resolver = context.contentResolver
        val ext = container.lowercase(Locale.ROOT)
        val sanitizedTitle = title.replace(Regex("[^a-zA-Z0-9.-]"), "_")
            .replace(Regex("_+"), "_")
            .take(50)
            .trim('_')
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "${sanitizedTitle}_$timestamp.$ext"

        val mimeType = MediaStorageUtils.getMimeType(container, isAudio)

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    if (isAudio) "${Environment.DIRECTORY_MUSIC}/SocialDL" else "${Environment.DIRECTORY_MOVIES}/SocialDL"
                )
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val collection = if (isAudio) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
        }

        val itemUri = resolver.insert(collection, contentValues) ?: return null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // P1-3: persist the pending URI (durable across process death) and run
            // the mid-copy cancellation guard right after creation.
            val proceed = try {
                onPendingRowCreated(itemUri)
            } catch (e: Exception) {
                Logger.e(tag, "Pending-row callback failed; deleting pending row $itemUri", e)
                resolver.delete(itemUri, null, null)
                throw e
            }
            if (!proceed) {
                Logger.i(tag, "Copy aborted right after pending-row creation; deleting pending row $itemUri")
                resolver.delete(itemUri, null, null)
                return null
            }
        }

        return try {
            resolver.openOutputStream(itemUri)?.use { output ->
                file.inputStream().use { input ->
                    input.copyTo(output)
                }
            } ?: return null

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(itemUri, contentValues, null, null)
            }
            itemUri
        } catch (e: Exception) {
            Logger.e(tag, "Failed to copy file to MediaStore URI: $itemUri", e)
            // finally-guarded: never leave a dangling pending row behind a failed copy
            resolver.delete(itemUri, null, null)
            null
        }
    }
}
