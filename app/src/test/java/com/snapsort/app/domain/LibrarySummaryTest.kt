package com.snapsort.app.domain

import org.junit.Assert.*
import org.junit.Test

class LibrarySummaryTest {
    private fun image(id: String, hash: String? = null, bytes: Long = 100, date: Long = 20, keep: Boolean = false) =
        Screenshot("content://media/external_primary/images/media/$id", "$id.png", date, bytes, hash = hash, keep = keep)

    @Test fun noAliasesInvalidSizesOrSingletonsCanEstablishCopies() {
        val original = image("1", "one")
        val providerAlias = original.copy(uri = "content://documents/one", canDelete = false, keep = true)
        val input = listOf(original, original, providerAlias, image("2", "", 100), image("3", "", 100),
            image("4", "zero", 0), image("5", "zero", 0), image("6", "negative", -100),
            image("7", "negative", -100), image("8", "one", 101))
        val summary = LibrarySummary(input)
        assertEquals(input.map { it.uri }.toSet(), summary.existingUris)
        assertTrue(summary.duplicateMembers.isEmpty())
        assertTrue(summary.duplicateExtras.isEmpty())
        assertTrue(summary.duplicateGroupSizes.isEmpty())
        assertEquals(501L, summary.totalBytes)
    }

    @Test fun keptOriginalIsRetainedEvenWhenItIsOldest() {
        val kept = image("1", "same", date = 1, keep = true)
        val newer = image("2", "same", date = 200)
        val newest = image("3", "same", date = 300)
        val input = listOf(kept, newer, newest)
        val summary = LibrarySummary(input)
        assertEquals(listOf(kept, newest, newer), summary.duplicateMembers)
        assertEquals(setOf(newer.uri, newest.uri), summary.duplicateExtras)
        assertEquals(mapOf(("same" to 100L) to 3), summary.duplicateGroupSizes)
        assertEquals(CleanupRules.selectDuplicatesToRemove(input), summary.duplicateExtras)
    }

    @Test fun allKeptGroupsRemainAvailableForExplicitInclusionWithoutCleanupExtras() {
        val a = image("1", "same", keep = true)
        val b = image("2", "same", keep = true)
        val summary = LibrarySummary(listOf(a, b))
        assertEquals(setOf(a, b), summary.duplicateMembers.toSet())
        assertEquals(2, summary.duplicateGroupSizes["same" to 100L])
        assertTrue(summary.duplicateExtras.isEmpty())
    }

    @Test fun snapshotDoesNotChangeWithCallerListAndRebuildHonoursKeepChange() {
        val old = image("1", "same", date = 1)
        val newest = image("2", "same", date = 100)
        val input = mutableListOf(old, newest)
        val first = LibrarySummary(input)
        input[0] = old.copy(keep = true)
        input += image("3", "unique")
        val rebuilt = LibrarySummary(input)
        assertEquals(setOf(old.uri), first.duplicateExtras)
        assertEquals(200L, first.totalBytes)
        assertEquals(setOf(newest.uri), rebuilt.duplicateExtras)
        assertEquals(300L, rebuilt.totalBytes)
        assertFalse(first.existingUris.contains(input.last().uri))
    }

    @Test fun byteTotalCountsIdentityOnceClampsNegativeAndSaturates() {
        val large = image("1", bytes = Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, LibrarySummary(listOf(large, large, image("2", bytes = 10),
            image("3", bytes = -1))).totalBytes)
        assertEquals(0L, LibrarySummary(listOf(image("1", bytes = -1))).totalBytes)
        assertEquals(0L, LibrarySummary(emptyList()).totalBytes)
    }
}
