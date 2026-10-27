package com.snapsort.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryClassifierTest {
    @Test fun recognisesDocumentAndCollectionSignals() {
        assertEquals(Category.STUDY, CategoryClassifier.classify("Chapter 4: Lecture notes", "Screenshot.png"))
        assertEquals(Category.RECEIPTS, CategoryClassifier.classify("Tax invoice Subtotal GST Amount paid", "Screenshot.png"))
        assertEquals(Category.SHOPPING, CategoryClassifier.classify("Add to cart Free shipping", "Screenshot.png"))
        assertEquals(Category.TICKETS, CategoryClassifier.classify("Boarding pass Flight 123 Gate 7 Seat 24A", "Screenshot.png"))
        assertEquals(Category.CHATS, CategoryClassifier.classify("WhatsApp Last seen Type a message", "Screenshot.png"))
    }

    @Test fun blankTextAndUnknownContentStayOther() {
        assertEquals(Category.OTHER, CategoryClassifier.classify("", "Screenshot_20260101.png"))
        assertEquals(Category.OTHER, CategoryClassifier.classify("mountains with a blue sky", "view.png"))
    }

    @Test fun handlesPunctuationCaseAndFilenameSignals() {
        assertEquals(Category.TICKETS, CategoryClassifier.classify("", "E-TICKET_2026.png"))
        assertEquals(Category.RECEIPTS, CategoryClassifier.classify("PAYMENT-SUCCESSFUL", "IMG.png"))
        assertEquals(Category.STUDY, CategoryClassifier.classify("LÉCTURE NOTES", "IMG.png"))
    }

    @Test fun matchesWholeWordsInsteadOfUnrelatedSubstrings() {
        assertEquals(Category.OTHER, CategoryClassifier.classify("Chattanooga gateway seatbelt", "IMG.png"))
    }
    @Test fun weakGenericEvidenceNeedsReview() {
        for (text in listOf("gate", "seat", "chapter", "discount", "typing", "ticket", "chat")) {
            val suggestion = CategoryClassifier.suggest(text, "Screenshot.png")
            assertEquals(text, Category.OTHER, suggestion.category)
            assertTrue(text, suggestion.needsReview)
        }
    }

    @Test fun equallyPlausibleCategoriesNeedReviewRegardlessOfSignalOrder() {
        assertEquals(Category.OTHER, CategoryClassifier.classify("Receipt Add to cart", "Screenshot.png"))
        assertEquals(Category.OTHER, CategoryClassifier.classify("Buy now WhatsApp", "Screenshot.png"))
        // One extra weak signal still does not make a close winner dependable.
        assertEquals(Category.OTHER, CategoryClassifier.classify("Receipt Add to cart Seat", "Screenshot.png"))
    }

    @Test fun independentSignalsCanReachThresholdAndExposeEvidence() {
        val suggestion = CategoryClassifier.suggest("Flight departure", "Screenshot.png")
        assertEquals(Category.TICKETS, suggestion.category)
        assertEquals(4, suggestion.score)
        assertTrue(suggestion.evidence.containsAll(listOf("flight", "departure")))
        assertFalse(suggestion.needsReview)
    }

    @Test fun confidentLeadingCategoryRequiresUsefulMargin() {
        assertEquals(Category.OTHER, CategoryClassifier.classify("Receipt Buy now", "Screenshot.png"))
        assertEquals(Category.RECEIPTS, CategoryClassifier.classify("Receipt Subtotal Buy now", "Screenshot.png"))
    }

    @Test fun repeatedWordsDoNotManufactureConfidence() {
        assertEquals(Category.OTHER, CategoryClassifier.classify("discount discount discount discount", "Screenshot.png"))
    }

}
