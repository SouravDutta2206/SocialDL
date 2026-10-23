package com.socialdl.src.core.common.util

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.util.Locale

/**
 * Shared utilities for MediaStore queries, file existence checks, and MIME type resolution.
 */
object MediaStorageUtils {

    private const val TAG = "MediaStorageUtils"

    /**
     * Resolves standard MIME type from media container extension and audio/video classification.
     */
    fun getMimeType(container: String, isAudio: Boolean = false): String {
        return when (container.lowercase(Locale.ROOT)) {
            "mp4" -> if (isAudio) "audio/mp4" else "video/mp4"
            "mkv" -> "video/x-matroska"
            "webm" -> if (isAudio) "audio/webm" else "video/webm"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "opus" -> "audio/opus"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "aac" -> "audio/aac"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            else -> if (isAudio) "audio/*" else "video/*"
        }
    }

    /**
     * Checks whether a media item pointed to by a content:// or file:// URI actually exists on disk.
     */
    fun doesMediaFileExist(context: Context, uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) return false
        return try {
            val uri = Uri.parse(uriString)
            when (uri.scheme) {
                "content" -> {
                    val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.IS_TRASHED)
                    } else {
                        arrayOf(MediaStore.MediaColumns._ID)
                    }
                    var existsInMediaStore = false
                    var isTrashed = false
                    context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) {
                            existsInMediaStore = true
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                val trashedIdx = cursor.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED)
                                if (trashedIdx != -1) {
                                    isTrashed = cursor.getInt(trashedIdx) != 0
                                }
                            }
                        }
                    }
                    if (!existsInMediaStore || isTrashed) {
                        return false
                    }

                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        pfd.statSize > 0 || (pfd.statSize == -1L && pfd.fileDescriptor.valid())
                    } ?: false
                }
                "file" -> {
                    val path = uri.path ?: return false
                    val file = File(path)
                    file.exists() && file.length() > 0
                }
                else -> {
                    val file = File(uriString)
                    file.exists() && file.length() > 0
                }
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Deletes a media file from MediaStore (Scoped Storage) or local filesystem.
     */
    fun deleteMediaFromStorage(context: Context, uriString: String?) {
        if (uriString.isNullOrBlank()) return
        try {
            val uri = Uri.parse(uriString)
            when (uri.scheme) {
                "content" -> {
                    context.contentResolver.delete(uri, null, null)
                }
                "file" -> {
                    uri.path?.let { File(it).delete() }
                }
                else -> {
                    File(uriString).delete()
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to delete storage file for uri: $uriString", e)
        }
    }
}
