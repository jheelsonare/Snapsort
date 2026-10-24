package com.snapsort.app

import android.app.Application
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.snapsort.app.data.GalleryRepository
import com.snapsort.app.domain.*
import com.snapsort.app.ui.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SnapSortViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = GalleryRepository(application)
    private val controls = MutableStateFlow(AppUiState())
    // Filtering/hashing summaries can touch thousands of records. Only publish the derived view
    // on the UI thread; compute it on Default and cancel superseded searches.
    val state: StateFlow<AppUiState> = combine(controls, repository.state) { control, library ->
        control.copy(library = library)
    }.mapLatest { next -> withContext(Dispatchers.Default) { derive(next) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppUiState())
    private var refreshJob: Job? = null
    private val trashQueue = ArrayDeque<List<String>>()
    private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { refreshDebounced() }
    }

    init {
        application.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        viewModelScope.launch {
            repository.state.map { library -> library.items.mapTo(hashSetOf()) { it.uri } }
                .distinctUntilChanged().flowOn(Dispatchers.Default).collect { available ->
                // Persist pruning in the authoritative selection as well as in the derived view.
                // A restored or re-granted URI must never silently become selected again.
                controls.update { it.copy(selected = it.selected.intersect(available)) }
            }
        }
    }

    private fun change(transform: AppUiState.() -> AppUiState) {
        controls.update { control ->
            control.copy(library = repository.state.value, visible = state.value.visible).transform()
        }
    }

    private fun derive(next: AppUiState): AppUiState {
        val existing = next.library.items.mapTo(hashSetOf()) { it.uri }
        val selected = next.selected.intersect(existing)
        return next.copy(
            selected = selected,
            selectedItems = next.library.items.filter { it.uri in selected },
            selectedBytes = CleanupRules.selectedBytes(next.library.items, selected),
            totalBytes = next.library.items.sumOf { it.sizeBytes.coerceAtLeast(0) },
            duplicateCount = CleanupRules.selectDuplicatesToRemove(next.library.items).size,
            duplicateGroupSizes = if (next.collection == ReviewCollection.DUPLICATES)
                next.library.items.filter { it.canDelete && !it.hash.isNullOrBlank() }
                    .groupingBy { it.hash to it.sizeBytes }.eachCount() else emptyMap(),
            visible = CleanupRules.filter(next.library.items, next.collection, next.category,
                next.query, next.olderThanDays, next.includeKept, System.currentTimeMillis()),
        )
    }

    fun refresh(access: GalleryAccess = controls.value.access) {
        change { copy(access = access) }
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch { repository.refresh(access) }
    }

    private fun refreshDebounced() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            delay(450)
            repository.refresh(controls.value.access)
        }
    }

    fun addImages(uris: List<android.net.Uri>) {
        viewModelScope.launch { repository.addImages(uris, controls.value.access) }
    }

    fun action(action: AppAction) {
        if (controls.value.busyDeleting && action != AppAction.Refresh && action != AppAction.DismissMessage) return
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
            AppAction.SelectDuplicateExtras -> change {
                copy(selected = CleanupRules.selectDuplicatesToRemove(library.items, visible.mapTo(hashSetOf()) { it.uri }))
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

    fun beginTrash(): List<String>? {
        if (controls.value.busyDeleting) return null
        val selected = state.value.selectedItems
        if (selected.isEmpty()) return null
        if (selected.any { !it.canDelete }) {
            notify("Some added images cannot be trashed through Android's gallery API. Remove those from this selection first.")
            return null
        }
        trashQueue.clear()
        selected.map { it.uri }.chunked(2000).forEach(trashQueue::addLast)
        change { copy(busyDeleting = true) }
        return trashQueue.removeFirstOrNull()
    }

    fun trashResult(confirmed: Boolean): List<String>? {
        refresh()
        if (confirmed && trashQueue.isNotEmpty()) return trashQueue.removeFirst()
        trashQueue.clear()
        change { copy(busyDeleting = false, message = if (confirmed)
            "Android finished the trash request. Refreshing the collection. Trash may retain storage until emptied."
        else "Trash request cancelled. Refreshing; remaining images stay selected.") }
        return null
    }

    fun trashFailed(message: String) {
        trashQueue.clear()
        change { copy(busyDeleting = false, message = message) }
        refresh()
    }

    fun notify(message: String) { change { copy(message = message) } }

    override fun onCleared() {
        getApplication<Application>().contentResolver.unregisterContentObserver(observer)
        repository.close()
        super.onCleared()
    }
}
