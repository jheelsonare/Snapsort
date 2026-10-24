package com.snapsort.app.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.snapsort.app.domain.Category
import com.snapsort.app.domain.Screenshot

/** Metadata only. The gallery remains the sole owner of the original image bytes. */
internal class MetadataStore(context: Context, databaseName: String = "snapsort.db") : SQLiteOpenHelper(context, databaseName, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE screenshots (
            uri TEXT PRIMARY KEY NOT NULL, name TEXT NOT NULL, date_ms INTEGER NOT NULL,
            size_bytes INTEGER NOT NULL, source_stamp INTEGER NOT NULL DEFAULT 0,
            category TEXT NOT NULL, corrected INTEGER NOT NULL DEFAULT 0,
            extracted_text TEXT NOT NULL DEFAULT '', keep_item INTEGER NOT NULL DEFAULT 0,
            content_hash TEXT, indexed INTEGER NOT NULL DEFAULT 0,
            manual INTEGER NOT NULL DEFAULT 0, can_delete INTEGER NOT NULL DEFAULT 0,
            excluded INTEGER NOT NULL DEFAULT 0
        )""")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

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
            ), sourceStamp = long("source_stamp"), excluded = bool("excluded"),
        )
    }
}

internal data class StoredImage(val image: Screenshot, val sourceStamp: Long = 0, val excluded: Boolean = false)
