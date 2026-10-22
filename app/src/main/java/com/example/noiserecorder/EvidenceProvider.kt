package com.example.noiserecorder

import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/** Only explicit read grants from our playback intents expose individual WAV files. */
class EvidenceProvider : ContentProvider() {
    override fun onCreate() = true
    private fun file(uri: Uri): File {
        val root = File(requireNotNull(context).filesDir, "recordings").canonicalFile
        val name = requireNotNull(uri.lastPathSegment)
        val file = File(root, name).canonicalFile
        require(file.parentFile == root && name.endsWith(".wav") && file.isFile)
        return file
    }
    override fun getType(uri: Uri) = "audio/wav"
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor { require(mode == "r"); return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY) }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = file(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply { addRow(columns.map { when(it) { OpenableColumns.DISPLAY_NAME -> f.name; OpenableColumns.SIZE -> f.length(); else -> null } }) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
