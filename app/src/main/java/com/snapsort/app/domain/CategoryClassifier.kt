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

    /** Raw rule evidence, not a calibrated probability of correctness. */
    data class Suggestion(
        val category: Category,
        val leadingCategory: Category,
        val score: Int,
        val margin: Int,
        val evidence: List<String>,
    ) {
        val needsReview: Boolean get() = category == Category.OTHER
    }

    // A weak word such as "seat", "chapter", or "discount" cannot establish a category.
    // Close winners are deliberately left for review, rather than resolved by enum order.
    private const val MIN_SCORE = 4
    private const val MIN_MARGIN = 2

    fun classify(text: String, name: String): Category = suggest(text, name).category

    fun suggest(text: String, name: String): Suggestion {
        val padded = " ${normalize("$name $text")} "
        val ranked = signals.map { (category, words) ->
            val matched = words.filter { padded.contains(" ${it.phrase} ") }
            Triple(category, matched.sumOf { it.weight }, matched.map { it.phrase })
        }.sortedByDescending { it.second }
        val first = ranked.first()
        val margin = first.second - ranked[1].second
        val accepted = first.second >= MIN_SCORE && margin >= MIN_MARGIN
        return Suggestion(
            category = if (accepted) first.first else Category.OTHER,
            leadingCategory = if (first.second > 0) first.first else Category.OTHER,
            score = first.second,
            margin = margin,
            evidence = first.third,
        )
    }

    private fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
}
