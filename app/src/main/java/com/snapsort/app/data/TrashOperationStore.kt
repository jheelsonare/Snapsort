package com.snapsort.app.data

import android.content.Context
import android.provider.MediaStore
import androidx.core.net.toUri
import com.snapsort.app.domain.PendingTrashOperation
import org.json.JSONArray
import org.json.JSONObject

/** Small atomic metadata journal. Call on IO, before launching a system request. */
internal class TrashOperationStore(context: Context, name: String = "pending-trash") {
    private val preferences = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val resolver = context.applicationContext.contentResolver

    fun read(): PendingTrashOperation? {
        val encoded = preferences.getString("operation", null) ?: return null
        val json = JSONObject(encoded)
        check(json.getInt("version") == 1) { "Unsupported cleanup checkpoint" }
        fun list(key: String) = json.getJSONArray(key).let { a -> List(a.length()) { a.getString(it) } }
        return PendingTrashOperation(list("targets"), list("in_flight"), json.getInt("confirmed_batches"), json.getBoolean("review_required"))
    }

    @Suppress("UseKtx") // The boolean commit result is required before Android may be launched.
    fun write(operation: PendingTrashOperation?) {
        val editor = preferences.edit()
        if (operation == null) editor.remove("operation")
        else editor.putString("operation", JSONObject().apply {
            put("version", 1); put("targets", JSONArray(operation.targets)); put("in_flight", JSONArray(operation.inFlight))
            put("confirmed_batches", operation.confirmedBatches); put("review_required", operation.reviewRequired)
        }.toString())
        check(editor.commit()) { "Cleanup checkpoint could not be saved" }
    }

    data class Observed(val present: Set<String>, val trashed: Set<String>, val unavailable: Set<String>)

    /** Never interpret denied access, a missing row, or a moved provider as successful deletion. */
    fun observe(targets: List<String>): Observed {
        val present = hashSetOf<String>()
        val trashed = hashSetOf<String>()
        val unavailable = hashSetOf<String>()
        unavailable.addAll(targets)
        // Bounded queries by original volume, rather than one provider round-trip per image.
        targets.filter { GalleryScanner.isDeletableMediaUri(it.toUri()) }
            .groupBy { it.substringBeforeLast('/') }.forEach { (collection, uris) ->
            uris.chunked(400).forEach { chunk ->
                val byId = chunk.associateBy { it.substringAfterLast('/') }
                try {
                    val args = android.os.Bundle().apply {
                        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
                        putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION,
                            "${MediaStore.Images.Media._ID} IN (${chunk.joinToString(",") { "?" }})")
                        putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, byId.keys.toTypedArray())
                    }
                    resolver.query(collection.toUri(), arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.IS_TRASHED), args, null)?.use { c ->
                        while (c.moveToNext()) {
                            val uri = byId[c.getString(0)] ?: continue
                            if (c.getInt(1) == 1) trashed += uri else present += uri
                            unavailable -= uri
                        }
                    }
                } catch (_: Exception) { /* Unobserved originals remain unresolved. */ }
            }
        }
        return Observed(present, trashed, unavailable)
    }
}
