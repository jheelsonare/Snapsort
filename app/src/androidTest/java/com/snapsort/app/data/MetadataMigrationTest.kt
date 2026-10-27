package com.snapsort.app.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.snapsort.app.domain.Category
import com.snapsort.app.domain.IndexStatus
import com.snapsort.app.domain.Screenshot
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MetadataMigrationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val name = "migration-${System.nanoTime()}.db"
    private val uri = "content://media/external_primary/images/media/999999"

    @After fun cleanup() { context.deleteDatabase(name) }

    @Test fun versionOneMigrationPreservesOrganisationAndPartialHashSuccess() {
        val file = context.getDatabasePath(name)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("""CREATE TABLE screenshots (
                uri TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, date_ms INTEGER NOT NULL,
                size_bytes INTEGER NOT NULL, source_stamp INTEGER NOT NULL DEFAULT 0,
                category TEXT NOT NULL, corrected INTEGER NOT NULL DEFAULT 0,
                extracted_text TEXT NOT NULL DEFAULT '', keep_item INTEGER NOT NULL DEFAULT 0,
                content_hash TEXT, indexed INTEGER NOT NULL DEFAULT 0, manual INTEGER NOT NULL DEFAULT 0,
                can_delete INTEGER NOT NULL DEFAULT 0, excluded INTEGER NOT NULL DEFAULT 0)""")
            db.insertOrThrow("screenshots", null, ContentValues().apply {
                put("uri", uri); put("name", "Screenshot.png"); put("date_ms", 55L); put("size_bytes", 100L)
                put("category", "STUDY"); put("corrected", 1); put("keep_item", 1)
                put("content_hash", "previous-hash"); put("indexed", 0); put("manual", 1); put("excluded", 1)
            })
            db.version = 1
        }
        MetadataStore(context, name).use { store ->
            val row = requireNotNull(store.get(uri))
            assertEquals(Category.STUDY, row.image.category)
            assertTrue(row.image.categoryCorrected)
            assertTrue(row.image.keep)
            assertTrue(row.image.manuallyAdded)
            assertTrue(row.excluded)
            assertEquals("previous-hash", row.image.hash)
            assertEquals(IndexStatus.SUCCEEDED, row.image.hashStatus)
            assertEquals(IndexStatus.PENDING, row.image.ocrStatus)
            assertFalse(row.image.indexed)
            store.addFolder("content://documents/tree/example")
            assertEquals(listOf("content://documents/tree/example"), store.folders())
        }
    }

    @Test fun interruptedStepsResumeButFailedStepsRemainExplicitAfterReopen() {
        MetadataStore(context, name).use { store ->
            store.put(StoredImage(Screenshot(uri, "Screenshot.png", 0, 10,
                hashStatus = IndexStatus.RUNNING, ocrStatus = IndexStatus.FAILED, ocrError = "Access denied")))
        }
        MetadataStore(context, name).use { store ->
            val image = requireNotNull(store.get(uri)).image
            assertEquals(IndexStatus.PENDING, image.hashStatus)
            assertEquals(IndexStatus.FAILED, image.ocrStatus)
            assertEquals("Access denied", image.ocrError)
            assertTrue(image.hasIndexFailure)
        }
    }
}
