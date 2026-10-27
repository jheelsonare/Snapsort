package com.snapsort.app.ui

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.exifinterface.media.ExifInterface
import androidx.core.net.toUri
import com.snapsort.app.domain.Category
import com.snapsort.app.domain.IndexStatus
import com.snapsort.app.domain.Screenshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.Closeable
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A URI snapshot keeps review order stable when Keep/category edits change the grid's filter. */
@Composable
internal fun ScreenshotViewer(
    state: AppUiState,
    queue: List<String>,
    initialUri: String,
    sequential: Boolean,
    onDismiss: () -> Unit,
    onAction: (AppAction) -> Unit,
) {
    var currentUri by remember(queue, initialUri) { mutableStateOf(initialUri) }
    var details by remember { mutableStateOf(false) }
    val byUri = remember(state.library.items) { state.library.items.associateBy { it.uri } }
    val shots = queue.mapNotNull(byUri::get)
    val originalPosition = queue.indexOf(currentUri)
    // Keep the current original stable when earlier members disappear externally. If this
    // original vanished, move forward in the snapshot order, falling back to a prior member.
    val shot = byUri[currentUri]?.takeIf { currentUri in queue }
        ?: queue.drop((originalPosition + 1).coerceAtLeast(0)).firstNotNullOfOrNull(byUri::get)
        ?: queue.take(originalPosition.coerceAtLeast(0)).asReversed().firstNotNullOfOrNull(byUri::get)
    val position = shots.indexOfFirst { it.uri == shot?.uri }
    LaunchedEffect(shot?.uri) {
        if (shot == null) onDismiss() else currentUri = shot.uri
    }
    if (shot == null) return
    fun advance() { if (position < shots.lastIndex) currentUri = shots[position + 1].uri else onDismiss() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = Charcoal) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Close preview") }
                    Column(Modifier.weight(1f)) {
                        Text(if (sequential) "Review screenshots" else "Screenshot", style = MaterialTheme.typography.titleMedium)
                        Text("${position + 1} / ${shots.size} · ${shot.name}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelSmall, color = Muted)
                    }
                    IconButton(onClick = { details = true }) { Icon(Icons.Outlined.Info, "Screenshot details and text") }
                }
                key(shot.uri) { ReadableImage(shot.uri, shot.name, Modifier.weight(1f).fillMaxWidth()) }
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { currentUri = shots[position - 1].uri }, enabled = position > 0) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Previous screenshot")
                        }
                        Text("${shot.category.label}${if (shot.keep) " · Keep" else ""}", color = Lime,
                            modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        IconButton(onClick = { currentUri = shots[position + 1].uri }, enabled = position < shots.lastIndex) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowForward, "Next screenshot")
                        }
                    }
                    // Horizontally scrollable at large font sizes instead of clipping decision controls.
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        FilledTonalButton(onClick = {
                            onAction(AppAction.SetItemKeep(shot.uri, if (sequential) true else !shot.keep))
                            if (sequential && shot.uri in state.selected) onAction(AppAction.ToggleSelection(shot.uri))
                            if (sequential) advance()
                        }) {
                            Icon(Icons.Outlined.Bookmark, null, Modifier.size(18.dp)); Spacer(Modifier.width(5.dp))
                            Text(if (!sequential && shot.keep) "Unmark Keep" else "Keep")
                        }
                        OutlinedButton(onClick = {
                            if (!sequential || shot.uri !in state.selected) onAction(AppAction.ToggleSelection(shot.uri))
                            if (sequential) advance()
                        }) {
                            Text(if (shot.uri in state.selected && !sequential) "Deselect" else "Select for trash")
                        }
                        if (sequential) TextButton(onClick = ::advance) { Text("Skip") }
                    }
                    Text(if (sequential) "Decisions advance to the next image. Review your selection before Android confirmation."
                        else "Pinch to zoom, drag to read. Use details to correct a category or retry indexing.",
                        color = Muted, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (details) ScreenshotDetails(shot, onDismiss = { details = false }, onAction = onAction)
    }
}

