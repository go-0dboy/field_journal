package ru.fieldjournal

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

class AttachmentProvider : ContentProvider() {
    override fun onCreate() = true

    private fun file(uri: Uri): File {
        val id = uri.pathSegments.singleOrNull() ?: error("Invalid file")
        require(attachmentId.matches(id))
        return File(requireNotNull(context).filesDir, "attachments/$id")
    }

    override fun getType(uri: Uri) = uri.getQueryParameter("mime") ?: "application/octet-stream"

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r" || uri.getQueryParameter("capture") == "1")
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.parseMode(mode))
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val file = file(uri)
        return MatrixCursor(columns).apply {
            addRow(columns.map {
                when (it) {
                    OpenableColumns.DISPLAY_NAME -> uri.getQueryParameter("name") ?: file.name
                    OpenableColumns.SIZE -> file.length()
                    else -> null
                }
            })
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
}

internal fun attachmentUri(context: Context, attachment: Attachment, capture: Boolean = false): Uri =
    Uri.Builder().scheme("content").authority("${context.packageName}.attachments").appendPath(attachment.id)
        .appendQueryParameter("name", attachment.name).appendQueryParameter("mime", attachment.mime).apply {
            if (capture) appendQueryParameter("capture", "1")
        }.build()
