package com.snapsort.app.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.snapsort.app.domain.Category
import com.snapsort.app.domain.ReviewCollection
import com.snapsort.app.domain.Screenshot
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnapSortApp(state: AppUiState, onAction: (AppAction) -> Unit) {
    var reviewOpen by remember { mutableStateOf(false) }
    var previewUri by remember { mutableStateOf<String?>(null) }
    val preview = state.library.items.firstOrNull { it.uri == previewUri }
    SnapSortTheme {
        Scaffold(
            topBar = { BrandBar(state, onAction) },
            bottomBar = {
                if (state.selected.isNotEmpty()) {
                    SelectionBar(state, onAction) { reviewOpen = true }
                }
            },
        ) { padding ->
            LazyVerticalGrid(
                columns = GridCells.Adaptive(148.dp),
                contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize().padding(padding).testTag("screenshot-grid"),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) { Overview(state) }
                if (!state.access.canReadLibrary || state.access.limited) {
                    item(span = { GridItemSpan(maxLineSpan) }) { AccessCard(state, onAction) }
                }
                val notice = state.library.error ?: state.message
                if (notice != null) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Notice(notice) { onAction(AppAction.DismissMessage) }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) { Collections(state, onAction) }
                item(span = { GridItemSpan(maxLineSpan) }) { LibraryControls(state, onAction) }
                if (state.library.processing || state.library.loading) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = Lime)
                            Text(
                                if (state.library.loading) "Finding your screenshots…" else
                                    "Reading on device · ${state.library.processed} of ${state.library.totalToProcess}",
                                style = MaterialTheme.typography.labelMedium,
                                color = Muted,
                            )
                        }
                    }
                }
                if (state.visible.isEmpty() && !state.library.loading) {
                    item(span = { GridItemSpan(maxLineSpan) }) { EmptyLibrary(state, onAction) }
                }
                if (state.collection == ReviewCollection.DUPLICATES) {
                    val groups = state.visible.groupBy { it.hash to it.sizeBytes }.entries.toList()
                    groups.forEachIndexed { index, group ->
                        val total = state.duplicateGroupSizes[group.key] ?: group.value.size
                        item(key = "group-${group.key}", span = { GridItemSpan(maxLineSpan) }) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("Identical group ${index + 1}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text("$total identical files · ${group.value.size} shown · ${formatBytes(group.key.second)} each",
                                    style = MaterialTheme.typography.bodySmall, color = Muted)
                            }
                        }
                        items(group.value, key = { it.uri }) { shot ->
                            ScreenshotTile(shot, shot.uri in state.selected,
                                { onAction(AppAction.ToggleSelection(shot.uri)) }, { previewUri = shot.uri })
                        }
                    }
                } else {
                    items(state.visible, key = { it.uri }) { shot ->
                        ScreenshotTile(
                            shot = shot,
                            selected = shot.uri in state.selected,
                            select = { onAction(AppAction.ToggleSelection(shot.uri)) },
                            preview = { previewUri = shot.uri },
                        )
                    }
                }
                if (state.visible.isNotEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                            Icon(Icons.Outlined.VerifiedUser, null, tint = Muted, modifier = Modifier.size(16.dp))
                            Text("Your originals stay in your gallery. Nothing is deleted automatically.", color = Muted,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
        if (reviewOpen && state.selectedItems.isNotEmpty()) {
            ReviewSheet(state, onDismiss = { reviewOpen = false }, onConfirm = {
                reviewOpen = false
                onAction(AppAction.DeleteSelected)
            }, onRemove = {
                reviewOpen = false
                onAction(AppAction.RemoveSelectedFromCollection)
            })
        }
        if (preview != null) {
            PreviewSheet(preview, preview.uri in state.selected, { previewUri = null }, {
                onAction(AppAction.ToggleSelection(preview.uri))
            }, onAction)
        }
    }
}

