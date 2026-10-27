package com.snapsort.app.data

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.snapsort.app.domain.Category
import com.snapsort.app.domain.CategoryClassifier
import com.snapsort.app.domain.GalleryAccess
import com.snapsort.app.domain.LibraryState
import com.snapsort.app.domain.Screenshot
import com.snapsort.app.domain.IndexStatus
import android.os.SystemClock
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
class GalleryRepository(context: Context, databaseName: String = "snapsort.db", imageOperations: ImageOperations? = null) {
    private val appContext = context.applicationContext
    private val store = MetadataStore(appContext, databaseName)
    private val scanner = GalleryScanner(appContext)
    private val indexer = imageOperations ?: object : ImageOperations {
        private val delegate = ImageIndexer(appContext.contentResolver)
        override suspend fun hash(uri: Uri) = delegate.hash(uri)
        override suspend fun text(uri: Uri) = delegate.text(uri)
        override fun close() = delegate.close()
    }
    private val repositoryJob = SupervisorJob()
    private val scope = CoroutineScope(repositoryJob + Dispatchers.IO)
    private val closed = AtomicBoolean(false)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(LibraryState())
    val state: StateFlow<LibraryState> = mutableState.asStateFlow()
    private var indexJob: Job? = null
    private val indexQueue = ArrayDeque<String>()
    private val queued = hashSetOf<String>()
    private val pendingPublish = hashSetOf<String>()
    private var visibleUris = emptySet<String>()
    private var completed = 0
    private var lastPublish = 0L

