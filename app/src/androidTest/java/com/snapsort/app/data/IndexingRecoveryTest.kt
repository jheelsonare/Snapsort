package com.snapsort.app.data

import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snapsort.app.domain.Category
import com.snapsort.app.domain.GalleryAccess
import com.snapsort.app.domain.IndexStatus
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IndexingRecoveryTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val resolver = context.contentResolver
    private val created = mutableListOf<Uri>()
    private val db = "index-recovery-${System.nanoTime()}.db"
    private val access = GalleryAccess(canReadLibrary = true)

    @After fun cleanup() {
        created.forEach { runCatching { resolver.delete(it, null, null) } }
        context.deleteDatabase(db)
    }

    @Test fun successfulHashSurvivesOcrFailureAndRetryRunsOnlyFailedStep() = runBlocking {
        val uri = fixture()
        val operations = FakeOperations(ocrFails = true)
        val repo = GalleryRepository(context, db, operations)
        try {
            repo.refresh(access)
            await(repo)
            val failed = repo.state.value.items.single { it.uri == uri.toString() }
            assertEquals(IndexStatus.SUCCEEDED, failed.hashStatus)
            assertEquals("test-file-hash", failed.hash)
            assertEquals(IndexStatus.FAILED, failed.ocrStatus)
            assertFalse(failed.indexed)
            assertNotNull(failed.ocrError)
            val hashCalls = operations.hashCalls
            val ocrCalls = operations.ocrCalls
            repo.refresh(access)
            await(repo)
            assertEquals("Refresh must not silently retry failed OCR", ocrCalls, operations.ocrCalls)
            operations.ocrFails = false
            repo.retryIndex(setOf(uri.toString()))
            await(repo)
            val completed = repo.state.value.items.single { it.uri == uri.toString() }
            assertEquals(hashCalls, operations.hashCalls)
            assertEquals(ocrCalls + 1, operations.ocrCalls)
            assertTrue(completed.indexed)
            assertEquals(IndexStatus.SUCCEEDED, completed.ocrStatus)
            assertNull(completed.ocrError)
        } finally { repo.close(); delay(100) }
    }

    @Test fun hashFailureDoesNotBlockTextAndRetryLeavesSuccessfulOcrAlone() = runBlocking {
        val uri = fixture()
        val operations = FakeOperations(hashFails = true)
        val repo = GalleryRepository(context, db, operations)
        try {
            repo.refresh(access); await(repo)
            val image = repo.state.value.items.single { it.uri == uri.toString() }
            assertEquals(IndexStatus.FAILED, image.hashStatus)
            assertEquals(IndexStatus.SUCCEEDED, image.ocrStatus)
            assertTrue(image.text.contains("RECEIPT"))
            val ocrCalls = operations.ocrCalls
            operations.hashFails = false
            repo.retryIndex(setOf(uri.toString())); await(repo)
            assertEquals(ocrCalls, operations.ocrCalls)
            assertTrue(repo.state.value.items.single { it.uri == uri.toString() }.indexed)
        } finally { repo.close(); delay(100) }
    }

    @Test fun explicitExclusionAndRestoreNeverDeleteOriginalAndCategoryCanReset() = runBlocking {
        val uri = fixture()
        val repo = GalleryRepository(context, db, FakeOperations())
        try {
            repo.refresh(access); await(repo)
            repo.setCategory(setOf(uri.toString()), Category.STUDY)
            assertTrue(repo.state.value.items.single { it.uri == uri.toString() }.categoryCorrected)
            repo.resetCategory(setOf(uri.toString()))
            assertFalse(repo.state.value.items.single { it.uri == uri.toString() }.categoryCorrected)
            repo.excludeImages(setOf(uri.toString()))
            repo.refresh(access)
            assertFalse(repo.state.value.items.any { it.uri == uri.toString() })
            resolver.openInputStream(uri).use { assertNotNull(it) }
            repo.restoreExcluded(); repo.refresh(access)
            assertTrue(repo.state.value.items.any { it.uri == uri.toString() })
        } finally { repo.close(); delay(100) }
    }

    private suspend fun await(repo: GalleryRepository) {
        withTimeout(30_000) { while (repo.state.value.processing || repo.state.value.loading) delay(30) }
    }

    private fun fixture(): Uri {
        val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Screenshot_IndexRecovery_${System.nanoTime()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/SnapSortIndexRecovery_Screenshots/")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }))
        created += uri
        resolver.openOutputStream(uri).use { requireNotNull(it).write(byteArrayOf(1, 2, 3, 4)) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        return uri
    }

    private class FakeOperations(var hashFails: Boolean = false, var ocrFails: Boolean = false) : ImageOperations {
        var hashCalls = 0
        var ocrCalls = 0
        override suspend fun hash(uri: Uri): String {
            hashCalls++
            if (hashFails) throw IOException("fixture hash error")
            return "test-file-hash"
        }
        override suspend fun text(uri: Uri): String {
            ocrCalls++
            if (ocrFails) throw IOException("fixture recognition error")
            return "RECEIPT TOTAL PAID 42.00"
        }
        override fun close() = Unit
    }
}