@Composable
private fun BrandBar(state: AppUiState, onAction: (AppAction) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Lime), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.CropFree, null, tint = Charcoal, modifier = Modifier.size(25.dp))
        }
        Text("SnapSort", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 10.dp).weight(1f))
        IconButton(onClick = { onAction(AppAction.AddImages) }) {
            Icon(Icons.Outlined.AddPhotoAlternate, "Select additional images")
        }
        IconButton(onClick = { onAction(AppAction.Refresh) }, enabled = !state.library.loading) {
            Icon(Icons.Outlined.Refresh, "Refresh gallery")
        }
        IconButton(onClick = { onAction(AppAction.ManageAccess) }) {
            Icon(Icons.Outlined.Settings, "Gallery access settings")
        }
    }
}

@Composable
private fun Overview(state: AppUiState) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Panel).padding(22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            Box(Modifier.size(6.dp).background(Lime, CircleShape))
            Text("A CLEARER CAMERA ROLL", color = Lime, style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold)
        }
        Text("Keep the useful.\nClear the clutter.", style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp, bottom = 18.dp))
        HorizontalDivider(color = Color.White.copy(alpha = .09f))
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${state.library.items.size}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("screenshots", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            Column(Modifier.weight(1f)) {
                Text(formatBytes(state.totalBytes), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("in your gallery", color = Muted, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Outlined.PhotoLibrary, null, tint = Lime, modifier = Modifier.size(34.dp))
        }
    }
}

@Composable
private fun AccessCard(state: AppUiState, onAction: (AppAction) -> Unit) {
    OutlinedCard(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Lock, null, tint = Lime)
                Text(if (state.access.limited) "You're sharing selected photos" else "Your screenshots, organised privately",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(if (state.access.limited)
                "Only images Android has granted access to can appear here. Add more images or change access in settings."
                else "Allow photo access to find screenshots already on your phone. Text is read on this device, with no account or uploads.",
                style = MaterialTheme.typography.bodyMedium, color = Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { onAction(AppAction.RequestAccess) }) {
                    Text(if (state.access.limited) "Choose more" else "Allow access")
                }
                TextButton(onClick = { onAction(AppAction.AddImages) }) { Text("Select images") }
            }
            if (!state.access.canReadLibrary) {
                TextButton(onClick = { onAction(AppAction.ManageAccess) }, contentPadding = PaddingValues(0.dp)) {
                    Text("Already denied? Open app settings")
                }
            }
        }
    }
}

@Composable
private fun Collections(state: AppUiState, onAction: (AppAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Make a little room", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            Text("REVIEW FIRST", color = Muted, style = MaterialTheme.typography.labelSmall)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CollectionCard(ReviewCollection.DUPLICATES, "${state.duplicateCount} extra copies", Icons.Outlined.CopyAll,
                state.collection, Modifier.weight(1f), onAction)
            CollectionCard(ReviewCollection.OLDER, "Choose an age", Icons.Outlined.History,
                state.collection, Modifier.weight(1f), onAction)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            CollectionCard(ReviewCollection.LARGE, "Largest files first", Icons.Outlined.Storage,
                state.collection, Modifier.weight(1f), onAction)
            CollectionCard(ReviewCollection.ALL, "${state.library.items.size} in your library", Icons.Outlined.GridView,
                state.collection, Modifier.weight(1f), onAction)
        }
    }
}

