package com.snapsort.app.domain

/** A checkpoint is intent, never proof that Android removed an original. */
data class PendingTrashOperation(
    val targets: List<String>,
    val inFlight: List<String>,
    val confirmedBatches: Int = 0,
    val reviewRequired: Boolean = false,
) {
    init {
        require(targets.isNotEmpty() && targets.distinct().size == targets.size)
        require(inFlight.size <= BATCH_SIZE && inFlight.distinct().size == inFlight.size)
        val targetSet = targets.toHashSet()
        require(inFlight.all { it in targetSet } && confirmedBatches >= 0)
        require(reviewRequired || inFlight.isNotEmpty())
    }

    fun interrupted() = copy(inFlight = emptyList(), reviewRequired = true)

    /** Missing/revoked originals stay unresolved. Only observed trash is conclusive. */
    fun reconcile(trashed: Set<String>, confirmed: Boolean = false): PendingTrashOperation? {
        val remaining = targets.filterNot { it in trashed }
        return remaining.takeIf { it.isNotEmpty() }?.let {
            copy(targets = it, inFlight = emptyList(),
                confirmedBatches = confirmedBatches + if (confirmed) 1 else 0, reviewRequired = true)
        }
    }

    companion object {
        const val BATCH_SIZE = 2000
        fun reviewed(uris: List<String>) = uris.distinct().let { PendingTrashOperation(it, it.take(BATCH_SIZE)) }
    }
}
