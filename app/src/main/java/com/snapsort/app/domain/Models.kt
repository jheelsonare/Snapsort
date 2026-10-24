package com.snapsort.app.domain

enum class Category(val label: String) {
    STUDY("Study"), RECEIPTS("Receipts"), SHOPPING("Shopping"), TICKETS("Tickets"), CHATS("Chats"), OTHER("Other")
}

enum class ReviewCollection(val label: String) {
    ALL("All screenshots"), DUPLICATES("Exact duplicates"), OLDER("Older screenshots"), LARGE("Large screenshots")
}

data class Screenshot(
    val uri: String,
    val name: String,
    val dateMillis: Long,
    val sizeBytes: Long,
    val category: Category = Category.OTHER,
    val categoryCorrected: Boolean = false,
    val text: String = "",
    val keep: Boolean = false,
    val hash: String? = null,
    val indexed: Boolean = false,
    val manuallyAdded: Boolean = false,
    val canDelete: Boolean = true,
)

data class GalleryAccess(val canReadLibrary: Boolean = false, val limited: Boolean = false)

data class LibraryState(
    val items: List<Screenshot> = emptyList(),
    val loading: Boolean = false,
    val processing: Boolean = false,
    val processed: Int = 0,
    val totalToProcess: Int = 0,
    val error: String? = null,
)
