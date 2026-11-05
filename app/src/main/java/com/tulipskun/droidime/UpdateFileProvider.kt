package com.tulipskun.droidime

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

// Hands the downloaded update APK to the package installer. Serves exactly one file from the
// app's cache and nothing else, so no androidx FileProvider dependency is needed.
class UpdateFileProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    private fun file(uri: Uri): File {
        if (uri.lastPathSegment != FILE) throw FileNotFoundException(uri.toString())
        val file = File(File(context!!.cacheDir, DIR), FILE)
        if (!file.exists()) throw FileNotFoundException(uri.toString())
        return file
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY)

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor {
        val f = file(uri)
        val cursor = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
        cursor.addRow(arrayOf<Any>(FILE, f.length()))
        return cursor
    }

    override fun getType(uri: Uri): String = "application/vnd.android.package-archive"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val DIR = "updates"
        const val FILE = "DroidIME-update.apk"
    }
}
