package com.snapsort.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.snapsort.app.domain.Category
import com.snapsort.app.domain.Screenshot
import com.snapsort.app.domain.IndexStatus

/** Metadata only. The gallery remains the sole owner of the original image bytes. */
internal class MetadataStore(context: Context, databaseName: String = "snapsort.db") : SQLiteOpenHelper(context, databaseName, null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE screenshots (
            uri TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, date_ms INTEGER NOT NULL,
            size_bytes INTEGER NOT NULL, source_stamp INTEGER NOT NULL DEFAULT 0,
            category TEXT NOT NULL, corrected INTEGER NOT NULL DEFAULT 0,
            extracted_text TEXT NOT NULL DEFAULT '', keep_item INTEGER NOT NULL DEFAULT 0,
            content_hash TEXT, indexed INTEGER NOT NULL DEFAULT 0,
            manual INTEGER NOT NULL DEFAULT 0, can_delete INTEGER NOT NULL DEFAULT 0,
            excluded INTEGER NOT NULL DEFAULT 0,
            hash_status TEXT NOT NULL DEFAULT 'PENDING', ocr_status TEXT NOT NULL DEFAULT 'PENDING',
            hash_error TEXT, ocr_error TEXT
        )""")
        db.execSQL("CREATE TABLE folders (uri TEXT PRIMARY KEY NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE screenshots ADD COLUMN hash_status TEXT NOT NULL DEFAULT 'PENDING'")
            db.execSQL("ALTER TABLE screenshots ADD COLUMN ocr_status TEXT NOT NULL DEFAULT 'PENDING'")
            db.execSQL("ALTER TABLE screenshots ADD COLUMN hash_error TEXT")
            db.execSQL("ALTER TABLE screenshots ADD COLUMN ocr_error TEXT")
            // A successful hash remains valuable even if the old combined OCR step failed.
            db.execSQL("UPDATE screenshots SET hash_status = 'SUCCEEDED' WHERE content_hash IS NOT NULL")
            db.execSQL("UPDATE screenshots SET ocr_status = 'SUCCEEDED' WHERE indexed = 1")
            db.execSQL("CREATE TABLE folders (uri TEXT PRIMARY KEY NOT NULL)")
        }
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        if (!db.isReadOnly) {
            // A process may have died during recognition. Only interrupted work is retried.
            db.execSQL("UPDATE screenshots SET hash_status = 'PENDING' WHERE hash_status = 'RUNNING'")
            db.execSQL("UPDATE screenshots SET ocr_status = 'PENDING' WHERE ocr_status = 'RUNNING'")
        }
    }

    fun relevant(uris: Collection<String>, includeManual: Boolean = true): Map<String, StoredImage> = buildMap {
        // Fetch only current gallery rows and persisted manual additions, not deleted history.
        if (includeManual) readableDatabase.query("screenshots", null, "manual = 1 AND excluded = 0", null, null, null, null).use { c ->
            while (c.moveToNext()) { val row = c.row(); put(row.image.uri, row) }
        }
        for (chunk in uris.distinct().chunked(400)) {
            val marks = chunk.joinToString(",") { "?" }
            readableDatabase.query("screenshots", null, "uri IN ($marks)", chunk.toTypedArray(), null, null, null).use { c ->
                while (c.moveToNext()) { val row = c.row(); put(row.image.uri, row) }
            }
        }
    }

    fun addFolder(uri: String) {
        writableDatabase.insertWithOnConflict("folders", null, ContentValues().apply { put("uri", uri) }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun folders(): List<String> = readableDatabase.query("folders", arrayOf("uri"), null, null, null, null, null).use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0)) }
    }

    fun restoreExcluded() { writableDatabase.execSQL("UPDATE screenshots SET excluded = 0 WHERE excluded = 1") }

    fun all(): Map<String, StoredImage> = readableDatabase.query(
        "screenshots", null, null, null, null, null, null,
    ).use { cursor -> buildMap { while (cursor.moveToNext()) { val row = cursor.row(); put(row.image.uri, row) } } }

    fun get(uri: String): StoredImage? = readableDatabase.query(
        "screenshots", null, "uri = ?", arrayOf(uri), null, null, null,
    ).use { if (it.moveToFirst()) it.row() else null }

    fun put(row: StoredImage) {
        val image = row.image
        val result = writableDatabase.insertWithOnConflict("screenshots", null, ContentValues().apply {
            put("uri", image.uri); put("name", image.name); put("date_ms", image.dateMillis)
            put("size_bytes", image.sizeBytes); put("source_stamp", row.sourceStamp)
            put("category", image.category.name); put("corrected", if (image.categoryCorrected) 1 else 0)
            put("extracted_text", image.text); put("keep_item", if (image.keep) 1 else 0)
            put("content_hash", image.hash); put("indexed", if (image.indexed) 1 else 0)
            put("manual", if (image.manuallyAdded) 1 else 0); put("can_delete", if (image.canDelete) 1 else 0)
            put("excluded", if (row.excluded) 1 else 0)
            put("hash_status", image.hashStatus.name); put("ocr_status", image.ocrStatus.name)
            put("hash_error", image.hashError); put("ocr_error", image.ocrError)
        }, SQLiteDatabase.CONFLICT_REPLACE)
        check(result != -1L) { "Screenshot metadata could not be saved" }
    }

    fun transaction(block: () -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            block()
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun Cursor.row(): StoredImage {
        fun string(name: String): String = getString(getColumnIndexOrThrow(name)).orEmpty()
        fun long(name: String) = getLong(getColumnIndexOrThrow(name))
        fun bool(name: String) = long(name) != 0L
        return StoredImage(
            Screenshot(
                uri = string("uri"), name = string("name"), dateMillis = long("date_ms"),
                sizeBytes = long("size_bytes"),
                category = runCatching { Category.valueOf(string("category")) }.getOrDefault(Category.OTHER),
                categoryCorrected = bool("corrected"), text = string("extracted_text"), keep = bool("keep_item"),
                hash = getString(getColumnIndexOrThrow("content_hash")), indexed = bool("indexed"), manuallyAdded = bool("manual"),
                canDelete = bool("can_delete"),
                hashStatus = runCatching { IndexStatus.valueOf(string("hash_status")) }.getOrDefault(IndexStatus.PENDING),
                ocrStatus = runCatching { IndexStatus.valueOf(string("ocr_status")) }.getOrDefault(IndexStatus.PENDING),
                hashError = getString(getColumnIndexOrThrow("hash_error")),
                ocrError = getString(getColumnIndexOrThrow("ocr_error")),
            ), sourceStamp = long("source_stamp"), excluded = bool("excluded"),
        )
    }
}

internal data class StoredImage(val image: Screenshot, val sourceStamp: Long = 0, val excluded: Boolean = false)
