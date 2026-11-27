package com.socialdl.src.core.download

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.socialdl.src.core.common.util.Logger
import com.socialdl.src.core.model.FormatKind
import com.socialdl.src.core.model.MediaContainer
import com.socialdl.src.core.settings.DEFAULT_AUDIO_FOLDER_RELATIVE
import com.socialdl.src.core.settings.DEFAULT_IMAGE_FOLDER_RELATIVE
import com.socialdl.src.core.settings.DEFAULT_VIDEO_FOLDER_RELATIVE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
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
 *
 * Custom destination: when a SAF tree URI is configured for the media kind, the file
 * is written as a document inside that tree instead of into MediaStore. SAF has no
 * pending rows, so only the document this export created is deleted on failure or
 * cancellation — the same "delete exactly what we created" rule as above.
 */
@Singleton
class MediaStoreExporter @Inject constructor(
    private val store: MediaStoreGateway,
) {
    private val tag = "MediaStoreExporter"

    /**
     * @param carouselIndex 1-based slot of the item inside a carousel post, or null
     *   for a single item. Slides share one title, so the slot is folded into the
     *   DISPLAY_NAME to keep their MediaStore rows distinct.
     * @param onPendingRowCreated called with the created IS_PENDING row (Q+ only);
     *   return false to abort the copy (e.g. the job row was deleted by a cancel —
     *   the pending row is deleted before returning).
     * @param onBeforePublish called after the copy completed and before the row is
     *   published (IS_PENDING=0); return false to delete the row and abort (e.g.
     *   the job was cancelled while the bytes were being copied).
     * @param customTreeUri when set, the file is written as a document inside this
     *   SAF tree instead of into MediaStore; pending-row callbacks and publish
     *   updates are skipped because SAF documents have no IS_PENDING state.
     * @param trimFilenames truncates long titles so the total file name stays
     *   within [MAX_TRIMMED_FILENAME_LENGTH] characters.
     * @return the finalized content URI, or null on failure. Cancellation is not a
     *   failure result: the row is deleted and [CancellationException] is rethrown.
     */
    suspend fun export(
        file: File,
        title: String,
        container: String,
        kind: FormatKind,
        carouselIndex: Int? = null,
        onPendingRowCreated: suspend (Uri) -> Boolean,
        onBeforePublish: suspend () -> Boolean,
        customTreeUri: Uri? = null,
        trimFilenames: Boolean = false,
    ): Uri? {
        val isAudio = kind == FormatKind.AUDIO
        val isImage = kind == FormatKind.IMAGE
        val ext = container.lowercase(Locale.ROOT)
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        // Carousel slides all carry the post caption as their title and finish
        // exporting within the same second. A title+timestamp name therefore made
        // every slide insert the SAME DISPLAY_NAME + RELATIVE_PATH; MediaProvider
        // maps those concurrent inserts onto one row ("Identical paths … not
        // moving"), so each copy silently overwrote the previous slide and
        // "Download all" published fewer files than items. The slot index in the
        // name gives every slide its own row. [buildExportFileName] keeps the slot
        // and timestamp intact even when the title is trimmed.
        val fileName = buildExportFileName(
            title = title,
            ext = ext,
            carouselIndex = carouselIndex,
            timestamp = timestamp,
            trimFilenames = trimFilenames,
        )

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

        if (customTreeUri != null) {
            return exportToDocument(file, customTreeUri, mimeType, fileName, onBeforePublish)
        }

        val relativePath = when {
            isImage -> DEFAULT_IMAGE_FOLDER_RELATIVE
            isAudio -> DEFAULT_AUDIO_FOLDER_RELATIVE
            else -> DEFAULT_VIDEO_FOLDER_RELATIVE
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                // Pre-Q MediaProvider ignores RELATIVE_PATH and DISPLAY_NAME alone
                // does not choose a directory, so the row must carry an explicit
                // DATA path under the public Downloads directory. The settings
                // constants are storage-root relative ("Download/SocialDL/…").
                val publicDir = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                val subDir = relativePath.substringAfter("${Environment.DIRECTORY_DOWNLOADS}/")
                val targetDir = File(publicDir, subDir).apply { mkdirs() }
                @Suppress("DEPRECATION")
                put(MediaStore.MediaColumns.DATA, File(targetDir, fileName).absolutePath)
            }
        }

        // Every default destination lives under Download/ now. Images/Audio/Video
        // collections reject a Download/ RELATIVE_PATH on Android 10+, so Q+ rows
        // must go through the Downloads collection; pre-Q keeps the kind collection
        // because DATA chooses the directory there.
        val collection = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            isImage -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            isAudio -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        Logger.d(tag, "Exporting $fileName as $mimeType into $collection")

        val itemUri = try {
            store.insert(collection, contentValues) ?: return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A missing storage permission or a full volume is typed, actionable
            // failure — never swallow it into a generic "ExportFailed".
            if (e is SecurityException || DownloadError.from(e) == DownloadError.StorageFull) {
                Logger.e(tag, "MediaStore insert failed for a typed cause ($fileName)", e)
                throw e
            }
            // Other provider-side rejections (MIME validation, missing volume) are
            // reported as a normal export failure.
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

        return copyAndPublish(
            file = file,
            targetUri = itemUri,
            targetLabel = "MediaStore row",
            onBeforePublish = onBeforePublish,
            deleteCreated = { deleteRow(itemUri) },
            onPublish = {
                if (pending) {
                    contentValues.clear()
                    contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    val updated = store.update(itemUri, contentValues, null, null)
                    if (updated == 0) {
                        // The row vanished while copying (provider cleanup / user action).
                        Logger.w(tag, "IS_PENDING=0 update affected no rows for $itemUri")
                        return@copyAndPublish false
                    }
                }
                true
            },
        )
    }

    /**
     * SAF variant of [export]: creates a document inside [treeUri], copies the
     * bytes and asks the worker to publish. SAF has no pending row and no publish
     * step, so [onPendingRowCreated] is deliberately not called. Every failure
     * path deletes exactly the document this export created; cancellation and
     * typed storage/permission failures are rethrown, everything else is a normal
     * export failure.
     */
    private suspend fun exportToDocument(
        file: File,
        treeUri: Uri,
        mimeType: String,
        fileName: String,
        onBeforePublish: suspend () -> Boolean,
    ): Uri? {
        val documentUri = try {
            store.createDocument(treeUri, mimeType, fileName) ?: return null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is SecurityException || DownloadError.from(e) == DownloadError.StorageFull) {
                Logger.e(tag, "SAF document creation failed for a typed cause ($fileName)", e)
                throw e
            }
            Logger.e(tag, "SAF rejected the export document ($fileName, $mimeType)", e)
            return null
        }

        Logger.d(tag, "Exporting $fileName as $mimeType into SAF tree $treeUri")

        return copyAndPublish(
            file = file,
            targetUri = documentUri,
            targetLabel = "SAF document",
            onBeforePublish = onBeforePublish,
            deleteCreated = { deleteDocument(documentUri) },
        )
    }

    /**
     * Shared copy -> pre-publish guard -> optional publish -> error/cancel cleanup
     * pipeline used by both MediaStore rows and SAF documents.
     */
    private suspend fun copyAndPublish(
        file: File,
        targetUri: Uri,
        targetLabel: String,
        onBeforePublish: suspend () -> Boolean,
        deleteCreated: () -> Unit,
        onPublish: () -> Boolean = { true },
    ): Uri? = try {
        val output = store.openOutputStream(targetUri)
        if (output == null) {
            // openOutputStream returned null (no stream available) — same
            // failure class as a failed copy: delete what we created so no
            // zero-byte ghost is left behind.
            Logger.e(tag, "openOutputStream returned null for $targetUri; deleting $targetLabel")
            deleteCreated()
            null
        } else {
            output.use { out -> file.inputStream().use { input -> copyStreamCancellable(input, out) } }

            // Copy finished; publish only if the worker confirms the job is still
            // alive so a cancel during the copy never leaves a visible file behind.
            if (!onBeforePublish()) {
                Logger.i(tag, "Export abandoned before publish; deleting $targetLabel $targetUri")
                deleteCreated()
                null
            } else if (!onPublish()) {
                null
            } else {
                targetUri
            }
        }
    } catch (e: CancellationException) {
        // Cancellation may land inside the copy loop or in onBeforePublish. The
        // target this export created must not stay behind; delete it with blocking
        // resolver calls (no suspension point) and let the worker observe the
        // cancellation instead of a failure.
        Logger.i(tag, "Export cancelled; deleting $targetLabel $targetUri")
        deleteCreated()
        throw e
    } catch (e: Exception) {
        Logger.e(tag, "Failed to copy file to $targetLabel: $targetUri", e)
        // finally-guarded: never leave a dangling target behind a failed copy.
        deleteCreated()
        // A permission denial or a full disk is typed (StoragePermission /
        // StorageFull); only generic I/O failures become ExportFailed.
        if (e is SecurityException || DownloadError.from(e) == DownloadError.StorageFull) {
            throw e
        }
        null
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

    /** Deletes exactly the SAF document this export created. */
    private fun deleteDocument(uri: Uri) {
        runCatching { store.deleteDocument(uri) }
            .onFailure { e -> Logger.w(tag, "Failed to delete SAF document $uri", e) }
    }
}

