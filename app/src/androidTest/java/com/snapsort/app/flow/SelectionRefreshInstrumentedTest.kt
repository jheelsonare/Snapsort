package com.snapsort.app.flow

import android.Manifest
import android.app.Application
import android.content.ContentUris
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.snapsort.app.SnapSortViewModel
import com.snapsort.app.domain.GalleryAccess
import com.snapsort.app.ui.AppAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SelectionRefreshInstrumentedTest {
    @get:Rule
    val permission: GrantPermissionRule = GrantPermissionRule.grant(
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE,
    )

    @Test fun externalChangesPruneSelectionAndRestoredImagesStayUnselected() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val application = instrumentation.targetContext.applicationContext as Application
        val resolver = application.contentResolver
        val inserted = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Screenshot_observer_${System.nanoTime()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Screenshots/")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }))
        val bitmap = Bitmap.createBitmap(120, 160, Bitmap.Config.ARGB_8888)
        resolver.openOutputStream(inserted)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        resolver.update(inserted, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        val uri = resolver.query(inserted, arrayOf(MediaStore.Images.Media.VOLUME_NAME, MediaStore.Images.Media._ID), null, null, null)!!.use {
            check(it.moveToFirst())
            ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(it.getString(0)), it.getLong(1))
        }
        val store = ViewModelStore()
        lateinit var model: SnapSortViewModel
        try {
            instrumentation.runOnMainSync {
                model = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(application))[SnapSortViewModel::class.java]
                model.refresh(GalleryAccess(canReadLibrary = true))
            }
            await { model.state.value.library.items.any { it.uri == uri.toString() } }
            instrumentation.runOnMainSync { model.action(AppAction.ToggleSelection(uri.toString())) }
            await { uri.toString() in model.state.value.selected }
            assertEquals(listOf(uri.toString()), model.beginTrash())
            assertNull("Repeated confirmation must not enqueue the same originals", model.beginTrash())
            model.trashResult(false)
            await { !model.state.value.busyDeleting }
            // Provider writes simulate changes made by the gallery, bypassing SnapSort's commands.
            assertEquals(1, resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_TRASHED, 1) }, null, null))
            await {
                model.state.value.library.items.none { it.uri == uri.toString() } &&
                    uri.toString() !in model.state.value.selected
            }
            assertEquals(1, resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_TRASHED, 0) }, null, null))
            await { model.state.value.library.items.any { it.uri == uri.toString() } }
            assertFalse("Restoring an original must not silently reselect it", uri.toString() in model.state.value.selected)
        } finally {
            instrumentation.runOnMainSync { store.clear() }
            resolver.delete(uri, null, null)
        }
    }

    private suspend fun await(predicate: () -> Boolean) {
        withTimeout(90_000) { while (!predicate()) delay(150) }
    }
}