/** Decodes only the visible source region; a 100,000px scrolling screenshot stays bounded. */
@Composable
private fun ReadableImage(uri: String, name: String, modifier: Modifier = Modifier) {
    val resolver = LocalContext.current.contentResolver
    var source by remember(uri) { mutableStateOf<RegionSource?>(null) }
    var failure by remember(uri) { mutableStateOf<String?>(null) }
    var retry by remember(uri) { mutableIntStateOf(0) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember(uri) { mutableFloatStateOf(0f) }
    var offset by remember(uri) { mutableStateOf(Offset.Zero) }
    var tile by remember(uri) { mutableStateOf<RegionTile?>(null) }
    // The effect owns the decoder until the viewer is closed/retried. Disposal waits on IO,
    // never on the main thread, if a region decode is currently holding the decoder lock.
    LaunchedEffect(uri, retry) {
        failure = null
        var opened: RegionSource? = null
        try {
            source = withContext(Dispatchers.IO) { RegionSource.open(resolver, uri.toUri()).also { opened = it } }
            awaitCancellation()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failure = "This original could not be opened. Check gallery access or retry." }
        finally {
            if (source === opened) source = null
            withContext(NonCancellable + Dispatchers.IO) { opened?.close() }
        }
    }
    val image = source
    val minScale = if (image != null && viewport.width > 0 && viewport.height > 0)
        min(viewport.width.toFloat() / image.width, viewport.height.toFloat() / image.height) else 0f
    val maxScale = max(3f, minScale * 12f)
    fun clamp(candidate: Offset, zoom: Float): Offset {
        val src = source ?: return candidate
        fun axis(value: Float, rendered: Float, window: Float) =
            if (rendered <= window) (window - rendered) / 2f else value.coerceIn(window - rendered, 0f)
        return Offset(axis(candidate.x, src.width * zoom, viewport.width.toFloat()),
            axis(candidate.y, src.height * zoom, viewport.height.toFloat()))
    }
    fun zoom(factor: Float, focus: Offset = Offset(viewport.width / 2f, viewport.height / 2f), pan: Offset = Offset.Zero) {
        if (scale <= 0f || minScale <= 0f) return
        val changed = (scale * factor).coerceIn(minScale, maxScale)
        offset = clamp(focus + (offset - focus) * (changed / scale) + pan, changed)
        scale = changed
    }
    LaunchedEffect(image, viewport) {
        if (image != null && viewport.width > 0 && viewport.height > 0) {
            // Start long images at readable width, with the top visible, rather than shrinking text.
            scale = if (image.height.toFloat() / image.width > 2.5f)
                viewport.width.toFloat() / image.width else minScale
            offset = clamp(Offset.Zero, scale)
            if (image.height * scale > viewport.height) offset = offset.copy(y = 0f)
        }
    }
    LaunchedEffect(image, viewport) {
        if (image == null || viewport.width == 0 || viewport.height == 0) return@LaunchedEffect
        // One worker, one pending latest viewport: fast gestures cannot queue native decodes or
        // allocate a bitmap for every pointer event. Displayed tiles are left to GC because the
        // renderer can still hold their native pixels after a Compose state replacement.
        snapshotFlow { scale to offset }.conflate().collect { (requestedScale, requestedOffset) ->
            if (requestedScale <= 0f) return@collect
            val visible = RectF(-requestedOffset.x / requestedScale, -requestedOffset.y / requestedScale,
                (viewport.width - requestedOffset.x) / requestedScale, (viewport.height - requestedOffset.y) / requestedScale)
            // Small overscan keeps nearby text visible while the next viewport is decoding.
            visible.inset(-visible.width() * .15f, -visible.height() * .15f)
            var decoded: RegionTile? = null
            try {
                withContext(Dispatchers.IO) {
                    currentCoroutineContext().ensureActive()
                    decoded = image.decode(visible, requestedScale)
                }
                tile = decoded
                failure = null
            } catch (cancelled: CancellationException) {
                // This tile was never published to Canvas, so recycling it is safe.
                decoded?.bitmap?.recycle()
                throw cancelled
            } catch (_: Exception) { failure = "This part of the original could not be read. Try again or check access." }
        }
    }
    // Pointer-input coroutines can outlive a recomposition. Always dispatch through current
    // handlers instead of keeping an obsolete closure over zoom limits or image dimensions.
    val transformGesture by rememberUpdatedState<(Offset, Offset, Float) -> Unit>({ focus, pan, factor -> zoom(factor, focus, pan) })
    val doubleTap by rememberUpdatedState<(Offset) -> Unit>({ focus ->
        if (scale > minScale * 1.5f) zoom(minScale / scale, focus) else zoom(2f, focus)
    })
    Column(modifier.background(Color.Black)) {
        Box(Modifier.weight(1f).fillMaxWidth().onSizeChanged { viewport = it }
            .testTag("readable-screenshot").semantics {
                contentDescription = "Full preview of $name"
                stateDescription = if (scale > 0f && minScale > 0f) "Zoom ${(scale / minScale * 100).roundToInt()} percent, ${(-offset.y / scale).coerceAtLeast(0f).roundToInt()} pixels from top" else "Loading image"
            }
            .pointerInput(image, viewport) {
                detectTransformGestures { centroid, pan, factor, _ -> transformGesture(centroid, pan, factor) }
            }
            .pointerInput(image, viewport) {
                detectTapGestures(onDoubleTap = { focus -> doubleTap(focus) })
            }) {
            val pixels = tile
            if (pixels != null) Canvas(Modifier.fillMaxSize()) {
                val bitmap = pixels.bitmap.asImageBitmap()
                drawImage(bitmap, dstOffset = IntOffset((offset.x + pixels.bounds.left * scale).roundToInt(),
                    (offset.y + pixels.bounds.top * scale).roundToInt()),
                    dstSize = IntSize(max(1, (pixels.bounds.width() * scale).roundToInt()), max(1, (pixels.bounds.height() * scale).roundToInt())))
            }
            if (image == null && failure == null) CircularProgressIndicator(Modifier.align(Alignment.Center))
            if (failure != null) Column(Modifier.align(Alignment.Center).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(failure!!, color = Color.White)
                TextButton(onClick = { tile = null; scale = 0f; retry++ }) { Text("Retry image") }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center) {
            IconButton(onClick = { zoom(1f / 1.5f) }, enabled = image != null && scale > minScale) { Icon(Icons.Outlined.ZoomOut, "Zoom out") }
            IconButton(onClick = { zoom(1.5f) }, enabled = image != null && scale < maxScale) { Icon(Icons.Outlined.ZoomIn, "Zoom in") }
            TextButton(onClick = { if (minScale > 0f) zoom(minScale / scale) }, enabled = image != null) { Text("Fit image") }
            IconButton(onClick = { offset = clamp(offset + Offset(0f, viewport.height * .7f), scale) }, enabled = image != null) {
                Icon(Icons.Outlined.KeyboardArrowUp, "Read earlier part")
            }
            IconButton(onClick = { offset = clamp(offset - Offset(0f, viewport.height * .7f), scale) }, enabled = image != null) {
                Icon(Icons.Outlined.KeyboardArrowDown, "Read later part")
            }
        }
    }
}

internal data class RegionTile(val bitmap: Bitmap, val bounds: RectF)

@Suppress("DEPRECATION")
internal class RegionSource private constructor(
    private val decoder: BitmapRegionDecoder,
    private val transform: Matrix,
    private val inverse: Matrix,
    val width: Int,
    val height: Int,
) : Closeable {
    @Synchronized fun decode(visible: RectF, scale: Float): RegionTile {
        val oriented = RectF(visible).apply { intersect(0f, 0f, width.toFloat(), height.toFloat()) }
        val raw = RectF(oriented).also { inverse.mapRect(it) }
        val rect = Rect(floor(raw.left).toInt().coerceIn(0, decoder.width - 1), floor(raw.top).toInt().coerceIn(0, decoder.height - 1),
            ceil(raw.right).toInt().coerceIn(1, decoder.width), ceil(raw.bottom).toInt().coerceIn(1, decoder.height))
        var sample = 1
        while (sample < 1_048_576 && (sample * 2 <= 1f / scale || rect.width().toLong() / sample * (rect.height() / sample) > 2_500_000L)) sample *= 2
        val bitmap = decoder.decodeRegion(rect, android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: error("Could not decode image region")
        val rotated = if (transform.isIdentity) bitmap else Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, transform, true)
        if (rotated !== bitmap) bitmap.recycle()
        val bounds = RectF(rect).also { transform.mapRect(it) }
        return RegionTile(rotated, bounds)
    }
    @Synchronized override fun close() { if (!decoder.isRecycled) decoder.recycle() }
    companion object {
        fun open(resolver: ContentResolver, uri: Uri): RegionSource {
            val orientation = runCatching { resolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } }.getOrNull()
            val decoder = resolver.openInputStream(uri)?.use { BitmapRegionDecoder.newInstance(it, false) }
                ?: error("Image unavailable")
            val matrix = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> { setRotate(180f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                    ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                    ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
                }
            }
            val bounds = RectF(0f, 0f, decoder.width.toFloat(), decoder.height.toFloat()).also { matrix.mapRect(it) }
            matrix.postTranslate(-bounds.left, -bounds.top)
            val inverse = Matrix().also { check(matrix.invert(it)) }
            return RegionSource(decoder, matrix, inverse, bounds.width().roundToInt(), bounds.height().roundToInt())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScreenshotDetails(shot: Screenshot, onDismiss: () -> Unit, onAction: (AppAction) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(shot.name, style = MaterialTheme.typography.titleMedium)
            Text(formatBytes(shot.sizeBytes), color = Muted)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    AssistChip(onClick = { menu = true }, label = { Text(shot.category.label) })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        Category.entries.forEach { category -> DropdownMenuItem(text = { Text(category.label) }, onClick = {
                            menu = false; onAction(AppAction.SetItemCategory(shot.uri, category))
                        }) }
                    }
                }
                Text(if (shot.categoryCorrected) "Chosen by you" else "Suggested on device", style = MaterialTheme.typography.bodySmall)
            }
            if (shot.categoryCorrected) TextButton(onClick = { onAction(AppAction.ResetItemCategory(shot.uri)) }) { Text("Reset to suggested category") }
            Text("Text found on device", style = MaterialTheme.typography.titleSmall)
            Text("OCR: ${statusLabel(shot.ocrStatus)}${shot.ocrError?.let { " · $it" }.orEmpty()}", color = Muted, style = MaterialTheme.typography.bodySmall)
            Text("File hash: ${statusLabel(shot.hashStatus)}${shot.hashError?.let { " · $it" }.orEmpty()}", color = Muted, style = MaterialTheme.typography.bodySmall)
            SelectionContainer { Text(shot.text.ifBlank {
                when (shot.ocrStatus) {
                    IndexStatus.SUCCEEDED -> "No readable text found."
                    IndexStatus.FAILED -> "Text recognition failed. Retry when the original is available."
                    IndexStatus.RUNNING -> "Text recognition is processing this image."
                    IndexStatus.PENDING -> "Text recognition is waiting to process this image."
                }
            }, color = Muted) }
            if (shot.ocrStatus == IndexStatus.FAILED || shot.hashStatus == IndexStatus.FAILED) {
                OutlinedButton(onClick = { onAction(AppAction.RetryIndex(shot.uri)) }) { Text("Retry failed indexing") }
            }
            if (!shot.canDelete) Text("Manage this original in its image provider. SnapSort cannot trash it through Android's gallery API.", color = Muted)
            OutlinedButton(onClick = { onAction(AppAction.ExcludeItem(shot.uri)); onDismiss() }) { Text("Exclude from SnapSort") }
            Text("Excluding removes this item from SnapSort's collection only. The original stays on your phone. Select the image again to add it back.",
                color = Muted, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Close details") }
        }
    }
}

internal fun statusLabel(status: IndexStatus): String = when (status) {
    IndexStatus.PENDING -> "Waiting"
    IndexStatus.RUNNING -> "Processing"
    IndexStatus.SUCCEEDED -> "Complete"
    IndexStatus.FAILED -> "Failed"
}