/** Maximum total file-name length when filename trimming is enabled. */
internal const val MAX_TRIMMED_FILENAME_LENGTH: Int = 40

/**
 * Builds the DISPLAY_NAME/document name for an export.
 *
 * Sanitization is unchanged from the MediaStore implementation: non-ASCII runs
 * collapse to `_`, leading/trailing separators are trimmed so a title can never
 * produce a dotfile, and titles with non-ASCII characters (or no usable ASCII at
 * all) get a stable hash so same-second exports cannot collide.
 *
 * With [trimFilenames], names longer than [MAX_TRIMMED_FILENAME_LENGTH] keep the
 * complete `_<carousel slot>_<timestamp>.<ext>` suffix and truncate only the title,
 * so the extension, timestamp and slot uniqueness survive trimming.
 */
internal fun buildExportFileName(
    title: String,
    ext: String,
    carouselIndex: Int?,
    timestamp: String,
    trimFilenames: Boolean,
): String {
    val sanitizedTitle = sanitizeExportTitle(title)
    val itemSuffix = carouselIndex?.takeIf { it > 0 }?.let { "_$it" }.orEmpty()
    val suffix = "${itemSuffix}_$timestamp.$ext"
    val fileName = "$sanitizedTitle$suffix"
    if (!trimFilenames || fileName.length <= MAX_TRIMMED_FILENAME_LENGTH) return fileName

    val maxTitleLength = (MAX_TRIMMED_FILENAME_LENGTH - suffix.length).coerceAtLeast(1)
    val trimmedTitle = sanitizedTitle
        .take(maxTitleLength)
        .trimEnd('_', '.', '-')
        .ifEmpty { "m" }
    return "$trimmedTitle$suffix"
}

