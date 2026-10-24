package com.snapsort.app.flow

import android.Manifest
import android.content.ContentValues
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import androidx.lifecycle.ViewModelProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.snapsort.app.MainActivity
import com.snapsort.app.SnapSortViewModel
import com.snapsort.app.data.GalleryRepository
import com.snapsort.app.domain.Category
import com.snapsort.app.domain.GalleryAccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** Real local media originals; every test cleans up only the URIs it created. */
@RunWith(AndroidJUnit4::class)
@LargeTest
class GalleryFlowInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule
    val mediaPermission: GrantPermissionRule = GrantPermissionRule.grant(
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE,
    )

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val resolver = context.contentResolver
    private val originals = mutableListOf<Uri>()
    private val access = GalleryAccess(canReadLibrary = true)
    private lateinit var repository: GalleryRepository
    private val runId = System.nanoTime().toString()

    @Before fun openRepository() { repository = GalleryRepository(context) }

    @After fun cleanup() {
        repository.close()
        originals.forEach { uri -> runCatching { resolver.delete(uri, null, null) } }
    }

    @Test fun indexesOriginalBytesAndOcrAndKeepsCategoryCorrections() = runBlocking {
        val png = screenshotPng()
        val first = insertImage("Screenshot_${runId}_a.png", png)
        val second = insertImage("screen_capture_${runId}_b.png", png, "DCIM/Screenshots/")
        repository.refresh(access)
        await {
            originals.take(2).all { uri -> repository.state.value.items.any { it.uri == uri.toString() && it.indexed } }
        }
        val indexed = repository.state.value.items.filter { it.uri in setOf(first.toString(), second.toString()) }
        assertEquals(2, indexed.size)
        val expectedHash = MessageDigest.getInstance("SHA-256").digest(png).joinToString("") { "%02x".format(it) }
        assertTrue(indexed.all { it.hash == expectedHash })
        assertTrue("Bundled OCR should recognise fixture words", indexed.all { it.text.contains("ALGEBRA", ignoreCase = true) })
        repository.setCategory(setOf(first.toString()), Category.TICKETS)
        repository.setKeep(setOf(first.toString()), true)
        repository.close()
        repository = GalleryRepository(context)
        repository.refresh(access)
        val corrected = repository.state.value.items.single { it.uri == first.toString() }
        assertEquals(Category.TICKETS, corrected.category)
        assertTrue(corrected.categoryCorrected)
        assertTrue(corrected.keep)
        assertArrayEquals("Indexing must not rewrite originals", png, resolver.openInputStream(first)!!.use { it.readBytes() })
    }

    @Test fun manuallyAddedImageCanBeRemovedFromCollectionWithoutDeletingOriginal() = runBlocking {
        val png = screenshotPng()
        val uri = insertImage("Boarding_note_$runId.png", png, "Pictures/SnapSortValidation/")
        repository.refresh(access)
        assertFalse(repository.state.value.items.any { it.uri == uri.toString() })
        repository.addImages(listOf(uri), access)
        assertTrue(repository.state.value.items.any { it.uri == uri.toString() && it.manuallyAdded })
        repository.removeFromCollection(setOf(uri.toString()))
        repository.refresh(access)
        assertFalse(repository.state.value.items.any { it.uri == uri.toString() })
        assertArrayEquals(png, resolver.openInputStream(uri)!!.use { it.readBytes() })
    }

    @Test fun refreshReflectsGalleryMovesAndExternalDeletion() = runBlocking {
        val uri = insertImage("Folder_only_$runId.png", screenshotPng())
        repository.refresh(access)
        assertTrue(repository.state.value.items.any { it.uri == uri.toString() })
        resolver.update(uri, ContentValues().apply {
            put(MediaStore.Images.Media.RELATIVE_PATH, "DCIM/Screenshots/")
        }, null, null)
        repository.refresh(access)
        assertTrue("Different screenshot folders remain discoverable", repository.state.value.items.any { it.uri == uri.toString() })
        resolver.update(uri, ContentValues().apply {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SnapSortValidation/")
        }, null, null)
        repository.refresh(access)
        assertFalse("A moved non-screenshot is no longer suggested", repository.state.value.items.any { it.uri == uri.toString() })
        repository.addImages(listOf(uri), access)
        assertTrue(repository.state.value.items.any { it.uri == uri.toString() })
        assertEquals(1, resolver.delete(uri, null, null))
        repository.refresh(access)
        assertFalse(repository.state.value.items.any { it.uri == uri.toString() })
    }

    @Test fun cancelledTrashRetainsOriginalAndConfirmedTrashRefreshesCollection() = runBlocking {
        val uri = insertImage("Screenshot_trash_$runId.png", screenshotPng())
        repository.refresh(access)
        assertTrue(repository.state.value.items.any { it.uri == uri.toString() })
        val device = UiDevice.getInstance(instrumentation)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val evidence = File(context.getExternalFilesDir(null), "test-evidence").apply { mkdirs() }
            try {
                lateinit var activeModel: SnapSortViewModel
                scenario.onActivity { activeModel = ViewModelProvider(it)[SnapSortViewModel::class.java] }
                await { activeModel.state.value.library.items.any { it.uri == uri.toString() } }
                compose.waitForIdle()
                device.dumpWindowHierarchy(File(evidence, "trash-$runId-before-selection.xml"))
                val checkboxDescription = "Select Screenshot_trash_$runId.png"
                compose.onNodeWithTag("screenshot-grid")
                    .performScrollToNode(hasContentDescription(checkboxDescription))
                device.dumpWindowHierarchy(File(evidence, "trash-$runId-after-scroll.xml"))
                compose.onNodeWithContentDescription(checkboxDescription).performClick()
                compose.onNodeWithText("1 selected").assertIsDisplayed()
                openAppTrashReview()
                // System UI labels vary by OS; Android's positive/negative dialog IDs are stable.
                val cancel = device.wait(Until.findObject(By.res("android", "button2")), 15_000)
                assertNotNull("Android should show its trash confirmation", cancel)
                cancel!!.click()
                assertTrue(device.wait(Until.gone(By.res("android", "button2")), 10_000))
                await { !isTrashed(uri) }
                repository.refresh(access)
                assertTrue("Cancellation preserves the original", repository.state.value.items.any { it.uri == uri.toString() })
                compose.onNodeWithText("1 selected").assertIsDisplayed()

                openAppTrashReview()
                val confirm = device.wait(Until.findObject(By.res("android", "button1")), 15_000)
                assertNotNull("Second request should show Android confirmation", confirm)
                confirm!!.click()
                await { isTrashed(uri) }
                compose.waitUntil(timeoutMillis = 30_000) {
                    compose.onAllNodesWithText("1 selected").fetchSemanticsNodes().isEmpty()
                }
                repository.refresh(access)
                assertFalse("Confirmed trash must leave the active collection", repository.state.value.items.any { it.uri == uri.toString() })
            } catch (failure: Throwable) {
                runCatching { device.dumpWindowHierarchy(File(evidence, "trash-$runId-failure.xml")) }
                runCatching { device.takeScreenshot(File(evidence, "trash-$runId-failure.png")) }
                throw AssertionError("Trash flow failed; UI evidence is in $evidence", failure)
            }
        }
    }

    private fun openAppTrashReview() {
        compose.onNodeWithText("Review selection").performClick()
        compose.onNodeWithText("One last look").assertIsDisplayed()
        compose.onNodeWithText("Continue to Android · move to trash").performScrollTo().performClick()
    }

    private fun isTrashed(uri: Uri): Boolean {
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }
        return resolver.query(uri, arrayOf(MediaStore.Images.Media.IS_TRASHED), args, null)?.use {
            it.moveToFirst() && it.getInt(0) == 1
        } ?: false
    }

    private fun insertImage(name: String, bytes: ByteArray, path: String = "Pictures/Screenshots/"): Uri {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, path)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        originals += uri
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return resolver.query(uri, arrayOf(MediaStore.Images.Media.VOLUME_NAME, MediaStore.Images.Media._ID), null, null, null)!!.use {
            check(it.moveToFirst())
            ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(it.getString(0)), it.getLong(1))
        }.also { canonical -> originals.remove(uri); originals += canonical }
    }

    private fun screenshotPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(1080, 800, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 72f }
        canvas.drawText("STUDY NOTES", 70f, 160f, paint)
        canvas.drawText("ALGEBRA CHAPTER 4", 70f, 290f, paint)
        canvas.drawText("Equations and functions", 70f, 420f, paint.apply { textSize = 55f })
        return ByteArrayOutputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            bitmap.recycle()
            stream.toByteArray()
        }
    }

    private suspend fun await(predicate: () -> Boolean) {
        withTimeout(90_000) { while (!predicate()) delay(150) }
    }
}