    suspend fun refresh(access: GalleryAccess) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (closed.get()) return@withLock
            mutableState.update { it.copy(loading = true) }
            try {
                val discovered = if (access.canReadLibrary) scanner.screenshots() else emptyList()
                var folderFailed = false
                // Persist each scanned chunk. Tree images remain references to the originals.
                for (folder in store.folders()) {
                    try {
                        val chunk = ArrayList<StoredImage>(64)
                        suspend fun saveChunk() {
                            if (chunk.isEmpty()) return
                            val previous = store.relevant(chunk.map { it.image.uri }, includeManual = false)
                            store.transaction { chunk.forEach { store.put(merge(it, previous[it.image.uri])) } }
                            chunk.clear()
                        }
                        scanner.folderImages(folder.toUri()) { row ->
                            chunk += row
                            if (chunk.size >= 64) saveChunk()
                        }
                        saveChunk()
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { folderFailed = true }
                }
                val saved = store.relevant(discovered.map { it.image.uri })
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
                visibleUris = visible.values.filterNot { it.excluded }.mapTo(hashSetOf()) { it.image.uri }
                mutableState.update { it.copy(
                    items = visible.values.filterNot { row -> row.excluded }.map { row -> row.image }.sortedByDescending { image -> image.dateMillis },
                    loading = false, error = if (folderFailed) "A selected folder is unavailable. Choose it again to restore access." else null,
                ) }
                startIndexing()
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(loading = false) }
                throw cancelled
            } catch (error: Exception) {
                // Permission revocation must not leave a stale set of cleanup targets onscreen.
                if (error is SecurityException) visibleUris = emptySet()
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

    suspend fun resetCategory(uris: Set<String>) = change(uris) {
        it.copy(category = CategoryClassifier.classify(it.text, it.name), categoryCorrected = false)
    }

    suspend fun retryIndex(uris: Set<String>? = null) {
        val targets = uris ?: state.value.items.filter { it.hasIndexFailure }.mapTo(hashSetOf()) { it.uri }
        change(targets) { image -> image.copy(
            hashStatus = if (image.hashStatus == IndexStatus.FAILED) IndexStatus.PENDING else image.hashStatus,
            ocrStatus = if (image.ocrStatus == IndexStatus.FAILED) IndexStatus.PENDING else image.ocrStatus,
            hashError = if (image.hashStatus == IndexStatus.FAILED) null else image.hashError,
            ocrError = if (image.ocrStatus == IndexStatus.FAILED) null else image.ocrError,
        ) }
        withContext(Dispatchers.IO) { mutex.withLock { if (!closed.get()) startIndexing() } }
    }

    suspend fun addFolder(uri: Uri, access: GalleryAccess) = withContext(Dispatchers.IO) {
        try { mutex.withLock { if (!closed.get()) store.addFolder(uri.toString()) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { saveError(); return@withContext }
        refresh(access)
    }

    suspend fun restoreExcluded() = withContext(Dispatchers.IO) {
        try { mutex.withLock { if (!closed.get()) store.restoreExcluded() } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { saveError() }
    }

    /** Explicit exclusion is independent of heuristic discovery; it never deletes gallery bytes. */
    suspend fun excludeImages(uris: Set<String>) = exclude(uris, manualOnly = false)

    suspend fun removeFromCollection(uris: Set<String>) = exclude(uris, manualOnly = true)

    private suspend fun exclude(uris: Set<String>, manualOnly: Boolean) = withContext(Dispatchers.IO) {
        try {
            mutex.withLock {
                if (closed.get()) return@withLock
                val removed = mutableSetOf<String>()
                store.transaction {
                    for (uri in uris) {
                        val row = store.get(uri) ?: continue
                        if (manualOnly && !row.image.manuallyAdded) continue
                        store.put(row.copy(excluded = true))
                        removed += uri
                    }
                }
                visibleUris = visibleUris - removed
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
                val changed = linkedMapOf<String, Screenshot>()
                store.transaction {
                    for (uri in uris) store.get(uri)?.let {
                        val row = it.copy(image = update(it.image))
                        store.put(row)
                        changed[uri] = row.image
                    }
                }
                mutableState.update { state -> state.copy(items = state.items.map { changed[it.uri] ?: it }) }
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
                hashStatus = if (changed) IndexStatus.PENDING else saved.image.hashStatus,
                ocrStatus = if (changed) IndexStatus.PENDING else saved.image.ocrStatus,
                hashError = if (changed) null else saved.image.hashError,
                ocrError = if (changed) null else saved.image.ocrError,
            ), excluded = saved.excluded,
        )
    }

    /** The mutex protects database/state updates, never recognition or full-original reads. */
    private fun startIndexing() {
        for (image in mutableState.value.items) {
            if (image.needsIndexing && queued.add(image.uri)) indexQueue.addLast(image.uri)
        }
        if (indexJob?.isActive == true || indexQueue.isEmpty()) return
        completed = 0
        lastPublish = SystemClock.elapsedRealtime()
        mutableState.update { it.copy(processing = true, processed = 0, totalToProcess = indexQueue.size) }
        indexJob = scope.launch {
            try {
                while (true) {
                    val row = mutex.withLock {
                        var next: StoredImage? = null
                        while (indexQueue.isNotEmpty() && next == null) {
                            val uri = indexQueue.removeFirst()
                            queued.remove(uri)
                            if (uri in visibleUris) {
                                next = store.get(uri)?.takeIf { it.image.needsIndexing }
                            }
                        }
                        if (next == null) {
                            publishIndexUpdates(finished = true)
                            indexJob = null
                        }
                        next?.also {
                            store.put(it.copy(image = it.image.copy(
                                hashStatus = if (it.image.hashStatus == IndexStatus.PENDING) IndexStatus.RUNNING else it.image.hashStatus,
                                ocrStatus = if (it.image.ocrStatus == IndexStatus.PENDING) IndexStatus.RUNNING else it.image.ocrStatus,
                            )))
                        }
                    } ?: return@launch
                    var hash = row.image.hash
                    var text = row.image.text
                    var hashStatus = row.image.hashStatus
                    var ocrStatus = row.image.ocrStatus
                    var hashError = row.image.hashError
                    var ocrError = row.image.ocrError
                    if (hashStatus == IndexStatus.PENDING) {
                        try { hash = indexer.hash(row.image.uri.toUri()); hashStatus = IndexStatus.SUCCEEDED; hashError = null }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { hashStatus = IndexStatus.FAILED; hashError = errorReason(error, "file hash") }
                    }
                    if (ocrStatus == IndexStatus.PENDING) {
                        try { text = indexer.text(row.image.uri.toUri()); ocrStatus = IndexStatus.SUCCEEDED; ocrError = null }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (error: Exception) { ocrStatus = IndexStatus.FAILED; ocrError = errorReason(error, "text recognition") }
                    }
                    mutex.withLock {
                        val current = store.get(row.image.uri)
                        if (current != null && current.sourceStamp == row.sourceStamp && current.image.sizeBytes == row.image.sizeBytes &&
                            row.image.uri in visibleUris) {
                            val updated = current.image.copy(
                                text = text, hash = hash, hashStatus = hashStatus, ocrStatus = ocrStatus,
                                hashError = hashError, ocrError = ocrError,
                                category = if (current.image.categoryCorrected || ocrStatus != IndexStatus.SUCCEEDED) current.image.category
                                    else CategoryClassifier.classify(text, current.image.name),
                                indexed = hashStatus == IndexStatus.SUCCEEDED && ocrStatus == IndexStatus.SUCCEEDED,
                            )
                            store.put(current.copy(image = updated))
                            pendingPublish += updated.uri
                            completed++
                            // Publish at most once per chunk/time window instead of once per image.
                            if (pendingPublish.size >= 8 || SystemClock.elapsedRealtime() - lastPublish >= 250) publishIndexUpdates()
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(processing = false) }
                throw cancelled
            } catch (_: Exception) {
                mutex.withLock {
                    indexJob = null
                    publishIndexUpdates(finished = true)
                    mutableState.update { it.copy(error = "Indexing stopped. Reopen SnapSort to resume interrupted work.") }
                }
            }
        }
    }

    private fun publishIndexUpdates(finished: Boolean = false) {
        val changes = pendingPublish.mapNotNull { store.get(it)?.image }.associateBy { it.uri }
        pendingPublish.clear()
        lastPublish = SystemClock.elapsedRealtime()
        mutableState.update { state -> state.copy(
            items = if (changes.isEmpty()) state.items else state.items.map { changes[it.uri] ?: it },
            processed = completed, totalToProcess = maxOf(state.totalToProcess, completed + indexQueue.size),
            processing = !finished,
        ) }
    }

    private fun errorReason(error: Exception, operation: String): String = when (error) {
        is SecurityException -> "Access denied. Allow this image again, then retry $operation."
        is java.io.FileNotFoundException -> "Original unavailable. Check storage or select the image again, then retry."
        is java.io.IOException -> "Image could not be read for $operation. Check the original, then retry."
        else -> "Could not complete $operation on this device. Retry when the image is available."
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
