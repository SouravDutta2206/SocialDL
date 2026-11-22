package com.socialdl.src.core.common.util

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File

/**
 * Shared utilities for MediaStore queries and file existence checks.
 */
object MediaStorageUtils {

    private const val TAG = "MediaStorageUtils"

    /**
     * Checks whether a media item pointed to by a content:// or file:// URI actually exists on disk.
     */
    fun doesMediaFileExist(context: Context, uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) return false
        return try {
            val uri = Uri.parse(uriString)
            when (uri.scheme) {
                "content" -> {
                    // Bug #13: a SAF/document URI (folder grants, document
                    // providers) does not expose MediaStore columns. Querying
                    // IS_TRASHED on it throws IllegalArgumentException on strict
                    // DocumentsProviders, so existence is proven with the
                    // official document-id column plus an actual open.
                    if (DocumentsContract.isDocumentUri(context, uri)) {
                        val existsAsDocument = context.contentResolver.query(
                            uri,
                            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                            null,
                            null,
                            null
                        )?.use { cursor -> cursor.moveToFirst() } ?: false
                        if (!existsAsDocument) return false
                        return context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                            pfd.statSize > 0 || (pfd.statSize == -1L && pfd.fileDescriptor.valid())
                        } ?: false
                    }

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
     *
     * @return true when the entry was actually deleted (or already absent), false when
     *         deletion failed and a ghost file remains. Callers must check the result
     *         before dropping their DB record.
     */
    fun deleteMediaFromStorage(context: Context, uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) return true
        return try {
            val uri = Uri.parse(uriString)
            when (uri.scheme) {
                "content" -> {
                    if (DocumentsContract.isDocumentUri(context, uri)) {
                        // Bug #13: DocumentsProvider URIs are deleted through the
                        // SAF API, not ContentResolver.delete (which strict
                        // providers reject). An already-absent document also
                        // counts as success.
                        DocumentsContract.deleteDocument(context.contentResolver, uri) ||
                            !doesMediaFileExist(context, uriString)
                    } else {
                        // MediaStore: delete() returns 0 when the row is already
                        // gone. Confirm with an existence check so an already
                        // deleted item reports true (as documented) instead of a
                        // phantom deletion failure.
                        context.contentResolver.delete(uri, null, null) > 0 ||
                            !doesMediaFileExist(context, uriString)
                    }
                }
                "file" -> {
                    val path = uri.path ?: return true
                    File(path).delete() || !File(path).exists()
                }
                else -> {
                    File(uriString).delete() || !File(uriString).exists()
                }
            }
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to delete storage file for uri: $uriString", e)
            false
        }
    }
}
