package com.socialdl.src.core.download

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import java.io.OutputStream

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
