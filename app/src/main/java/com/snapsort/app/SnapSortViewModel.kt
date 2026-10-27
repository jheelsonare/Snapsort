package com.snapsort.app

import android.app.Application
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.snapsort.app.data.GalleryRepository
import com.snapsort.app.data.TrashOperationStore
import com.snapsort.app.domain.*
import com.snapsort.app.ui.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SnapSortViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = GalleryRepository(application)
    private val controls = MutableStateFlow(AppUiState())
    private val summaryLock = Any()
    @Volatile private var cachedSummary: Pair<List<Screenshot>, LibrarySummary>? = null
    // Filtering/hashing summaries can touch thousands of records. Only publish the derived view
    // on the UI thread; compute it on Default and cancel superseded searches.
    val state: StateFlow<AppUiState> = combine(controls, repository.state) { control, library ->
        control.copy(library = library)
    }.mapLatest { next -> withContext(Dispatchers.Default) { derive(next) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppUiState())
    private var refreshJob: Job? = null
    private val trashJournal = TrashOperationStore(application)
    private val trashMutex = Mutex()
    private var pendingTrash: PendingTrashOperation? = null
    private var journalInitialized = false
    private var journalError = false
    private var restoreCleanupSelection = true
    private val selectionRevision = java.util.concurrent.atomic.AtomicLong(0)
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { refreshDebounced() }
    }

    init {
        application.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        viewModelScope.launch {
            repository.state.mapLatest { library ->
                val snapshot = library.items
                withContext(Dispatchers.Default) { snapshot to snapshot.mapTo(hashSetOf()) { it.uri } }
            }.collect { (snapshot, available) ->
                // Persist pruning in the authoritative selection as well as in the derived view.
                // A restored or re-granted URI must never silently become selected again.
                // Ignore queued snapshots that predate a refresh/recovery. In particular, an
                // initial empty library must not erase a just-recovered selection.
                if (repository.state.value.items === snapshot) {
                    controls.update { it.copy(selected = it.selected.intersect(available)) }
                }
            }
        }
    }

    private fun change(transform: AppUiState.() -> AppUiState) {
        controls.update { control ->
            control.copy(library = repository.state.value, visible = state.value.visible).transform()
        }
    }

    private fun derive(next: AppUiState): AppUiState {
        val summary = summaryFor(next.library.items)
        val selected = next.selected.intersect(summary.existingUris)
        val duplicates = next.collection == ReviewCollection.DUPLICATES
        val candidates = if (duplicates) {
            if (next.includeKept) summary.duplicateMembers else summary.duplicateMembers.filterNot { it.keep }
        } else next.library.items
        return next.copy(
            selected = selected,
            selectedItems = next.library.items.filter { it.uri in selected },
            selectedBytes = CleanupRules.selectedBytes(next.library.items, selected),
            totalBytes = summary.totalBytes,
            duplicateCount = summary.duplicateExtras.size,
            duplicateGroupSizes = if (duplicates) summary.duplicateGroupSizes else emptyMap(),
            visible = CleanupRules.filter(candidates, if (duplicates) ReviewCollection.ALL else next.collection, next.category,
                next.query, next.olderThanDays, next.includeKept, System.currentTimeMillis()),
        )
    }

    private fun summaryFor(items: List<Screenshot>): LibrarySummary {
        cachedSummary?.takeIf { it.first === items }?.let { return it.second }
        return synchronized(summaryLock) {
            cachedSummary?.takeIf { it.first === items }?.second
                ?: LibrarySummary(items).also { cachedSummary = items to it }
        }
    }

    fun refresh(access: GalleryAccess = controls.value.access) {
        change { copy(access = access) }
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            repository.refresh(access)
            reconcileRecoveredCleanup()
        }
    }

    private fun refreshDebounced() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            delay(450)
            repository.refresh(controls.value.access)
            reconcileRecoveredCleanup()
        }
    }

    fun addImages(uris: List<android.net.Uri>) {
        viewModelScope.launch { repository.addImages(uris, controls.value.access) }
    }

    fun addFolder(uri: android.net.Uri) {
        viewModelScope.launch { repository.addFolder(uri, controls.value.access) }
    }

    fun action(action: AppAction) {
        if (controls.value.busyDeleting && action != AppAction.Refresh && action != AppAction.DismissMessage) return
        // Recovery may be reading MediaStore while the first gallery frame is already visible.
        // An explicit user decision must win over a late recovered-selection publication.
        if (action is AppAction.ToggleSelection || action is AppAction.ChangeCollection ||
            action == AppAction.SelectAll || action == AppAction.ClearSelection ||
            action == AppAction.SelectDuplicateExtras || action == AppAction.SelectSupportedGalleryImages ||
            action is AppAction.MarkKeep || action == AppAction.RemoveSelectedFromCollection) {
            selectionRevision.incrementAndGet()
        }
        when (action) {
            AppAction.Refresh -> refresh()
            is AppAction.ChangeCollection -> change { copy(collection = action.value, selected = emptySet()) }
            is AppAction.FilterCategory -> change { copy(category = action.value) }
            is AppAction.Search -> change { copy(query = action.value) }
            is AppAction.ChangeAge -> change { copy(olderThanDays = action.days) }
            is AppAction.IncludeKept -> change { copy(includeKept = action.value) }
            is AppAction.ToggleSelection -> if (!state.value.busyDeleting) change {
                copy(selected = if (action.uri in selected) selected - action.uri else selected + action.uri)
            }
            AppAction.SelectAll -> change {
                copy(selected = selected + visible.filter { includeKept || !it.keep }.map { it.uri })
            }
            AppAction.ClearSelection -> change { copy(selected = emptySet()) }
            AppAction.SelectSupportedGalleryImages -> change {
                val supported = library.items.filter { it.canDelete }.mapTo(hashSetOf()) { it.uri }
                copy(selected = selected.intersect(supported))
            }
            AppAction.SelectDuplicateExtras -> {
                val snapshot = repository.state.value.items
                val visible = state.value.visible.mapTo(hashSetOf()) { it.uri }
                val revision = selectionRevision.get()
                viewModelScope.launch {
                    val extras = withContext(Dispatchers.Default) { summaryFor(snapshot).duplicateExtras.intersect(visible) }
                    if (repository.state.value.items === snapshot && selectionRevision.get() == revision && !controls.value.busyDeleting) change { copy(selected = extras) }
                }
            }
            is AppAction.MarkKeep -> viewModelScope.launch {
                val targets = state.value.selected
                repository.setKeep(targets, action.keep)
                if (repository.state.value.items.filter { it.uri in targets }.all { it.keep == action.keep }) {
                    change { copy(selected = emptySet()) }
                }
            }
            is AppAction.CorrectCategory -> viewModelScope.launch {
                repository.setCategory(state.value.selected, action.category)
            }
            is AppAction.SetItemKeep -> viewModelScope.launch { repository.setKeep(setOf(action.uri), action.keep) }
            is AppAction.SetItemCategory -> viewModelScope.launch { repository.setCategory(setOf(action.uri), action.category) }
            is AppAction.ResetItemCategory -> viewModelScope.launch { repository.resetCategory(setOf(action.uri)) }
            is AppAction.RetryIndex -> viewModelScope.launch { repository.retryIndex(action.uri?.let(::setOf)) }
            is AppAction.ExcludeItem -> viewModelScope.launch { repository.excludeImages(setOf(action.uri)) }
            AppAction.RestoreExcluded -> viewModelScope.launch { repository.restoreExcluded(); refresh() }
            AppAction.DiscardRecoveredTrash -> viewModelScope.launch {
                trashMutex.withLock {
                    try {
                        withContext(Dispatchers.IO) { trashJournal.write(null) }
                        pendingTrash = null; journalError = false; journalInitialized = true
                        restoreCleanupSelection = false
                        change { copy(cleanupRecoveryPending = false, selected = emptySet(), message = "Cleanup checkpoint cleared. No originals were changed.") }
                    } catch (_: Exception) { notify("The cleanup checkpoint could not be cleared. Check device storage and retry.") }
                }
            }
            AppAction.RemoveSelectedFromCollection -> viewModelScope.launch {
                val targets = state.value.selected
                repository.removeFromCollection(targets)
                if (repository.state.value.items.none { it.uri in targets }) {
                    change { copy(selected = emptySet(), message = "Removed added images from SnapSort. Originals remain on your phone.") }
                }
            }
            AppAction.DismissMessage -> { repository.clearError(); change { copy(message = null) } }
            else -> Unit // Permission, picker, and Android confirmation are hosted by the activity.
        }
    }

    suspend fun beginTrash(): List<String>? = trashMutex.withLock {
        initializeJournalLocked()
        if (controls.value.busyDeleting) return@withLock null
        if (journalError) {
            notify("Clear the unreadable cleanup checkpoint before reviewing a new request.")
            return@withLock null
        }
        // Use the authoritative current selection/library, not a potentially stale derived frame.
        val selected = repository.state.value.items.filter { it.uri in controls.value.selected }
        if (selected.isEmpty()) return@withLock null
        if (selected.any { !it.canDelete }) {
            notify("Some added images cannot be trashed through Android's gallery API. Remove those from this selection first.")
            return@withLock null
        }
        change { copy(busyDeleting = true) }
        val previous = pendingTrash
        val previousTargets = previous?.targets?.toHashSet().orEmpty()
        val reviewed = PendingTrashOperation.reviewed(selected.map { it.uri }).copy(
            confirmedBatches = if (previous != null && selected.all { it.uri in previousTargets }) previous.confirmedBatches else 0,
        )
        pendingTrash = reviewed
        try {
            withContext(Dispatchers.IO) { trashJournal.write(reviewed) }
            restoreCleanupSelection = false
            change { copy(cleanupRecoveryPending = false) }
            reviewed.inFlight
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            // Rotation can cancel the Activity coroutine after the durable write, before launch.
            // Preserve that checkpoint as review-only, including in this retained ViewModel.
            pendingTrash = reviewed.interrupted()
            restoreCleanupSelection = true
            try {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { trashJournal.write(pendingTrash) }
            } catch (_: Exception) { journalError = true }
            change { copy(busyDeleting = false, cleanupRecoveryPending = true) }
            throw cancelled
        } catch (_: Exception) {
            pendingTrash = previous
            change { copy(busyDeleting = false, message = "Cleanup could not be saved. No Android request was started. Check device storage and retry.") }
            null
        }
    }

    suspend fun trashResult(confirmed: Boolean) = trashMutex.withLock {
        initializeJournalLocked()
        change { copy(busyDeleting = true) }
        try {
            repository.refresh(controls.value.access)
            val operation = pendingTrash
            if (operation != null) {
                val observed = withContext(Dispatchers.IO) { trashJournal.observe(operation.targets) }
                val remaining = if (confirmed) operation.reconcile(observed.trashed, confirmed = true) else null
                withContext(Dispatchers.IO) { trashJournal.write(remaining) }
                pendingTrash = remaining
                val visible = repository.state.value.items.mapTo(hashSetOf()) { it.uri }
                restoreCleanupSelection = false
                change { copy(selected = if (selectionRevision.get() == 0L) observed.present.intersect(visible) else selected.intersect(visible),
                    cleanupRecoveryPending = remaining != null) }
            }
            change { copy(busyDeleting = false, message = when {
                !confirmed -> "Trash request cancelled. Remaining accessible images stay selected."
                pendingTrash != null -> "Cleanup paused. Review remaining accessible images before another Android request. Unavailable images are not assumed deleted."
                else -> "Android finished the trash request. The collection is updated. Trash may retain storage until emptied."
            }) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) {
            journalError = true
            change { copy(busyDeleting = false, cleanupRecoveryPending = true, message = "Cleanup needs reconciliation. Reopen or refresh before reviewing another request.") }
        }
    }

    fun onTrashResult(confirmed: Boolean) { viewModelScope.launch { trashResult(confirmed) } }
    fun onTrashFailed(message: String) { viewModelScope.launch { trashFailed(message) } }

    suspend fun trashFailed(message: String) = trashMutex.withLock {
        initializeJournalLocked()
        pendingTrash = pendingTrash?.interrupted()
        try { withContext(Dispatchers.IO) { trashJournal.write(pendingTrash) } }
        catch (_: Exception) { journalError = true }
        change { copy(busyDeleting = false, cleanupRecoveryPending = pendingTrash != null || journalError, message = message) }
        refresh()
    }

    private suspend fun initializeJournalLocked() {
        if (journalInitialized) return
        try {
            pendingTrash = withContext(Dispatchers.IO) { trashJournal.read()?.interrupted().also { if (it != null) trashJournal.write(it) } }
            journalInitialized = true
            if (pendingTrash != null) change { copy(cleanupRecoveryPending = true,
                message = "An interrupted cleanup was found. Review remaining accessible images before continuing. Nothing resumes automatically.") }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) {
            journalInitialized = true
            journalError = true
            change { copy(cleanupRecoveryPending = true, message = "The cleanup checkpoint could not be read. Clear the checkpoint, then review a new selection. No request will resume automatically.") }
        }
    }

    private suspend fun reconcileRecoveredCleanup() = trashMutex.withLock {
        initializeJournalLocked()
        if (controls.value.busyDeleting || !restoreCleanupSelection || journalError) return@withLock
        val operation = pendingTrash ?: return@withLock
        if (!controls.value.access.canReadLibrary && repository.state.value.items.isEmpty()) return@withLock
        val observed = withContext(Dispatchers.IO) { trashJournal.observe(operation.targets) }
        val remaining = operation.reconcile(observed.trashed)
        try {
            withContext(Dispatchers.IO) { trashJournal.write(remaining) }
            pendingTrash = remaining
            restoreCleanupSelection = false
            val visible = repository.state.value.items.mapTo(hashSetOf()) { it.uri }
            change { copy(selected = if (selectionRevision.get() == 0L) observed.present.intersect(visible) else selected.intersect(visible),
                cleanupRecoveryPending = remaining != null) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) {
            journalError = true
            notify("The cleanup checkpoint could not be saved. Clear it before starting a new request.")
        }
    }

    fun notify(message: String) { change { copy(message = message) } }

    override fun onCleared() {
        getApplication<Application>().contentResolver.unregisterContentObserver(observer)
        repository.close()
        super.onCleared()
    }
}
