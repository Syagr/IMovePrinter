package ua.com.sdegroup.imoveprinter.util

import android.content.Context
import android.net.Uri

fun getFileNameFromUri(context: Context, uri: Uri): String {
  return try {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
      val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
      if (cursor.moveToFirst() && nameIndex >= 0) {
        cursor.getString(nameIndex)
      } else {
        uri.lastPathSegment ?: "file.pdf"
      }
    } ?: uri.lastPathSegment ?: "file.pdf"
  } catch (e: Exception) {
    uri.lastPathSegment ?: "file.pdf"
  }
}
