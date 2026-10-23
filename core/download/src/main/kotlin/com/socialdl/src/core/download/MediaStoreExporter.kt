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
 */
@Singleton
class MediaStoreExporter @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val tag = "MediaStoreExporter"

    fun export(
        file: File,
        title: String,
        author: String?,
        container: String,
        isAudio: Boolean
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

        try {
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
            return itemUri
        } catch (e: Exception) {
            Logger.e(tag, "Failed to copy file to MediaStore URI: $itemUri", e)
            resolver.delete(itemUri, null, null)
            return null
        }
    }
}
