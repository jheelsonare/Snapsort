package com.snapsort.app.data

import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snapsort.app.domain.PendingTrashOperation
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrashJournalTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun newStoreRestoresCheckpointAndBatchProgressWithoutLaunch() {
        val name = "journal-test-${System.nanoTime()}"
        try {
            val operation = PendingTrashOperation.reviewed((1..2001).map { "content://media/external_primary/images/media/$it" })
            TrashOperationStore(context, name).write(operation)
            val reopened = TrashOperationStore(context, name).read()!!
            assertEquals(operation, reopened)
            val betweenBatches = reopened.reconcile(operation.inFlight.toSet(), confirmed = true)!!
            TrashOperationStore(context, name).write(betweenBatches)
            assertEquals(1, TrashOperationStore(context, name).read()!!.confirmedBatches)
            assertTrue(TrashOperationStore(context, name).read()!!.interrupted().inFlight.isEmpty())
            TrashOperationStore(context, name).write(null)
            assertNull(TrashOperationStore(context, name).read())
        } finally { context.deleteSharedPreferences(name) }
    }

    @Test fun actualMediaStoreTrashAndRestoreAreObservedWithoutAssumingMissingIsDeleted() {
        val resolver = context.contentResolver
        val original = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Screenshot_Journal_${System.nanoTime()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Screenshots/")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }))
        val pixels = Bitmap.createBitmap(16, 24, Bitmap.Config.ARGB_8888)
        resolver.openOutputStream(original)!!.use { pixels.compress(Bitmap.CompressFormat.PNG, 100, it) }
        pixels.recycle()
        resolver.update(original, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        val uri = resolver.query(original, arrayOf(MediaStore.Images.Media.VOLUME_NAME, MediaStore.Images.Media._ID), null, null, null)!!.use { c ->
            check(c.moveToFirst()); ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(c.getString(0)), c.getLong(1))
        }
        val journal = TrashOperationStore(context, "journal-media-test")
        val missing = "content://media/external_primary/images/media/9223372036854775807"
        try {
            assertEquals(setOf(uri.toString()), journal.observe(listOf(uri.toString(), missing)).present)
            assertEquals(setOf(missing), journal.observe(listOf(missing)).unavailable)
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_TRASHED, 1) }, null, null)
            assertEquals(setOf(uri.toString()), journal.observe(listOf(uri.toString())).trashed)
            resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_TRASHED, 0) }, null, null)
            assertEquals(setOf(uri.toString()), journal.observe(listOf(uri.toString())).present)
        } finally { resolver.delete(uri, null, null); context.deleteSharedPreferences("journal-media-test") }
    }
}
