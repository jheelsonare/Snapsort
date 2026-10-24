package com.snapsort.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snapsort.app.domain.Category
import com.snapsort.app.domain.GalleryAccess
import com.snapsort.app.domain.LibraryState
import com.snapsort.app.domain.Screenshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SnapSortUiTest {
    @get:Rule val compose = createComposeRule()

    private val shot = Screenshot(
        uri = "content://media/external/images/media/99887766",
        name = "Screenshot-test.png",
        dateMillis = 1_700_000_000_000,
        sizeBytes = 512_000,
        indexed = true,
    )

    private fun show(selected: Boolean = true, item: Screenshot = shot): MutableList<AppAction> {
        val actions = mutableListOf<AppAction>()
        compose.setContent {
            SnapSortApp(AppUiState(
                access = GalleryAccess(canReadLibrary = true),
                library = LibraryState(items = listOf(item)),
                visible = listOf(item),
                selected = if (selected) setOf(item.uri) else emptySet(),
                selectedItems = if (selected) listOf(item) else emptyList(),
                selectedBytes = if (selected) item.sizeBytes else 0,
                totalBytes = item.sizeBytes,
            )) { actions += it }
        }
        return actions
    }

    @Test fun deletionRequiresPreviewAndExplicitContinue() {
        val actions = show()
        compose.onNodeWithText("Review selection").performClick()
        compose.onNodeWithText("One last look").assertIsDisplayed()
        assertTrue(actions.none { it == AppAction.DeleteSelected })
        compose.onNodeWithText("Continue to Android · move to trash").performScrollTo().performClick()
        assertEquals(listOf(AppAction.DeleteSelected), actions)
    }

    @Test fun cancellingReviewDoesNotRequestDeletion() {
        val actions = show()
        compose.onNodeWithText("Review selection").performClick()
        compose.onNodeWithText("Keep reviewing").performScrollTo().performClick()
        assertTrue(actions.none { it == AppAction.DeleteSelected })
        compose.onNodeWithText("Review selection").assertIsDisplayed()
    }

    @Test fun keptImageHasExplicitReviewWarning() {
        val actions = show(item = shot.copy(keep = true))
        compose.onNodeWithText("Review selection").performClick()
        compose.onNodeWithText("Your selection includes screenshots marked Keep. Review these carefully.")
            .performScrollTo().assertIsDisplayed()
        assertTrue(actions.none { it == AppAction.DeleteSelected })
    }

    @Test fun unsupportedOriginalCannotEnterSystemDeletion() {
        val actions = show(item = shot.copy(canDelete = false, manuallyAdded = true))
        compose.onNodeWithText("Review selection").performClick()
        compose.onNodeWithText("Continue to Android · move to trash").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Remove from SnapSort only").performScrollTo().performClick()
        assertEquals(listOf(AppAction.RemoveSelectedFromCollection), actions)
    }

    @Test fun categoryCorrectionDispatchesChosenCategory() {
        val actions = show()
        compose.onNodeWithContentDescription("Change category for selection").performClick()
        compose.onAllNodesWithText("Study").onLast().performClick()
        assertEquals(listOf(AppAction.CorrectCategory(Category.STUDY)), actions)
    }

    @Test fun thumbnailCheckboxSelectsOriginalUri() {
        val actions = show(selected = false)
        compose.onNodeWithTag("screenshot-grid")
            .performScrollToNode(hasContentDescription("Select ${shot.name}"))
        compose.onNodeWithContentDescription("Select ${shot.name}").performClick()
        assertEquals(listOf(AppAction.ToggleSelection(shot.uri)), actions)
    }

    @Test fun keepAndDeselectAreSeparateActions() {
        val actions = show()
        compose.onNodeWithContentDescription("Mark selected screenshots as Keep").performClick()
        compose.onNodeWithText("Deselect").performClick()
        assertEquals(listOf(AppAction.MarkKeep(true), AppAction.ClearSelection), actions)
    }

    @Test fun includingKeptScreenshotsRequiresAnExplicitToggle() {
        val actions = show(selected = false)
        compose.onNodeWithTag("screenshot-grid")
            .performScrollToNode(hasContentDescription("Include Keep in bulk actions"))
        compose.onNodeWithContentDescription("Include Keep in bulk actions").assertIsOff().performClick()
        assertEquals(listOf(AppAction.IncludeKept(true)), actions)
    }
}
