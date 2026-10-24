package com.snapsort.app.domain

import java.text.Normalizer
import java.util.Locale

/** Small, transparent suggestions made entirely on the device; a user correction always wins. */
object CategoryClassifier {
    private data class Signal(val phrase: String, val weight: Int = 1)

    private val signals = linkedMapOf(
        Category.TICKETS to listOf(
            Signal("boarding pass", 6), Signal("e ticket", 5), Signal("eticket", 5),
            Signal("ticket", 3), Signal("booking confirmation", 4), Signal("admit one", 5),
            Signal("seat", 1), Signal("gate", 1), Signal("departure", 2), Signal("flight", 2),
            Signal("pnr", 4), Signal("check in", 2), Signal("concert", 2), Signal("event entry", 4),
        ),
        Category.RECEIPTS to listOf(
            Signal("receipt", 6), Signal("invoice", 5), Signal("tax invoice", 6),
            Signal("subtotal", 3), Signal("total paid", 4), Signal("amount paid", 4),
            Signal("payment successful", 4), Signal("transaction id", 3),
            Signal("sales tax", 3), Signal("gst", 2), Signal("vat", 2), Signal("balance due", 3),
        ),
        Category.STUDY to listOf(
            Signal("lecture", 4), Signal("lecture notes", 5), Signal("study notes", 5),
            Signal("homework", 4), Signal("assignment", 3), Signal("theorem", 4),
            Signal("syllabus", 4), Signal("textbook", 4), Signal("flashcard", 4),
            Signal("chapter", 2), Signal("lesson", 2), Signal("exam", 3),
            Signal("revision", 2), Signal("equation", 3), Signal("quiz", 2),
        ),
        Category.SHOPPING to listOf(
            Signal("add to cart", 6), Signal("add to bag", 6), Signal("buy now", 5),
            Signal("shopping cart", 5), Signal("wishlist", 4), Signal("wish list", 4),
            Signal("product details", 3), Signal("free shipping", 3), Signal("in stock", 3),
            Signal("discount", 2), Signal("coupon", 2), Signal("checkout", 3),
            Signal("size guide", 3), Signal("delivery estimate", 3),
        ),
        Category.CHATS to listOf(
            Signal("whatsapp", 5), Signal("messenger", 4), Signal("imessage", 5),
            Signal("telegram", 4), Signal("chat", 3), Signal("conversation", 3),
            Signal("last seen", 4), Signal("type a message", 5), Signal("message delivered", 4),
            Signal("message read", 3), Signal("typing", 2), Signal("voice message", 3),
        ),
    )

    fun classify(text: String, name: String): Category {
        val normalized = normalize("$name $text")
        if (normalized.isBlank()) return Category.OTHER
        val padded = " $normalized "
        var best = Category.OTHER
        var bestScore = 0
        for ((category, words) in signals) {
            val score = words.sumOf { signal ->
                if (padded.contains(" ${signal.phrase} ")) signal.weight else 0
            }
            if (score > bestScore) {
                best = category
                bestScore = score
            }
        }
        return best
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
}
