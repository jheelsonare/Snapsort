package com.snapsort.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.exifinterface.media.ExifInterface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.snapsort.app.domain.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ScreenshotViewerTest {
    @get:Rule val compose = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val imageFiles = mutableListOf<File>()
    private val first = Screenshot("content://media/external/images/media/77889900", "Screenshot-one.png", 1000L, 128L, indexed = true)
    private val second = first.copy(uri = "content://media/external/images/media/77889901", name = "Screenshot-two.png")
    private fun state(items: List<Screenshot> = listOf(first, second), selected: Set<String> = emptySet()) = AppUiState(
        access = GalleryAccess(canReadLibrary = true), library = LibraryState(items = items), visible = items,
        selected = selected, selectedItems = items.filter { it.uri in selected }, selectedBytes = items.filter { it.uri in selected }.sumOf { it.sizeBytes },
    )
    private fun viewer(sequential: Boolean = true, selected: Set<String> = emptySet(), items: List<Screenshot> = listOf(first, second),
        close: () -> Unit = {}): MutableList<AppAction> {
        val actions = mutableListOf<AppAction>()
        compose.setContent { SnapSortTheme { ScreenshotViewer(state(items, selected), items.map { it.uri }, items.first().uri, sequential, close) { actions += it } } }
        return actions
    }

    @After fun cleanup() { imageFiles.forEach { it.delete() } }

    @Test fun sequentialKeepUnselectsCurrentAndAdvancesWithoutReturningToGrid() {
        val actions = viewer(selected = setOf(first.uri))
        compose.onNodeWithText("Keep", useUnmergedTree = true).performClick()
        compose.onNodeWithText("2 / 2 · ${second.name}").assertIsDisplayed()
        assertEquals(listOf(AppAction.SetItemKeep(first.uri, true), AppAction.ToggleSelection(first.uri)), actions)
    }

    @Test fun sequentialSelectAndSkipAdvanceAndNeverDispatchDeletion() {
        var closed = false
        val actions = viewer(close = { closed = true })
        compose.onNodeWithText("Select for trash").performClick()
        compose.onNodeWithText("2 / 2 · ${second.name}").assertIsDisplayed()
        compose.onNodeWithText("Skip").performClick()
        assertTrue(closed)
        assertEquals(listOf(AppAction.ToggleSelection(first.uri)), actions)
        assertFalse(actions.contains(AppAction.DeleteSelected))
    }

    @Test fun nextAndPreviousLeaveSelectionUntouched() {
        val actions = viewer(sequential = false)
        compose.onNodeWithContentDescription("Previous screenshot").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Next screenshot").performClick()
        compose.onNodeWithText("2 / 2 · ${second.name}").assertIsDisplayed()
        compose.onNodeWithContentDescription("Previous screenshot").performClick()
        compose.onNodeWithText("1 / 2 · ${first.name}").assertIsDisplayed()
        assertTrue(actions.isEmpty())
    }

    @Test fun finalReviewCanRemoveIndividualItemWithoutRequestingTrash() {
        val actions = mutableListOf<AppAction>()
        compose.setContent { SnapSortApp(state(selected = setOf(first.uri, second.uri))) { actions += it } }
        compose.onNodeWithText("Review selection").performClick()
        compose.onNodeWithContentDescription("Remove ${first.name} from trash selection").performScrollTo().performClick()
        assertEquals(listOf(AppAction.ToggleSelection(first.uri)), actions)
    }

    @Test fun mixedSelectionOffersSupportedGallerySubset() {
        val provider = second.copy(canDelete = false, manuallyAdded = true)
        val actions = mutableListOf<AppAction>()
        compose.setContent { SnapSortApp(state(listOf(first, provider), setOf(first.uri, second.uri))) { actions += it } }
        compose.onNodeWithText("Review selection").performClick()
        compose.onNodeWithText("Continue to Android · move to trash").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Select supported gallery images").performScrollTo().performClick()
        assertEquals(listOf(AppAction.SelectSupportedGalleryImages), actions)
    }

    @Test fun failedOcrShowsFailureAndRetryWhileSuccessfulHashRemainsComplete() {
        val failed = first.copy(ocrStatus = IndexStatus.FAILED, ocrError = "Image unavailable", hashStatus = IndexStatus.SUCCEEDED)
        val actions = viewer(items = listOf(failed), sequential = false)
        compose.onNodeWithContentDescription("Screenshot details and text").performClick()
        compose.onNodeWithText("OCR: Failed · Image unavailable").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("File hash: Complete").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Retry failed indexing").performScrollTo().performClick()
        assertEquals(listOf(AppAction.RetryIndex(first.uri)), actions)
    }

    private fun longImage(): File {
        val file = File(context.cacheDir, "viewer-${System.nanoTime()}.png")
        val bitmap = Bitmap.createBitmap(360, 12_000, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.RED)
        canvas.drawRect(0f, 10_000f, 360f, 12_000f, Paint().apply { color = Color.BLUE })
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        imageFiles += file
        return file
    }

    @Test fun longScreenshotRegionReadsBottomAtUsefulResolutionWithBoundedAllocation() {
        val file = longImage()
        RegionSource.open(context.contentResolver, Uri.fromFile(file)).use { source ->
            assertEquals(12_000, source.height)
            val tile = source.decode(RectF(0f, 11_000f, 360f, 12_000f), 1f)
            try {
                assertEquals(Color.BLUE, tile.bitmap.getPixel(100, 100))
                assertEquals(360, tile.bitmap.width)
                assertEquals(1000, tile.bitmap.height)
                assertTrue(tile.bitmap.allocationByteCount <= 2_500_000 * 4)
            } finally { tile.bitmap.recycle() }
        }
    }

    @Test fun zoomAndAccessiblePanControlsChangeLongScreenshotViewport() {
        val image = first.copy(uri = Uri.fromFile(longImage()).toString())
        viewer(items = listOf(image), sequential = false)
        compose.waitUntil(15_000) {
            compose.onNodeWithTag("readable-screenshot").fetchSemanticsNode().config.getOrElse(SemanticsProperties.StateDescription) { "Loading image" } != "Loading image"
        }
        val before = compose.onNodeWithTag("readable-screenshot").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        compose.onNodeWithContentDescription("Zoom in").performClick()
        compose.waitForIdle()
        val zoomed = compose.onNodeWithTag("readable-screenshot").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertNotEquals(before, zoomed)
        compose.onNodeWithContentDescription("Read later part").performClick()
        compose.waitForIdle()
        val panned = compose.onNodeWithTag("readable-screenshot").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        assertNotEquals(zoomed, panned)
    }

    private fun exifImage(orientation: Int, horizontal: Boolean = false): File {
        val file = File(context.cacheDir, "viewer-exif-${System.nanoTime()}.jpg")
        val bitmap = Bitmap.createBitmap(320, 640, Bitmap.Config.ARGB_8888)
        Canvas(bitmap).apply {
            drawColor(Color.RED)
            drawRect(if (horizontal) 160f else 0f, if (horizontal) 0f else 320f, 320f, 640f,
                Paint().apply { color = Color.BLUE })
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString()); saveAttributes()
        }
        imageFiles += file
        return file
    }

    @Test fun regionDecoderMapsRotatedExifCoordinatesToCorrectPixels() {
        val file = exifImage(ExifInterface.ORIENTATION_ROTATE_90)
        RegionSource.open(context.contentResolver, Uri.fromFile(file)).use { source ->
            assertEquals(640, source.width)
            assertEquals(320, source.height)
            // The original top (red) half moves to the right after a clockwise rotation.
            val tile = source.decode(RectF(400f, 40f, 600f, 260f), 1f)
            try {
                assertEquals(200, tile.bitmap.width)
                assertEquals(220, tile.bitmap.height)
                val pixel = tile.bitmap.getPixel(100, 100)
                assertTrue(Color.red(pixel) > 200 && Color.blue(pixel) < 80)
            } finally { tile.bitmap.recycle() }
        }
    }

    @Test fun regionDecoderMapsMirroredExifCoordinatesToCorrectPixels() {
        val file = exifImage(ExifInterface.ORIENTATION_FLIP_HORIZONTAL, horizontal = true)
        RegionSource.open(context.contentResolver, Uri.fromFile(file)).use { source ->
            val tile = source.decode(RectF(20f, 20f, 100f, 200f), 1f)
            try {
                val pixel = tile.bitmap.getPixel(30, 30)
                assertTrue(Color.blue(pixel) > 200 && Color.red(pixel) < 80)
            } finally { tile.bitmap.recycle() }
        }
    }

    @Test fun doubleTapUsesCurrentGestureStateAfterZoomChanges() {
        val image = first.copy(uri = Uri.fromFile(longImage()).toString())
        viewer(items = listOf(image), sequential = false)
        compose.waitUntil(15_000) {
            compose.onNodeWithTag("readable-screenshot").fetchSemanticsNode().config
                .getOrElse(SemanticsProperties.StateDescription) { "Loading image" } != "Loading image"
        }
        compose.onNodeWithContentDescription("Zoom in").performClick()
        compose.onNodeWithTag("readable-screenshot").performTouchInput { doubleClick() }
        compose.waitForIdle()
        assertTrue(compose.onNodeWithTag("readable-screenshot").fetchSemanticsNode()
            .config[SemanticsProperties.StateDescription].startsWith("Zoom 100 percent"))
        compose.onNodeWithTag("readable-screenshot").performTouchInput { doubleClick() }
        compose.waitForIdle()
        assertTrue(compose.onNodeWithTag("readable-screenshot").fetchSemanticsNode()
            .config[SemanticsProperties.StateDescription].startsWith("Zoom 200 percent"))
    }

    @Test fun decisionControlsRemainReachableAtDoubleFontSizeAndIconTargetsAre48Dp() {
        val actions = mutableListOf<AppAction>()
        var closed = false
        var density = 1f
        compose.setContent {
            val normal = LocalDensity.current
            density = normal.density
            CompositionLocalProvider(LocalDensity provides Density(normal.density, fontScale = 2f)) {
                SnapSortTheme {
                    ScreenshotViewer(state(), listOf(first.uri, second.uri), first.uri, true, { closed = true }) { actions += it }
                }
            }
        }
        val zoomTarget = compose.onNode(hasContentDescription("Zoom in") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        assertTrue("Zoom touch target width", zoomTarget.width >= 48f * density - 1f)
        assertTrue("Zoom touch target height", zoomTarget.height >= 48f * density - 1f)
        compose.onNodeWithText("Select for trash").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("2 / 2 · ${second.name}").assertIsDisplayed()
        compose.onNodeWithText("Keep", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Skip").performScrollTo().assertIsDisplayed().performClick()
        assertTrue(closed)
        assertEquals(listOf(AppAction.ToggleSelection(first.uri)), actions)
    }


    @Test fun removingEarlierQueueMemberPreservesCurrentOriginalAndNextNavigation() {
        val third = first.copy(uri = "content://media/external/images/media/77889902", name = "Screenshot-three.png")
        val library = mutableStateOf(listOf(first, second, third))
        val queue = listOf(first.uri, second.uri, third.uri)
        val actions = mutableListOf<AppAction>()
        compose.setContent {
            SnapSortTheme { ScreenshotViewer(state(library.value), queue, second.uri, false, {}) { actions += it } }
        }
        compose.onNodeWithText("2 / 3 · ${second.name}").assertIsDisplayed()
        compose.runOnIdle { library.value = listOf(second, third) }
        compose.onNodeWithText("1 / 2 · ${second.name}").assertIsDisplayed()
        compose.onNodeWithContentDescription("Next screenshot").performClick()
        compose.onNodeWithText("2 / 2 · ${third.name}").assertIsDisplayed()
        assertTrue(actions.isEmpty())
    }

    @Test fun disappearingCurrentOriginalChoosesNextThenPriorWithoutSelectingAnything() {
        val third = first.copy(uri = "content://media/external/images/media/77889902", name = "Screenshot-three.png")
        val library = mutableStateOf(listOf(first, second, third))
        val queue = listOf(first.uri, second.uri, third.uri)
        val actions = mutableListOf<AppAction>()
        compose.setContent {
            SnapSortTheme { ScreenshotViewer(state(library.value), queue, second.uri, true, {}) { actions += it } }
        }
        compose.onNodeWithText("2 / 3 · ${second.name}").assertIsDisplayed()
        compose.runOnIdle { library.value = listOf(first, third) }
        compose.onNodeWithText("2 / 2 · ${third.name}").assertIsDisplayed()
        compose.runOnIdle { library.value = listOf(first) }
        compose.onNodeWithText("1 / 1 · ${first.name}").assertIsDisplayed()
        assertTrue(actions.isEmpty())
    }

}
