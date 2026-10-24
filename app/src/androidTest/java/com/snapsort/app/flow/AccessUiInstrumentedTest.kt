package com.snapsort.app.flow

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snapsort.app.domain.GalleryAccess
import com.snapsort.app.ui.AppAction
import com.snapsort.app.ui.AppUiState
import com.snapsort.app.ui.SnapSortApp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Access explanations/actions; OS grant and denial must also be checked on a device. */
@RunWith(AndroidJUnit4::class)
class AccessUiInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun limitedAccessExplainsScopeAndOffersMoreImages() {
        val actions = mutableListOf<AppAction>()
        compose.setContent {
            SnapSortApp(AppUiState(access = GalleryAccess(canReadLibrary = true, limited = true))) { actions += it }
        }
        compose.onNodeWithTag("screenshot-grid")
            .performScrollToNode(hasText("You're sharing selected photos"))
        compose.onNodeWithText("You're sharing selected photos").assertIsDisplayed()
        compose.onNodeWithText("Only images Android has granted access to can appear here. Add more images or change access in settings.")
            .assertIsDisplayed()
        compose.onNodeWithText("Choose more").performClick()
        compose.onNodeWithContentDescription("Select additional images").performClick()
        compose.runOnIdle { assertEquals(listOf(AppAction.RequestAccess, AppAction.AddImages), actions) }
    }
}
