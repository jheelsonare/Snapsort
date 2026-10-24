package com.snapsort.app.domain

import java.util.Locale

/** Review signals only. These rules never delete or change a gallery item. */
object CleanupRules {
    private const val MILLIS_PER_DAY = 86_400_000L
    private val newestFirst = compareByDescending<Screenshot> { it.dateMillis }.thenBy { it.uri }
    private val preferredCopy = compareByDescending<Screenshot> { it.keep }
        .thenByDescending { it.dateMillis }.thenBy { it.uri }

    /**
     * Complete exact-file groups, with the preferred retained copy first. Kept members stay in the
     * group as anchors so an unkept copy can still be reviewed. Unless requested, all-kept groups
     * are omitted because they contain no bulk-cleanup candidates.
     */
    fun duplicateGroups(
        items: List<Screenshot>,
        includeKept: Boolean = false,
    ): List<List<Screenshot>> = items.distinctBy { it.uri }
        // A provider URI can be an alias of a gallery row, rather than a second physical file.
        // Only canonical original-media rows can establish that keeping one preserves a copy.
        .filter { it.canDelete && !it.hash.isNullOrBlank() && it.sizeBytes > 0L }
        .groupBy { it.hash to it.sizeBytes }
        .values
        .filter { group -> group.size > 1 && (includeKept || group.any { !it.keep }) }
        .map { group -> group.sortedWith(preferredCopy) }
        .sortedWith(compareByDescending<List<Screenshot>> { it.maxOf { image -> image.dateMillis } }
            .thenBy { it.first().uri })

    fun filter(
        items: List<Screenshot>,
        collection: ReviewCollection,
        category: Category? = null,
        query: String = "",
        olderThanDays: Int = 90,
        includeKept: Boolean = false,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<Screenshot> {
        val duplicateUris = if (collection == ReviewCollection.DUPLICATES) {
            duplicateGroups(items, includeKept = true).flatten().mapTo(hashSetOf()) { it.uri }
        } else emptySet()
        val ageMillis = olderThanDays.coerceAtLeast(0).toLong() * MILLIS_PER_DAY
        val cutoff = if (nowMillis < Long.MIN_VALUE + ageMillis) Long.MIN_VALUE else nowMillis - ageMillis
        val words = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter(String::isNotEmpty)
        val candidates = items.distinctBy { it.uri }.filter { item ->
            (collection == ReviewCollection.ALL || includeKept || !item.keep) &&
                (category == null || item.category == category) &&
                words.all { word ->
                    item.name.lowercase(Locale.ROOT).contains(word) ||
                        item.text.lowercase(Locale.ROOT).contains(word) ||
                        item.category.label.lowercase(Locale.ROOT).contains(word)
                } && when (collection) {
                    ReviewCollection.ALL, ReviewCollection.LARGE -> true
                    ReviewCollection.DUPLICATES -> item.uri in duplicateUris
                    ReviewCollection.OLDER -> item.dateMillis > 0 && item.dateMillis < cutoff
                }
        }
        return when (collection) {
            ReviewCollection.LARGE -> candidates.sortedWith(
                compareByDescending<Screenshot> { it.sizeBytes }.then(newestFirst),
            )
            else -> candidates.sortedWith(newestFirst)
        }
    }

    /** Keep one global representative per group; Keep images are never suggested for removal. */
    fun selectDuplicatesToRemove(items: List<Screenshot>): Set<String> = duplicateGroups(items)
        .flatMap { group -> group.drop(1).filterNot { it.keep } }
        .mapTo(linkedSetOf()) { it.uri }

    /** Choose the retained representative before applying a filtered view, preserving a copy. */
    fun selectDuplicatesToRemove(items: List<Screenshot>, visibleUris: Set<String>): Set<String> =
        selectDuplicatesToRemove(items).intersect(visibleUris)

    fun selectedBytes(items: List<Screenshot>, selected: Set<String>): Long = items
        .distinctBy { it.uri }
        .filter { it.uri in selected }
        .fold(0L) { total, item ->
            val bytes = item.sizeBytes.coerceAtLeast(0L)
            if (Long.MAX_VALUE - total < bytes) Long.MAX_VALUE else total + bytes
        }
}
