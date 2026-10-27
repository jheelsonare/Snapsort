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
import com.snapsort.app.ui.AppAction
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecoverySelectionRaceTest {
    @get:Rule val permission: GrantPermissionRule = GrantPermissionRule.grant(
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE,
    )

    @Test fun explicitNewSelectionSurvivesReconciliationOfUnavailableOldTargets() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as Application
        val resolver = app.contentResolver
        val journal = TrashOperationStore(app)
        val previous = journal.read()
        val models = ViewModelStore()
        var fixture: Uri? = null
        lateinit var model: SnapSortViewModel
        try {
            val inserted = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "Screenshot_RecoverySelection_${System.nanoTime()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Screenshots/")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }))
            fixture = inserted
            val pixels = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888)
            try { resolver.openOutputStream(inserted)!!.use { pixels.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { pixels.recycle() }
            resolver.update(inserted, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            val canonical = resolver.query(inserted, arrayOf(MediaStore.Images.Media.VOLUME_NAME, MediaStore.Images.Media._ID),
                null, null, null)!!.use { c ->
                check(c.moveToFirst())
                ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(c.getString(0)), c.getLong(1))
            }
            fixture = canonical
            val uri = canonical.toString()
            // An old library can contain many now-inaccessible originals. None is the new fixture.
            // Large IDs avoid creating or modifying gallery rows to exercise the recovery workload.
            val oldTargets = (0 until 10_000).map { "content://media/external_primary/images/media/${Long.MAX_VALUE - it}" }
            journal.write(PendingTrashOperation.reviewed(oldTargets))
            instrumentation.runOnMainSync {
                model = ViewModelProvider(models, ViewModelProvider.AndroidViewModelFactory(app))[SnapSortViewModel::class.java]
                model.refresh(GalleryAccess(canReadLibrary = true))
            }
            withTimeout(90_000) {
                model.state.first { it.cleanupRecoveryPending && it.library.items.any { shot -> shot.uri == uri } }
            }
            // The first gallery frame is usable while the old operation is reconciled. This
            // explicit decision must win even when a recovered selection is published later.
            instrumentation.runOnMainSync { model.action(AppAction.ToggleSelection(uri)) }
            val batch = model.beginTrash() // Waits for the journal mutex; does not launch Android.
            assertEquals("Late recovery must not erase an explicit new selection", listOf(uri), batch)
            withTimeout(90_000) { model.state.first { it.selected == setOf(uri) } }
            assertEquals(listOf(uri), journal.read()!!.targets)
            assertEquals(listOf(uri), journal.read()!!.inFlight)
            model.trashResult(confirmed = false)
            assertNull(journal.read())
        } finally {
            instrumentation.runOnMainSync { models.clear() }
            journal.write(previous)
            fixture?.let { resolver.delete(it, null, null) }
        }
    }
}
