package com.snapsort.app.domain

import org.junit.Assert.assertEquals
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
}
