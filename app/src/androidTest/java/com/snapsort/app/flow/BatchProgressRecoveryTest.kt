package com.snapsort.app.flow

import android.Manifest
import android.app.Application
import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.snapsort.app.SnapSortViewModel
import com.snapsort.app.data.TrashOperationStore
import com.snapsort.app.domain.GalleryAccess
import com.snapsort.app.domain.PendingTrashOperation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Provider writes only affect these app-owned fixtures; this does not fake system consent. */
@RunWith(AndroidJUnit4::class)
class BatchProgressRecoveryTest {
    @get:Rule val permission: GrantPermissionRule = GrantPermissionRule.grant(
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE,
    )

    @Test fun confirmedBatchSurvivesRecreationAndRenewedReviewRetainsProgress() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val resolver = app.contentResolver
        val journal = TrashOperationStore(app)
        val previous = journal.read()
        val fixtures = mutableListOf<Uri>()
        var models = ViewModelStore()
        lateinit var model: SnapSortViewModel
        fun reopen() {
            instrumentation.runOnMainSync {
                model = ViewModelProvider(models, ViewModelProvider.AndroidViewModelFactory(app))[SnapSortViewModel::class.java]
                model.refresh(GalleryAccess(canReadLibrary = true))
            }
        }
        try {
            repeat(2) { index ->
                val inserted = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "Screenshot_BatchProgress_${System.nanoTime()}_$index.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Screenshots/")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }))
                // Record the aggregate URI first, so a fixture is cleaned up even if writing fails.
                fixtures += inserted
                val pixels = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
                try { resolver.openOutputStream(inserted)!!.use { pixels.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { pixels.recycle() }
                resolver.update(inserted, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
                fixtures[fixtures.lastIndex] = resolver.query(inserted,
                    arrayOf(MediaStore.Images.Media.VOLUME_NAME, MediaStore.Images.Media._ID), null, null, null)!!.use { c ->
                    check(c.moveToFirst())
                    ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(c.getString(0)), c.getLong(1))
                }
            }
            val targets = fixtures.map(Uri::toString)
            // A compact checkpoint stands in for a large operation's one-image final batches.
            journal.write(PendingTrashOperation(targets, listOf(targets.first())))
            reopen()
            await { model.state.value.cleanupRecoveryPending && model.state.value.selected.containsAll(targets) }
            assertTrue("Recovered checkpoints must not dispatch Android requests", journal.read()!!.inFlight.isEmpty())

            // Simulate the original gallery's completed first batch, then deliver its result.
            assertEquals(1, resolver.update(fixtures.first(), ContentValues().apply {
                put(MediaStore.Images.Media.IS_TRASHED, 1)
            }, null, null))
            model.trashResult(confirmed = true)
            await { !model.state.value.busyDeleting && model.state.value.selected == setOf(targets.last()) }
            val between = journal.read()!!
            assertEquals(1, between.confirmedBatches)
            assertEquals(listOf(targets.last()), between.targets)
            assertTrue(between.reviewRequired)
            assertTrue(between.inFlight.isEmpty())

            instrumentation.runOnMainSync { models.clear() }
            models = ViewModelStore()
            reopen()
            await { model.state.value.cleanupRecoveryPending && model.state.value.selected == setOf(targets.last()) }
            assertEquals(1, journal.read()!!.confirmedBatches)
            assertTrue("Recreation cannot authorize the next batch", journal.read()!!.inFlight.isEmpty())

            // Explicitly reviewing the remaining selection permits a request without resetting progress.
            assertEquals(listOf(targets.last()), model.beginTrash())
            val next = journal.read()!!
            assertEquals(1, next.confirmedBatches)
            assertFalse(next.reviewRequired)
            assertEquals(listOf(targets.last()), next.inFlight)
            model.trashResult(confirmed = false)
            assertNull(journal.read())
        } finally {
            instrumentation.runOnMainSync { models.clear() }
            journal.write(previous)
            fixtures.forEach { resolver.delete(it, null, null) }
        }
    }

    private suspend fun await(predicate: () -> Boolean) {
        withTimeout(90_000) { while (!predicate()) delay(150) }
    }
}
