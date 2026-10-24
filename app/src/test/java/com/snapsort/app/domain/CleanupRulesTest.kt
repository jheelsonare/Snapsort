package com.snapsort.app.domain

import org.junit.Assert.*
import org.junit.Test

class CleanupRulesTest {
    private val now = 200L * 86_400_000
    private fun image(
        id: String,
        age: Int = 1,
        size: Long = 100,
        hash: String? = null,
        keep: Boolean = false,
        category: Category = Category.OTHER,
        text: String = "",
    ) = Screenshot(id, "$id.png", now - age * 86_400_000L, size, category = category,
        text = text, hash = hash, keep = keep)

    @Test fun allIsNewestFirstAndKeepsProtectedImagesVisible() {
        val result = CleanupRules.filter(listOf(image("old", age = 90), image("kept", keep = true)),
            ReviewCollection.ALL, nowMillis = now)
        assertEquals(listOf("kept", "old"), result.map { it.uri })
    }

    @Test fun olderUsesStrictAgeThresholdAndExcludesKeep() {
        val input = listOf(image("recent", 2), image("boundary", 90), image("old", 91),
            image("kept", 150, keep = true), image("unknown", 201).copy(dateMillis = 0))
        assertEquals(listOf("old"), CleanupRules.filter(input, ReviewCollection.OLDER,
            olderThanDays = 90, nowMillis = now).map { it.uri })
        assertEquals(listOf("old", "kept"), CleanupRules.filter(input, ReviewCollection.OLDER,
            includeKept = true, nowMillis = now).map { it.uri })
    }

    @Test fun largeSortsByBytesThenDateAndHonoursKeepChoice() {
        val input = listOf(image("small", size = 10), image("large", size = 500),
            image("protected", size = 1000, keep = true))
        assertEquals(listOf("large", "small"), CleanupRules.filter(input, ReviewCollection.LARGE).map { it.uri })
        assertEquals("protected", CleanupRules.filter(input, ReviewCollection.LARGE, includeKept = true).first().uri)
    }

    @Test fun searchRequiresEveryWordAcrossOcrNameAndCategory() {
        val input = listOf(image("lecture", category = Category.STUDY, text = "Theorem about triangles"),
            image("invoice", text = "Triangle shop"))
        assertEquals(listOf("lecture"), CleanupRules.filter(input, ReviewCollection.ALL,
            query = "TRIANGLE study").map { it.uri })
        assertTrue(CleanupRules.filter(input, ReviewCollection.ALL, category = Category.CHATS).isEmpty())
    }

    @Test fun exactDuplicatesRequireNonBlankHashAndMatchingPositiveSize() {
        val input = listOf(image("a", hash = "abc"), image("b", hash = "abc"),
            image("differentSize", hash = "abc", size = 101), image("unhashed"),
            image("unhashed2"), image("blank1", hash = ""), image("blank2", hash = ""),
            image("zero1", size = 0, hash = "zero"), image("zero2", size = 0, hash = "zero"))
        assertEquals(listOf(listOf("a", "b")), CleanupRules.duplicateGroups(input).map { group -> group.map { it.uri } })
    }

    @Test fun duplicateSelectionRetainsNewestCopy() {
        val input = listOf(image("old", age = 20, hash = "x"), image("new", hash = "x"),
            image("unique", hash = "y"))
        assertEquals(setOf("old"), CleanupRules.selectDuplicatesToRemove(input))
        assertEquals(setOf("old"), CleanupRules.selectDuplicatesToRemove(input, setOf("old", "unique")))
        assertTrue(CleanupRules.selectDuplicatesToRemove(input, setOf("new")).isEmpty())
    }

    @Test fun aProviderAliasCannotEstablishASecondOriginalCopy() {
        val original = image("original", hash = "same")
        val alias = image("provider", hash = "same", keep = true).copy(canDelete = false)
        assertTrue(CleanupRules.duplicateGroups(listOf(original, alias)).isEmpty())
        assertTrue(CleanupRules.selectDuplicatesToRemove(listOf(original, alias)).isEmpty())
    }

    @Test fun providerAliasesCannotReplaceTheRetainedGalleryOriginal() {
        val input = listOf(image("older", age = 30, hash = "same"), image("newer", hash = "same"),
            image("provider", hash = "same", keep = true).copy(canDelete = false))
        assertEquals(setOf("older"), CleanupRules.selectDuplicatesToRemove(input))
        assertEquals(setOf("older", "newer"), CleanupRules.filter(input, ReviewCollection.DUPLICATES)
            .map { it.uri }.toSet())
    }

    @Test fun keptAnchorStillLetsOneUnkeptDuplicateBeReviewed() {
        val input = listOf(image("kept", age = 30, hash = "x", keep = true), image("copy", hash = "x"))
        assertEquals(listOf("kept", "copy"), CleanupRules.duplicateGroups(input).single().map { it.uri })
        assertEquals(listOf("copy"), CleanupRules.filter(input, ReviewCollection.DUPLICATES).map { it.uri })
        assertEquals(setOf("copy"), CleanupRules.selectDuplicatesToRemove(input))
        assertEquals(100L, CleanupRules.selectedBytes(input, setOf("copy")))
    }

    @Test fun allKeptDuplicatesNeedExplicitInclusionAndAreNeverAutoSelected() {
        val input = listOf(image("a", hash = "x", keep = true), image("b", hash = "x", keep = true))
        assertTrue(CleanupRules.duplicateGroups(input).isEmpty())
        assertEquals(1, CleanupRules.duplicateGroups(input, includeKept = true).size)
        assertTrue(CleanupRules.filter(input, ReviewCollection.DUPLICATES).isEmpty())
        assertEquals(2, CleanupRules.filter(input, ReviewCollection.DUPLICATES, includeKept = true).size)
        assertTrue(CleanupRules.selectDuplicatesToRemove(input).isEmpty())
    }

    @Test fun filteringADuplicateByCategoryDoesNotLoseItsRelationship() {
        val input = listOf(image("a", hash = "x", category = Category.STUDY), image("b", hash = "x"))
        assertEquals(listOf("a"), CleanupRules.filter(input, ReviewCollection.DUPLICATES,
            category = Category.STUDY).map { it.uri })
        assertTrue(CleanupRules.selectDuplicatesToRemove(input, setOf("a")).isEmpty())
    }

    @Test fun selectedBytesCountsVisibleIdentitiesOnceAndIgnoresMissingIds() {
        val a = image("a", size = 12)
        val b = image("b", size = 25)
        assertEquals(37L, CleanupRules.selectedBytes(listOf(a, a, b), setOf("a", "b", "missing")))
        assertEquals(0L, CleanupRules.selectedBytes(listOf(a), emptySet()))
    }
}
