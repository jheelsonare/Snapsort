package com.snapsort.app.domain

import java.util.Collections

/**
 * Immutable metadata derived from one library snapshot. Reuse it across query/selection changes;
 * construct a new summary whenever library items change (including hash, size, URI or Keep).
 * It never stores a mutable reference to the caller's collection or changes any original image.
 */
class LibrarySummary(items: List<Screenshot>) {
    private val uniqueItems = items.distinctBy { it.uri }
    private val groups = CleanupRules.duplicateGroups(uniqueItems, includeKept = true)

    val existingUris: Set<String> = Collections.unmodifiableSet(uniqueItems.mapTo(linkedSetOf()) { it.uri })
    val totalBytes: Long = uniqueItems.fold(0L) { total, item ->
        val bytes = item.sizeBytes.coerceAtLeast(0L)
        if (Long.MAX_VALUE - total < bytes) Long.MAX_VALUE else total + bytes
    }

    /** Includes Keep anchors and all-kept groups; visibility filters decide which are shown. */
    val duplicateMembers: List<Screenshot> = Collections.unmodifiableList(groups.flatten())

    /** One global original is retained; Keep images are never proposed as extra copies. */
    val duplicateExtras: Set<String> = Collections.unmodifiableSet(
        groups.flatMap { group -> group.drop(1).filterNot { it.keep } }.mapTo(linkedSetOf()) { it.uri },
    )

    /** Only real exact-copy groups: distinct supported originals, nonblank hash, positive size. */
    val duplicateGroupSizes: Map<Pair<String?, Long>, Int> = Collections.unmodifiableMap(
        groups.associateTo(linkedMapOf()) { group -> (group.first().hash to group.first().sizeBytes) to group.size },
    )
}
