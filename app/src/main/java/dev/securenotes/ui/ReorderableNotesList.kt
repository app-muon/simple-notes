package dev.securenotes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.securenotes.document.Note
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

@Composable
internal fun ReorderableNotesList(
    notes: List<Note>, state: LazyListState, onOpen: (Note) -> Unit,
    onReorder: (List<String>, () -> Unit) -> Unit,
) {
    val latestNotes by rememberUpdatedState(notes)
    val latestReorder by rememberUpdatedState(onReorder)
    var displayed by remember { mutableStateOf(notes) }
    var draggedId by remember { mutableStateOf<String?>(null) }
    var pointerY by remember { mutableFloatStateOf(0f) }
    var grabOffset by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableIntStateOf(0) }
    var saving by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val edge = with(LocalDensity.current) { 64.dp.toPx() }
    LaunchedEffect(notes) { if (draggedId == null && !saving) displayed = notes }
    fun moveAtPointer() {
        val id = draggedId ?: return
        val from = displayed.indexOfFirst { it.id == id }
        if (from < 0) return
        val center = pointerY - grabOffset + rowHeight / 2f
        val target = state.layoutInfo.visibleItemsInfo.minByOrNull { kotlin.math.abs(it.offset + it.size / 2f - center) } ?: return
        val to = displayed.indexOfFirst { it.id == target.key }
        if (to >= 0 && to != from) displayed = displayed.toMutableList().apply { add(to, removeAt(from)) }
    }
    fun saveOrder() {
        if (displayed.map(Note::id) == latestNotes.map(Note::id)) return
        saving = true
        latestReorder(displayed.map(Note::id)) { displayed = latestNotes; saving = false }
    }
    // The gesture belongs to the list, so scrolling the source row offscreen cannot cancel it.
    LaunchedEffect(draggedId) {
        if (draggedId != null) while (true) {
            delay(16.milliseconds)
            val layout = state.layoutInfo
            val amount = when {
                pointerY < layout.viewportStartOffset + edge -> -((layout.viewportStartOffset + edge - pointerY) / 5).coerceAtMost(edge / 3)
                pointerY > layout.viewportEndOffset - edge -> ((pointerY - layout.viewportEndOffset + edge) / 5).coerceAtMost(edge / 3)
                else -> 0f
            }
            if (amount != 0f) { state.scrollBy(amount); moveAtPointer() }
        }
    }
    Box(Modifier.fillMaxSize().clipToBounds().verticalScrollbar(state).pointerInput(state) {
        detectDragGesturesAfterLongPress(
            onDragStart = { point ->
                if (!saving) state.layoutInfo.visibleItemsInfo.firstOrNull { point.y >= it.offset && point.y < it.offset + it.size }?.let {
                    draggedId = it.key as String; pointerY = point.y; grabOffset = point.y - it.offset; rowHeight = it.size
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            },
            onDrag = { change, delta -> if (draggedId != null) { change.consume(); pointerY += delta.y; moveAtPointer() } },
            onDragEnd = { if (draggedId != null) { draggedId = null; saveOrder() } },
            onDragCancel = { draggedId = null; displayed = latestNotes },
        )
    }) {
        // Once a row is lifted, only the edge-scroll loop should scroll. Otherwise the child list
        // consumes the movement before the parent drag detector and cancels the reorder gesture.
        LazyColumn(state = state, userScrollEnabled = draggedId == null,
            contentPadding = PaddingValues(bottom = 96.dp), modifier = Modifier.fillMaxSize()) {
            items(displayed, key = Note::id) { note ->
                val index = displayed.indexOfFirst { it.id == note.id }
                Column(Modifier.animateItem().graphicsLayer { alpha = if (draggedId == note.id) 0f else 1f }
                    .testTag("note-row-${note.id}").semantics {
                        customActions = if (saving || draggedId != null) emptyList() else buildList {
                            if (index > 0) add(CustomAccessibilityAction("Move up") {
                                displayed = displayed.toMutableList().apply { add(index - 1, removeAt(index)) }; saveOrder(); true
                            })
                            if (index < displayed.lastIndex) add(CustomAccessibilityAction("Move down") {
                                displayed = displayed.toMutableList().apply { add(index + 1, removeAt(index)) }; saveOrder(); true
                            })
                        }
                    }) {
                    NoteTitle(note, Modifier.clickable(enabled = !saving && draggedId == null) { onOpen(note) })
                    HorizontalDivider(Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                }
            }
        }
        displayed.firstOrNull { it.id == draggedId }?.let { note ->
            Surface(Modifier.fillMaxWidth().offset { IntOffset(0, (pointerY - grabOffset).roundToInt()) },
                shadowElevation = 8.dp, color = MaterialTheme.colorScheme.secondaryContainer) { NoteTitle(note) }
        }
    }
}

@Composable private fun NoteTitle(note: Note, modifier: Modifier = Modifier) {
    Text(note.displayTitle, modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
        style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
}
