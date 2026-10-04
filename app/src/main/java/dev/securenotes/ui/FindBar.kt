package dev.securenotes.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.*
import androidx.compose.ui.unit.dp
import dev.securenotes.search.*

@Composable fun FindBar(vm: NotesViewModel, focus: FocusRequester) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(vm.find.query, vm::findQuery, Modifier.weight(1f).focusRequester(focus).testTag("find-query"), label = { Text("Find in note") }, singleLine = true)
            IconButton(onClick = vm::closeFind) { Icon(Icons.Default.Close, "Close find") }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (vm.find.query.isEmpty()) "Enter text to find" else if (vm.find.pending) "" else if (vm.find.matches.isEmpty()) "No matches" else "${vm.find.activeIndex + 1} of ${vm.find.matches.size}", Modifier.weight(1f).testTag("find-counter"), style = MaterialTheme.typography.labelLarge)
            IconButton(enabled = !vm.find.pending && vm.find.matches.isNotEmpty(), onClick = { vm.moveFind(-1); focus.requestFocus() }) { Icon(Icons.Default.KeyboardArrowUp, "Previous match") }
            IconButton(enabled = !vm.find.pending && vm.find.matches.isNotEmpty(), onClick = { vm.moveFind(1); focus.requestFocus() }) { Icon(Icons.Default.KeyboardArrowDown, "Next match") }
        }
    }
}

fun withFindHighlights(text: AnnotatedString, matches: List<IndexedFindMatch>, activeIndex: Int, offset: Int, colors: ColorScheme): AnnotatedString = buildAnnotatedString {
    append(text)
    // Active styling is added last so overlapping literal matches still have one distinct active range.
    fun highlight(indexed: IndexedFindMatch) {
        val match = indexed.match
        val start = (match.start - offset).coerceAtLeast(0); val end = (match.end - offset).coerceAtMost(text.length)
        if (end <= start) return
        val active = indexed.index == activeIndex
        addStyle(SpanStyle(background = if (active) colors.primary else colors.secondaryContainer,
            color = if (active) colors.onPrimary else colors.onSecondaryContainer), start, end)
    }
    matches.forEach { if (it.index != activeIndex) highlight(it) }
    matches.firstOrNull { it.index == activeIndex }?.let(::highlight)
}
