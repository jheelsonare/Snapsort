package com.snapsort.app.data

import android.content.ContentValues
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snapsort.app.domain.Category
import com.snapsort.app.domain.GalleryAccess
import com.snapsort.app.domain.Screenshot
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GalleryDataTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val resolver = context.contentResolver
    private val created = mutableListOf<Uri>()
    private val databaseName = "snapsort-data-test.db"

    @After fun cleanup() {
        created.forEach { runCatching { resolver.delete(it, null, null) } }
        context.deleteDatabase(databaseName)
    }

    @Test fun metadataSurvivesReopenWithoutCopyingTheOriginal() {
        val row = StoredImage(Screenshot(
            "content://media/external/images/media/98222", "Screenshot.png", 123456L, 1000L,
            category = Category.STUDY, categoryCorrected = true, text = "Lecture notes",
            keep = true, hash = "abc", indexed = true, manuallyAdded = true,
        ), sourceStamp = 12L, excluded = true)
        MetadataStore(context, databaseName).use { it.put(row) }
        MetadataStore(context, databaseName).use { assertEquals(row, it.get(row.image.uri)) }
    }

    @Test fun detectsFolderAndLocalizedNamingVariantsAndRejectsCameraPhotos() {
        assertTrue(GalleryScanner.isScreenshot("IMG_003.png", "Pictures/Screenshots/", "Screenshots"))
        assertTrue(GalleryScanner.isScreenshot("Screen_Shot_2026-01-01.png", "DCIM/", null))
        assertTrue(GalleryScanner.isScreenshot("Bildschirmfoto 2026.png", null, null))
        assertTrue(GalleryScanner.isScreenshot("Captura de pantalla.png", null, null))
        assertTrue(GalleryScanner.isScreenshot("Capture d’écran.png", null, null))
        assertTrue(GalleryScanner.isScreenshot("截图_123.png", null, null))
        assertTrue(GalleryScanner.isScreenshot("스크린샷_123.png", null, null))
        assertTrue(GalleryScanner.isScreenshot("スクリーンショット_123.png", null, null))
        assertFalse(GalleryScanner.isScreenshot("IMG_20261022.jpg", "DCIM/Camera/", "Camera"))
    }

    @Test fun onlyOriginalMediaRowsAreEligibleForSystemTrash() {
        assertTrue(GalleryScanner.isDeletableMediaUri(Uri.parse("content://media/external_primary/images/media/1")))
        assertFalse(GalleryScanner.isDeletableMediaUri(Uri.parse("content://media/picker/0/com.android.providers.media.photopicker/media/1")))
        assertFalse(GalleryScanner.isDeletableMediaUri(Uri.parse("content://com.android.providers.media.documents/document/image%3A1")))
        assertFalse(GalleryScanner.isDeletableMediaUri(Uri.parse("file:///sdcard/Pictures/Screenshot.png")))
    }

    @Test fun originalBytesProduceStableHashAndBundledOcr() = runBlocking {
        val bytes = receiptPng()
        val uri = insertImage("Screenshot_receipt.png", bytes)
        val indexer = ImageIndexer(resolver)
        try {
            val expected = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            assertEquals(expected, indexer.hash(uri))
            val text = withTimeout(30_000) { indexer.text(uri) }
            assertTrue("Expected receipt text, received: $text", text.contains("RECEIPT", ignoreCase = true))
            assertTrue(text.contains("42.00"))
        } finally { indexer.close() }
    }

    @Test fun refreshPreservesCorrectionsAndKeepThenRemovesExternallyDeletedImages() = runBlocking {
        val uri = insertImage("Screenshot_external.png", receiptPng())
        val repository = GalleryRepository(context, databaseName)
        try {
            repository.refresh(GalleryAccess(canReadLibrary = true))
            assertTrue(repository.state.value.items.any { it.uri == uri.toString() })
            withTimeout(30_000) { while (repository.state.value.processing) delay(50) }
            assertTrue(repository.state.value.items.first { it.uri == uri.toString() }.indexed)
            repository.setCategory(setOf(uri.toString()), Category.STUDY)
            repository.setKeep(setOf(uri.toString()), true)
            repository.refresh(GalleryAccess(canReadLibrary = true))
            val item = repository.state.value.items.first { it.uri == uri.toString() }
            assertEquals(Category.STUDY, item.category)
            assertTrue(item.categoryCorrected)
            assertTrue(item.keep)
            // This test created and owns the fixture. Production cleanup uses the system flow.
            assertEquals(1, resolver.delete(uri, null, null))
            repository.refresh(GalleryAccess(canReadLibrary = true))
            assertFalse(repository.state.value.items.any { it.uri == uri.toString() })
        } finally { repository.close() }
    }

    @Test fun manuallyAddedImagesCanBeRemovedFromCollectionWithoutDeletingTheOriginal() = runBlocking {
        val uri = insertImage("personal_note.png", receiptPng(), "Pictures/SnapSortDataTest/")
        val repository = GalleryRepository(context, databaseName)
        try {
            repository.addImages(listOf(uri), GalleryAccess())
            assertTrue(repository.state.value.items.any { it.uri == uri.toString() && it.manuallyAdded })
            repository.removeFromCollection(setOf(uri.toString()))
            assertFalse(repository.state.value.items.any { it.uri == uri.toString() })
            resolver.openInputStream(uri).use { assertNotNull(it) }
            repository.refresh(GalleryAccess())
            assertFalse(repository.state.value.items.any { it.uri == uri.toString() })
        } finally { repository.close() }
    }

    @Test fun mediaAliasesDoNotCreateTwoEntriesForOneOriginal() = runBlocking {
        val canonical = insertImage("Screenshot_alias.png", receiptPng())
        val alias = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentUris.parseId(canonical))
        val repository = GalleryRepository(context, databaseName)
        try {
            repository.addImages(listOf(alias), GalleryAccess(canReadLibrary = true))
            val matches = repository.state.value.items.filter { it.name == "Screenshot_alias.png" }
            assertEquals(1, matches.size)
            assertEquals(canonical.toString(), matches.single().uri)
            assertTrue(matches.single().manuallyAdded)
        } finally { repository.close() }
    }

    private fun insertImage(name: String, bytes: ByteArray, path: String = "Pictures/SnapSortDataTest_Screenshots/"): Uri {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = requireNotNull(resolver.insert(collection, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, path)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }))
        created += uri
        resolver.openOutputStream(uri).use { requireNotNull(it).write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    private fun receiptPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 85f }
        canvas.drawText("RECEIPT", 80f, 180f, paint)
        canvas.drawText("TOTAL PAID 42.00", 80f, 330f, paint)
        canvas.drawText("Thank you", 80f, 480f, paint)
        return ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
    }
}
