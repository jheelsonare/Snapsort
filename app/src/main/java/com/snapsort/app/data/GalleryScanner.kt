package com.snapsort.app.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.snapsort.app.domain.Screenshot
import java.util.Locale
import java.text.Normalizer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class GalleryScanner(private val context: Context) {
    private val resolver = context.contentResolver
    suspend fun screenshots(): List<StoredImage> {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = buildList {
            add(MediaStore.Images.Media._ID); add(MediaStore.Images.Media.DISPLAY_NAME)
            add(MediaStore.Images.Media.SIZE); add(MediaStore.Images.Media.DATE_ADDED)
            add(MediaStore.Images.Media.DATE_TAKEN); add(MediaStore.Images.Media.DATE_MODIFIED)
            add(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            add(MediaStore.Images.Media.RELATIVE_PATH)
            add(MediaStore.Images.Media.VOLUME_NAME)
            add(MediaStore.Images.Media.GENERATION_MODIFIED)
        }.toTypedArray()
        val selection = "${MediaStore.Images.Media.IS_PENDING} = 0 AND ${MediaStore.Images.Media.IS_TRASHED} = 0"
        return resolver.query(collection, projection, selection, null, "${MediaStore.Images.Media.DATE_ADDED} DESC")
            ?.use { cursor -> buildList {
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val name = cursor.string(MediaStore.Images.Media.DISPLAY_NAME).orEmpty()
                    val path = cursor.string(MediaStore.Images.Media.RELATIVE_PATH)
                    if (!isScreenshot(name, path, cursor.string(MediaStore.Images.Media.BUCKET_DISPLAY_NAME))) continue
                    val volumeCollection = MediaStore.Images.Media.getContentUri(
                        cursor.string(MediaStore.Images.Media.VOLUME_NAME) ?: MediaStore.VOLUME_EXTERNAL,
                    )
                    val uri = ContentUris.withAppendedId(volumeCollection, cursor.long(MediaStore.Images.Media._ID))
                    val added = cursor.long(MediaStore.Images.Media.DATE_ADDED) * 1000
                    add(StoredImage(
                        Screenshot(uri.toString(), name, cursor.long(MediaStore.Images.Media.DATE_TAKEN).takeIf { it > 0 } ?: added,
                            cursor.long(MediaStore.Images.Media.SIZE).coerceAtLeast(0), canDelete = true),
                        sourceStamp = cursor.long(MediaStore.Images.Media.GENERATION_MODIFIED),
                    ))
                }
            } } ?: emptyList()
    }

    /** Walk a user-granted tree without copying bytes. Cancellation is checked between rows. */
    suspend fun folderImages(tree: Uri, onImage: suspend (StoredImage) -> Unit) {
        require(DocumentsContract.isTreeUri(tree)) { "Choose a folder through Android's folder picker" }
        val pending = ArrayDeque<String>()
        val visited = hashSetOf<String>()
        pending.add(DocumentsContract.getTreeDocumentId(tree))
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val parent = pending.removeFirst()
            if (!visited.add(parent)) continue
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
            val documents: List<Pair<String, String?>> = resolver.query(children, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE,
            ), null, null, null)?.use { c ->
                buildList<Pair<String, String?>> {
                    while (c.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        add(c.getString(0) to c.getString(1))
                    }
                }
            } ?: throw java.io.IOException("The selected folder is unavailable")
            for ((id, mime) in documents) {
                currentCoroutineContext().ensureActive()
                if (mime == DocumentsContract.Document.MIME_TYPE_DIR) pending.add(id)
                else if (mime?.startsWith("image/") == true) {
                    val original = DocumentsContract.buildDocumentUriUsingTree(tree, id)
                    val resolved = resolvePickedUri(original)
                    val row = runCatching { readSingle(resolved) }.getOrNull()
                        ?: if (resolved != original) runCatching { readSingle(original) }.getOrNull() else null
                    if (row != null) onImage(row.copy(image = row.image.copy(manuallyAdded = true)))
                }
            }
        }
    }

    fun resolvePickedUri(uri: Uri): Uri {
        return runCatching { MediaStore.getMediaUri(context, uri) }.getOrNull() ?: uri
    }

    fun readSingle(uri: Uri): StoredImage? {
        // An unrestricted query works for MediaStore and document providers. Their columns differ.
        return resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            if (cursor.long(MediaStore.Images.Media.IS_TRASHED) != 0L) return@use null
            val name = cursor.string(OpenableColumns.DISPLAY_NAME) ?: "Selected image"
            val modified = cursor.long(MediaStore.Images.Media.DATE_MODIFIED).takeIf { it > 0 }
                ?: cursor.long(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            val sourceStamp = if (isDeletableMediaUri(uri))
                cursor.long(MediaStore.Images.Media.GENERATION_MODIFIED) else modified
            val date = cursor.long(MediaStore.Images.Media.DATE_TAKEN).takeIf { it > 0 }
                ?: (cursor.long(MediaStore.Images.Media.DATE_ADDED) * 1000).takeIf { it > 0 }
                ?: modified.takeIf { it > 0 }?.let { if (it < 100_000_000_000) it * 1000 else it }
                ?: 0
            // A persisted document row can outlive its backing file. Verify actual access.
            val canonical = if (isDeletableMediaUri(uri)) {
                cursor.string(MediaStore.Images.Media.VOLUME_NAME)?.let { volume ->
                    val id = cursor.long(MediaStore.Images.Media._ID)
                    if (id > 0) ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(volume), id) else null
                } ?: uri
            } else uri
            // Some document providers grant exactly the selected URI. Preserve that working grant
            // if an equivalent MediaStore volume URI does not carry it on this device.
            val descriptor = runCatching { resolver.openAssetFileDescriptor(canonical, "r") }.getOrNull()
            val readableUri = if (descriptor != null) canonical else uri
            (descriptor ?: resolver.openAssetFileDescriptor(uri, "r"))?.use { readable ->
                val size = cursor.long(OpenableColumns.SIZE).takeIf { it > 0 }
                    ?: readable.length.coerceAtLeast(0)
                StoredImage(Screenshot(readableUri.toString(), name, date, size, manuallyAdded = true, canDelete = isDeletableMediaUri(readableUri)), sourceStamp)
            }
        }
    }

    companion object {
        private val combiningMarks = Regex("\\p{M}+")
        private val separators = Regex("[ _-]+")
        private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKD)
            .replace(combiningMarks, "").lowercase(Locale.ROOT).replace(separators, "")
        private val screenshotMarkers = listOf("screenshot", "screencapture", "screencap", "截屏", "截图", "螢幕截圖", "屏幕快照",
            "スクリーンショット", "스크린샷", "capturadepantalla", "capturadeecr", "bildschirmfoto", "bildschirmaufnahme",
            "captured’ecran", "capturedecran", "captured'ecran").map(::normalize)

        fun isScreenshot(name: String, path: String?, bucket: String?): Boolean {
            val combined = normalize(listOfNotNull(name, path, bucket).joinToString(" "))
            return screenshotMarkers.any { combined.contains(it) }
        }

        fun isDeletableMediaUri(uri: Uri): Boolean {
            val segments = uri.pathSegments
            return uri.scheme == ContentResolver.SCHEME_CONTENT && uri.authority == MediaStore.AUTHORITY &&
                segments.size == 4 && segments[1] == "images" && segments[2] == "media" &&
                segments.last().toLongOrNull() != null
        }
    }
}

private fun Cursor.string(column: String): String? = getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getString)
private fun Cursor.long(column: String): Long = getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let(::getLong) ?: 0
