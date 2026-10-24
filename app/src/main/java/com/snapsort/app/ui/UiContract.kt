package com.snapsort.app.ui

import com.snapsort.app.domain.*

data class AppUiState(
    val library: LibraryState = LibraryState(),
    val access: GalleryAccess = GalleryAccess(),
    val collection: ReviewCollection = ReviewCollection.ALL,
    val category: Category? = null,
    val query: String = "",
    val olderThanDays: Int = 90,
    val includeKept: Boolean = false,
    val visible: List<Screenshot> = emptyList(),
    val selected: Set<String> = emptySet(),
    val selectedItems: List<Screenshot> = emptyList(),
    val totalBytes: Long = 0,
    val selectedBytes: Long = 0,
    val duplicateCount: Int = 0,
    val duplicateGroupSizes: Map<Pair<String?, Long>, Int> = emptyMap(),
    val busyDeleting: Boolean = false,
    val message: String? = null,
)

sealed interface AppAction {
    data object RequestAccess : AppAction
    data object ManageAccess : AppAction
    data object AddImages : AppAction
    data object Refresh : AppAction
    data class ChangeCollection(val value: ReviewCollection) : AppAction
    data class FilterCategory(val value: Category?) : AppAction
    data class Search(val value: String) : AppAction
    data class ChangeAge(val days: Int) : AppAction
    data class IncludeKept(val value: Boolean) : AppAction
    data class ToggleSelection(val uri: String) : AppAction
    data object SelectAll : AppAction
    data object ClearSelection : AppAction
    data object SelectDuplicateExtras : AppAction
    data class MarkKeep(val keep: Boolean) : AppAction
    data class CorrectCategory(val category: Category) : AppAction
    data class SetItemKeep(val uri: String, val keep: Boolean) : AppAction
    data class SetItemCategory(val uri: String, val category: Category) : AppAction
    data object DeleteSelected : AppAction
    data object RemoveSelectedFromCollection : AppAction
    data object DismissMessage : AppAction
}