@Composable
private fun CollectionCard(collection: ReviewCollection, subtitle: String, icon: ImageVector,
    active: ReviewCollection, modifier: Modifier, onAction: (AppAction) -> Unit) {
    val selected = active == collection
    Card(
        onClick = { onAction(AppAction.ChangeCollection(collection)) },
        modifier = modifier.heightIn(min = 140.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = if (selected) Lime else Panel),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val foreground = if (selected) Charcoal else MaterialTheme.colorScheme.onSurface
            Icon(icon, null, tint = foreground, modifier = Modifier.padding(bottom = 6.dp))
            Text(collection.label, color = foreground, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(subtitle, color = foreground.copy(alpha = .7f), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun LibraryControls(state: AppUiState, onAction: (AppAction) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = state.query, onValueChange = { onAction(AppAction.Search(it)) },
            placeholder = { Text("Search screenshots & text") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) IconButton(onClick = { onAction(AppAction.Search("")) }) {
                    Icon(Icons.Outlined.Close, "Clear search")
                }
            },
            modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp),
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { FilterChip(selected = state.category == null, onClick = { onAction(AppAction.FilterCategory(null)) },
                label = { Text("All categories") }) }
            items(Category.entries) { category ->
                FilterChip(selected = state.category == category,
                    onClick = { onAction(AppAction.FilterCategory(if (state.category == category) null else category)) },
                    label = { Text(category.label) })
            }
        }
        if (state.collection == ReviewCollection.OLDER) {
            Text("Show screenshots older than", style = MaterialTheme.typography.bodySmall, color = Muted)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf(30, 90, 180, 365)) { days ->
                    FilterChip(selected = state.olderThanDays == days, onClick = { onAction(AppAction.ChangeAge(days)) },
                        label = { Text(if (days == 365) "1 year" else "$days days") })
                }
            }
            Text("Age is a review signal. Older screenshots can still be useful.", style = MaterialTheme.typography.bodySmall, color = Muted)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Include Keep in bulk actions", style = MaterialTheme.typography.bodyMedium)
                Text(if (state.includeKept) "Kept screenshots may be selected in bulk" else "Your Keep choices are protected from bulk suggestions",
                    style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            Switch(checked = state.includeKept, onCheckedChange = { onAction(AppAction.IncludeKept(it)) },
                modifier = Modifier.semantics { contentDescription = "Include Keep in bulk actions" })
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(state.collection.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("${state.visible.size} shown · ${if (state.collection == ReviewCollection.LARGE) "size ↓" else "newest first"}",
                    style = MaterialTheme.typography.labelSmall, color = Muted)
            }
            TextButton(onClick = { onAction(AppAction.SelectAll) }, enabled = state.visible.isNotEmpty()) { Text("Select all") }
        }
        if (state.collection == ReviewCollection.DUPLICATES && state.visible.isNotEmpty()) {
            OutlinedButton(onClick = { onAction(AppAction.SelectDuplicateExtras) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.CopyAll, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Select extra copies · keep one")
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScreenshotTile(shot: Screenshot, selected: Boolean, select: () -> Unit, preview: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(16.dp)).background(Panel)
        .border(if (selected) 2.dp else 0.dp, if (selected) Lime else Color.Transparent, RoundedCornerShape(16.dp))) {
        Box(Modifier.fillMaxWidth().aspectRatio(.82f).clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
            .background(Color(0xFF30382E)).combinedClickable(onClick = preview, onLongClick = select)) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(shot.uri).size(480).crossfade(true).build(),
                contentDescription = "Preview ${shot.name}",
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
            )
            Checkbox(checked = selected, onCheckedChange = { select() },
                modifier = Modifier.align(Alignment.TopEnd).padding(2.dp).clip(CircleShape)
                    .background(Charcoal.copy(alpha = .76f)).semantics { contentDescription = "Select ${shot.name}" })
            if (shot.keep) {
                Row(Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(8.dp)).background(Lime)
                    .padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Icon(Icons.Outlined.Bookmark, null, tint = Charcoal, modifier = Modifier.size(13.dp))
                    Text("Keep", color = Charcoal, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
        }
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(shot.category.label, style = MaterialTheme.typography.labelMedium, color = Lime)
            Text(formatDate(shot.dateMillis), style = MaterialTheme.typography.bodySmall, maxLines = 1)
            Text(formatBytes(shot.sizeBytes), style = MaterialTheme.typography.labelSmall, color = Muted)
        }
    }
}

@Composable
private fun EmptyLibrary(state: AppUiState, onAction: (AppAction) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 36.dp, horizontal = 18.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(72.dp).background(Panel, CircleShape), contentAlignment = Alignment.Center) {
            Icon(if (state.query.isNotEmpty()) Icons.Outlined.SearchOff else Icons.Outlined.PhotoLibrary,
                null, tint = Lime, modifier = Modifier.size(30.dp))
        }
        Text(if (state.library.items.isEmpty()) "A fresh start for your screenshots" else "Nothing in this view",
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(if (state.library.items.isEmpty()) "Give SnapSort access or select images to begin. Screenshots are found by folder and filename; you can add any that were missed."
            else "Try another category, search, or age. Kept screenshots are excluded from cleanup suggestions by default.",
            color = Muted, style = MaterialTheme.typography.bodyMedium)
        if (state.library.items.isEmpty()) {
            FilledTonalButton(onClick = { onAction(AppAction.AddImages) }) {
                Icon(Icons.Outlined.AddPhotoAlternate, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Select images")
            }
        }
    }
}

@Composable
private fun Notice(message: String, dismiss: () -> Unit) {
    Surface(color = Panel, shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Info, null, tint = Lime, modifier = Modifier.size(19.dp))
            Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(10.dp))
            IconButton(onClick = dismiss) { Icon(Icons.Outlined.Close, "Dismiss message") }
        }
    }
}

