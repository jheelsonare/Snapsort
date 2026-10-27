package com.snapsort.app.data

import android.net.Uri

/** Separate operations let a successful file hash survive an unrelated OCR failure. */
interface ImageOperations {
    suspend fun hash(uri: Uri): String
    suspend fun text(uri: Uri): String
    fun close()
}
