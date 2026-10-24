package com.snapsort.app.data

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine

internal class ImageIndexer(private val resolver: ContentResolver) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun hash(uri: Uri): String {
        val digest = MessageDigest.getInstance("SHA-256")
        resolver.openInputStream(uri)?.buffered()?.use { stream ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val read = stream.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        } ?: throw IOException("The original image is unavailable")
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    suspend fun text(uri: Uri): String {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Unsupported image format")
        // Long scrolling screenshots must not become illegible by shrinking the whole image.
        // Decode bounded strips at readable resolution instead of allocating the full bitmap.
        val orientation = orientation(uri)
        if (bounds.outHeight > 4096 && orientation in listOf(null, ExifInterface.ORIENTATION_UNDEFINED, ExifInterface.ORIENTATION_NORMAL)) {
            return tiledText(uri, bounds.outWidth, bounds.outHeight)
        }
        return recognize(decode(uri, bounds, orientation))
    }

    private suspend fun recognize(bitmap: Bitmap): String {
        // This artifact bundles the Latin recognizer; it does not download a model.
        return suspendCancellableCoroutine { continuation ->
            try {
                recognizer.process(InputImage.fromBitmap(bitmap, 0)).addOnCompleteListener { task ->
                    // ML Kit may still use the pixels after our coroutine is cancelled.
                    bitmap.recycle()
                    if (continuation.isActive) {
                        if (task.isSuccessful) continuation.resume(task.result.text)
                        else continuation.resumeWithException(task.exception ?: IOException("Text recognition did not complete"))
                    }
                }
            } catch (error: Exception) {
                bitmap.recycle()
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun tiledText(uri: Uri, width: Int, height: Int): String {
        return resolver.openInputStream(uri)?.use { stream ->
            val decoder = BitmapRegionDecoder.newInstance(stream, false) ?: throw IOException("Unsupported image format")
            try {
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                    inSampleSize = 1
                    while (width / inSampleSize > 2048) inSampleSize *= 2
                }
                val lines = linkedSetOf<String>()
                var top = 0
                while (top < height) {
                    currentCoroutineContext().ensureActive()
                    val bottom = minOf(top + 2560, height)
                    val bitmap = decoder.decodeRegion(Rect(0, top, width, bottom), options)
                        ?: throw IOException("Part of the screenshot could not be decoded")
                    lines.addAll(recognize(bitmap).lines().filter { it.isNotBlank() })
                    if (bottom == height) break
                    top = bottom - 96 // overlap protects a line crossing a strip boundary
                }
                lines.joinToString("\n")
            } finally { decoder.recycle() }
        } ?: throw IOException("The original image is unavailable")
    }

    private fun orientation(uri: Uri): Int? = runCatching {
        resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
    }.getOrNull()

    private fun decode(uri: Uri, bounds: BitmapFactory.Options, orientation: Int?): Bitmap {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inSampleSize = 1
            while (bounds.outWidth / inSampleSize > 2560 || bounds.outHeight / inSampleSize > 2560) inSampleSize *= 2
        }
        val decoded = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("The image could not be decoded")
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> { matrix.setRotate(180f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
        }
        if (matrix.isIdentity) return decoded
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
            if (it !== decoded) decoded.recycle()
        }
    }

    fun close() = recognizer.close()
}
