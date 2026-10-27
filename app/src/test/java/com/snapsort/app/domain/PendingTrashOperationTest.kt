package com.snapsort.app.domain

import org.junit.Assert.*
import org.junit.Test

class PendingTrashOperationTest {
    @Test fun checkpointBoundsBatchAndRetainsRemainingTargets() {
        val uris = (1..4001).map { "content://media/external_primary/images/media/$it" }
        val checkpoint = PendingTrashOperation.reviewed(uris + uris.first())
        assertEquals(4001, checkpoint.targets.size)
        assertEquals(2000, checkpoint.inFlight.size)
        assertFalse(checkpoint.reviewRequired)
    }

    @Test fun deathBeforeOrAfterConfirmationNeverProducesAnAutomaticNextBatch() {
        val original = PendingTrashOperation.reviewed(listOf("one", "two", "three"))
        val recovered = original.interrupted()
        assertTrue(recovered.reviewRequired)
        assertTrue(recovered.inFlight.isEmpty())
        val reconciled = recovered.reconcile(setOf("one"), confirmed = true)!!
        assertEquals(listOf("two", "three"), reconciled.targets)
        assertEquals(1, reconciled.confirmedBatches)
        assertTrue(reconciled.inFlight.isEmpty())
        assertTrue(reconciled.reviewRequired)
    }

    @Test fun missingOrRevokedTargetsCannotBeCountedAsDeleted() {
        val original = PendingTrashOperation.reviewed(listOf("present", "revoked", "missing", "trashed"))
        assertEquals(listOf("present", "revoked", "missing"), original.reconcile(setOf("trashed"))!!.targets)
        assertNull(original.reconcile(original.targets.toSet()))
    }

    @Test fun unreviewedOrForeignBatchesAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { PendingTrashOperation(listOf("one"), emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { PendingTrashOperation(listOf("one"), listOf("two")) }
    }
}
