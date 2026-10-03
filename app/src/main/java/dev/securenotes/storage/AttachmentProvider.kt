package dev.securenotes.storage

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import dev.securenotes.NotesApplication
import dev.securenotes.document.validId
import kotlinx.coroutines.runBlocking

class AttachmentProvider : ContentProvider() {
    private val app get() = context!!.applicationContext as NotesApplication
    override fun onCreate() = true
    private fun id(uri: Uri): String = uri.lastPathSegment.orEmpty().also { require(validId(it)); app.checkAccess() }
    override fun getType(uri: Uri): String? = runBlocking { app.repository.access { it.dao.attachment(id(uri))?.mime } }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor = runBlocking {
        app.repository.access { v ->
            val a = v.dao.attachment(id(uri)) ?: throw java.io.FileNotFoundException()
            val columns = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }?.toTypedArray()
                ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
            MatrixCursor(columns).apply { addRow(columns.map { if (it == OpenableColumns.SIZE) a.size else a.filename }) }
        }
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = runBlocking {
        require(mode == "r")
        require(uri in app.grants) { "Grant expired" }
        app.repository.access { v -> val a = v.dao.attachment(id(uri)) ?: throw java.io.FileNotFoundException(); v.descriptor(a.id, a.size) { check(uri in app.grants) } }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
}
