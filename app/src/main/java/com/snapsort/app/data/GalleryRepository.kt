package com.snapsort.app.data

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.snapsort.app.domain.Category
import com.snapsort.app.domain.CategoryClassifier
import com.snapsort.app.domain.GalleryAccess
import com.snapsort.app.domain.LibraryState
import com.snapsort.app.domain.Screenshot
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** All I/O is dispatched off the UI thread; only metadata is retained in private storage. */
class GalleryRepository(context: Context, databaseName: String = "snapsort.db") {
    private val appContext = context.applicationContext
    private val store = MetadataStore(appContext, databaseName)
    private val scanner = GalleryScanner(appContext)
    private val indexer = ImageIndexer(appContext.contentResolver)
    private val repositoryJob = SupervisorJob()
    private val scope = CoroutineScope(repositoryJob + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = mutableState.asStateFlow()
    private var indexJob: Job? = null
    private val failedThisPass = mutableSetOf<String>()

    suspend fun refresh(access: GalleryAccess) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (closed.get()) return@withLock
            mutableState.update { it.copy(loading = true) }
            try {
                val saved = store.all()
                val discovered = if (access.canReadLibrary) scanner.screenshots() else emptyList()
                val visible = LinkedHashMap<String, StoredImage>()
                for (image in discovered) visible[image.image.uri] = merge(image, saved[image.image.uri])
                // Picker grants can exist without any broad media permission. They are independent.
                for (manual in saved.values.filter { it.image.manuallyAdded && !it.excluded }) {
                    currentCoroutineContext().ensureActive()
                    val current = runCatching { scanner.readSingle(manual.image.uri.toUri()) }.getOrNull()
                    if (current != null) visible[current.image.uri] = merge(current, manual)
                }
                store.transaction {
                    visible.values.filter { !it.excluded && saved[it.image.uri] != it }.forEach(store::put)
                }
                failedThisPass.clear()
                mutableState.update { it.copy(
                    items = visible.values.filterNot { row -> row.excluded }.map { row -> row.image }.sortedByDescending { image -> image.dateMillis },
                    loading = false, error = null,
                ) }
                startIndexing()
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(loading = false) }
                throw cancelled
            } catch (error: Exception) {
                // Permission revocation must not leave a stale set of cleanup targets onscreen.
                mutableState.update { it.copy(
                    items = if (error is SecurityException) emptyList() else it.items,
                    loading = false,
                    error = if (error is SecurityException) "Gallery access changed. Allow access or choose images again."
                        else "The gallery could not be refreshed. Try again when your storage is available.",
                ) }
            }
        }
    }

    suspend fun addImages(uris: List<Uri>, access: GalleryAccess) = withContext(Dispatchers.IO) {
        var unreadable = 0
        var saveFailed = false
        try {
            mutex.withLock {
                if (closed.get()) return@withLock
                for (original in uris.distinct()) {
                    currentCoroutineContext().ensureActive()
                    val resolved = scanner.resolvePickedUri(original)
                    val row = runCatching { scanner.readSingle(resolved) }.getOrNull()
                        ?: if (resolved != original) runCatching { scanner.readSingle(original) }.getOrNull() else null
                    if (row == null) { unreadable++; continue }
                    val existing = store.get(row.image.uri)
                    store.put(merge(row.copy(image = row.image.copy(manuallyAdded = true)), existing).copy(excluded = false))
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { saveFailed = true }
        refresh(access)
        if (saveFailed) saveError()
        else if (unreadable > 0) mutableState.update { it.copy(error = "$unreadable selected image(s) could not be opened. Try selecting local images from your gallery.") }
    }

    suspend fun setKeep(uris: Set<String>, keep: Boolean) = change(uris) { it.copy(keep = keep) }

    suspend fun setCategory(uris: Set<String>, category: Category) = change(uris) {
        it.copy(category = category, categoryCorrected = true)
    }

    suspend fun removeFromCollection(uris: Set<String>) = withContext(Dispatchers.IO) {
        try {
            mutex.withLock {
                if (closed.get()) return@withLock
                val removed = mutableSetOf<String>()
                store.transaction {
                    for (uri in uris) {
                        val row = store.get(uri) ?: continue
                        if (!row.image.manuallyAdded) continue
                        store.put(row.copy(excluded = true))
                        removed += uri
                    }
                }
                mutableState.update { state -> state.copy(items = state.items.filterNot { it.uri in removed }) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { saveError() }
    }

    fun clearError() { mutableState.update { it.copy(error = null) } }

    private suspend fun change(uris: Set<String>, update: (Screenshot) -> Screenshot) = withContext(Dispatchers.IO) {
        try {
            mutex.withLock {
                if (closed.get()) return@withLock
                store.transaction {
                    for (uri in uris) store.get(uri)?.let { store.put(it.copy(image = update(it.image))) }
                }
                mutableState.update { state -> state.copy(items = state.items.map { if (it.uri in uris) update(it) else it }) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { saveError() }
    }

    private fun saveError() {
        mutableState.update { it.copy(error = "Your change could not be saved. Check available device storage and try again.") }
    }

    private fun merge(fresh: StoredImage, saved: StoredImage?): StoredImage {
        if (saved == null) return fresh
        val changed = fresh.image.sizeBytes != saved.image.sizeBytes || fresh.sourceStamp != saved.sourceStamp
        return fresh.copy(
            image = fresh.image.copy(
                category = if (changed && !saved.image.categoryCorrected) Category.OTHER else saved.image.category,
                categoryCorrected = saved.image.categoryCorrected,
                text = if (changed) "" else saved.image.text, keep = saved.image.keep,
                hash = if (changed) null else saved.image.hash, indexed = !changed && saved.image.indexed,
                manuallyAdded = fresh.image.manuallyAdded || saved.image.manuallyAdded,
            ), excluded = saved.excluded,
        )
    }

    /** The mutex protects only database/state updates, never recognition or reading full originals. */
    private fun startIndexing() {
        if (indexJob?.isActive == true) return
        mutableState.update { it.copy(processing = true, processed = 0, totalToProcess = it.items.count { image -> !image.indexed }) }
        indexJob = scope.launch {
            var completed = 0
            try {
                while (true) {
                    val row = mutex.withLock {
                        val next = mutableState.value.items.firstOrNull { !it.indexed && it.uri !in failedThisPass }
                        if (next == null) {
                            indexJob = null
                            mutableState.update { it.copy(
                                processing = false,
                                error = if (failedThisPass.isNotEmpty())
                                    "Some images could not be indexed. Text search and duplicate results may be incomplete. Refresh to retry."
                                else it.error,
                            ) }
                        }
                        next?.let { store.get(it.uri) }
                    } ?: return@launch
                    val uri = row.image.uri.toUri()
                    var hash = row.image.hash
                    var text: String? = null
                    var failed = false
                    try {
                        if (hash == null) hash = indexer.hash(uri)
                        text = indexer.text(uri)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { failed = true }
                    mutex.withLock {
                        val current = store.get(row.image.uri)
                        // Ignore work for replaced bytes, removed entries, or no-longer-visible images.
                        if (current != null && current.sourceStamp == row.sourceStamp && current.image.sizeBytes == row.image.sizeBytes &&
                            mutableState.value.items.any { it.uri == row.image.uri }) {
                            val updated = current.image.copy(
                                text = text ?: current.image.text, hash = hash,
                                category = if (current.image.categoryCorrected || text == null) current.image.category
                                    else CategoryClassifier.classify(text, current.image.name),
                                indexed = !failed,
                            )
                            store.put(current.copy(image = updated))
                            completed++
                            if (failed) failedThisPass += row.image.uri
                            mutableState.update { state ->
                                val updatedItems = state.items.map { if (it.uri == updated.uri) updated else it }
                                state.copy(
                                    items = updatedItems, processed = completed,
                                    totalToProcess = maxOf(state.totalToProcess, completed + updatedItems.count { !it.indexed && it.uri !in failedThisPass }),
                                )
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(processing = false) }
                throw cancelled
            } catch (_: Exception) {
                mutex.withLock {
                    indexJob = null
                    mutableState.update { it.copy(processing = false, error = "Indexing stopped. Refresh the gallery to retry.") }
                }
            }
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        scope.cancel()
        // Serialize disposal with both the owned indexing worker and externally launched refresh
        // calls. The main thread must never block waiting for a large MediaStore query to finish.
        CoroutineScope(Dispatchers.IO).launch {
            repositoryJob.join()
            mutex.withLock {
                indexer.close()
                store.close()
            }
        }
    }
}