private fun sanitizeExportTitle(title: String): String {
    val sanitizedBase = title
        .replace(Regex("[^a-zA-Z0-9.-]"), "_")
        // Collapse runs of separators and trim every leading/trailing one, so
        // a title like "..hidden" can never produce a dotfile name.
        .replace(Regex("[_.]+"), "_")
        .trim('_', '.', '-')
    return when {
        // No usable ASCII base (e.g. "日本語の動画", "🔥🔥", "..."): a stable
        // hash keeps the name unique instead of inserting "_.mp4".
        sanitizedBase.isEmpty() -> "media_${shortTitleHash(title)}"
        // Distinct non-ASCII titles can sanitize to the same few ASCII
        // leftovers and collide within the same second; the hash keeps them
        // apart. 41 + 1 + 8 = 50 characters.
        title.any { it.code > 127 } -> "${sanitizedBase.take(41)}_${shortTitleHash(title)}"
        else -> sanitizedBase.take(50)
    }
}

/** Stable 8-hex-char digest of the raw title, used to disambiguate names. */
private fun shortTitleHash(title: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(title.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }.take(8)
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

    /**
     * Creates a document named [displayName] inside the SAF tree [treeUri].
     * Returns null when the provider refuses to create it.
     */
    fun createDocument(treeUri: Uri, mimeType: String, displayName: String): Uri?

    /** Deletes a SAF document created by [createDocument]. */
    fun deleteDocument(uri: Uri): Boolean
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

    override fun createDocument(treeUri: Uri, mimeType: String, displayName: String): Uri? {
        val parentDocumentUri = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri),
        )
        return DocumentsContract.createDocument(resolver, parentDocumentUri, mimeType, displayName)
    }

    override fun deleteDocument(uri: Uri): Boolean =
        DocumentsContract.deleteDocument(resolver, uri)
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