@Composable
private fun SelectionBar(state: AppUiState, onAction: (AppAction) -> Unit, review: () -> Unit) {
    var categoryMenu by remember { mutableStateOf(false) }
    Surface(color = Panel, shadowElevation = 12.dp) {
        Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 20.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${state.selected.size} selected", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Up to ${formatBytes(state.selectedBytes)} to free", color = Lime, style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { onAction(AppAction.ClearSelection) }) { Text("Deselect") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val allKept = state.selectedItems.all { it.keep }
                IconButton(onClick = { onAction(AppAction.MarkKeep(!allKept)) }) {
                    Icon(if (allKept) Icons.Outlined.BookmarkRemove else Icons.Outlined.BookmarkAdd,
                        if (allKept) "Unmark Keep for selected screenshots" else "Mark selected screenshots as Keep")
                }
                Box {
                    IconButton(onClick = { categoryMenu = true }) { Icon(Icons.AutoMirrored.Outlined.Label, "Change category for selection") }
                    DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                        Category.entries.forEach { category ->
                            DropdownMenuItem(text = { Text(category.label) }, onClick = {
                                categoryMenu = false
                                onAction(AppAction.CorrectCategory(category))
                            })
                        }
                    }
                }
                Button(onClick = review, enabled = !state.busyDeleting, modifier = Modifier.weight(1f)) {
                    if (state.busyDeleting) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(if (state.busyDeleting) "Updating…" else "Review selection")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewSheet(state: AppUiState, onDismiss: () -> Unit, onConfirm: () -> Unit, onRemove: () -> Unit) {
    val canDelete = state.selectedItems.all { it.canDelete }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("One last look", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text("${state.selectedItems.size} screenshots · up to ${formatBytes(state.selectedBytes)}",
                color = Lime, style = MaterialTheme.typography.titleMedium)
            Text("This moves the original gallery images to Android's trash after system confirmation. Storage may not be freed until the trash is emptied. Recover them from your gallery's trash where available.",
                color = Muted, style = MaterialTheme.typography.bodyMedium)
            if (state.selectedItems.any { it.keep }) {
                Text("Your selection includes screenshots marked Keep. Review these carefully.", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium)
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 280.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.selectedItems, key = { it.uri }) { shot ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        AsyncImage(shot.uri, contentDescription = "Selected ${shot.name}", contentScale = ContentScale.Crop,
                            modifier = Modifier.size(58.dp).clip(RoundedCornerShape(8.dp)).background(Panel))
                        Column(Modifier.weight(1f)) {
                            Text(shot.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${formatDate(shot.dateMillis)} · ${formatBytes(shot.sizeBytes)}${if (shot.keep) " · Keep" else ""}",
                                color = Muted, style = MaterialTheme.typography.bodySmall)
                            if (!shot.canDelete) Text("Manage this original in its image provider",
                                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
            if (!canDelete) {
                Text("Some selected images are shared by a document provider. Android doesn't expose a supported gallery deletion flow for them here. Delete those originals in your gallery or select only supported gallery images.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Button(onClick = onConfirm, enabled = canDelete && !state.busyDeleting, modifier = Modifier.fillMaxWidth()) {
                Text("Continue to Android · move to trash")
            }
            if (state.selectedItems.all { it.manuallyAdded }) {
                OutlinedButton(onClick = onRemove, modifier = Modifier.fillMaxWidth()) { Text("Remove from SnapSort only") }
                Text("Removing from SnapSort leaves the original gallery images untouched.", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Keep reviewing") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewSheet(shot: Screenshot, selected: Boolean, onDismiss: () -> Unit, onSelect: () -> Unit, onAction: (AppAction) -> Unit) {
    var categoryMenu by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Screenshot", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Close preview") }
            }
            AsyncImage(shot.uri, "Full preview of ${shot.name}", contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 410.dp).clip(RoundedCornerShape(16.dp)).background(Panel))
            Text(shot.name, style = MaterialTheme.typography.bodyMedium)
            Text("${formatDate(shot.dateMillis)} · ${formatBytes(shot.sizeBytes)}", style = MaterialTheme.typography.bodySmall, color = Muted)
            if (!shot.canDelete) Text("This provider allows viewing. Manage or delete the original in its gallery; SnapSort can remove it from this collection only.",
                style = MaterialTheme.typography.bodySmall, color = Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    AssistChip(onClick = { categoryMenu = true }, label = { Text(shot.category.label) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Label, null, Modifier.size(18.dp)) })
                    DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                        Category.entries.forEach { category ->
                            DropdownMenuItem(text = { Text(category.label) }, onClick = {
                                categoryMenu = false
                                onAction(AppAction.SetItemCategory(shot.uri, category))
                            })
                        }
                    }
                }
                FilterChip(selected = shot.keep, onClick = { onAction(AppAction.SetItemKeep(shot.uri, !shot.keep)) },
                    label = { Text("Keep") }, leadingIcon = { Icon(Icons.Outlined.Bookmark, null, Modifier.size(18.dp)) })
                Spacer(Modifier.weight(1f))
                Checkbox(checked = selected, onCheckedChange = { onSelect() }, modifier = Modifier.semantics { contentDescription = "Select this screenshot" })
            }
            Text(if (shot.categoryCorrected) "Category chosen by you" else "Suggested category · tap to correct",
                style = MaterialTheme.typography.bodySmall, color = Muted)
            HorizontalDivider(color = Muted.copy(alpha = .2f))
            Text("Text found on device", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            SelectionContainer {
                Text(shot.text.ifBlank { if (shot.indexed) "No readable text found." else "Text recognition is still processing this image." },
                    color = Muted, style = MaterialTheme.typography.bodyMedium)
            }
            Text("Keep excludes this image from bulk cleanup suggestions. You can still select it individually.",
                style = MaterialTheme.typography.bodySmall, color = Muted)
        }
    }
}

internal fun formatBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0)
    return when {
        safe >= 1_073_741_824 -> String.format(Locale.getDefault(), "%.1f GB", safe / 1_073_741_824.0)
        safe >= 1_048_576 -> String.format(Locale.getDefault(), "%.1f MB", safe / 1_048_576.0)
        safe >= 1024 -> String.format(Locale.getDefault(), "%.0f KB", safe / 1024.0)
        else -> "$safe B"
    }
}

private fun formatDate(timeMillis: Long): String =
    if (timeMillis > 0) DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(timeMillis)) else "Date unavailable"
