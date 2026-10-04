@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package dev.securenotes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.securenotes.document.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable fun TagFilterSheet(vm: NotesViewModel, dismiss: () -> Unit) {
    val tags by vm.tagCatalog.collectAsStateWithLifecycle()
    var manage by remember { mutableStateOf(false) }
    val state = rememberLazyListState()
    ModalBottomSheet(onDismissRequest = dismiss) {
        Text("Show notes", Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.titleLarge)
        LazyColumn(state = state, modifier = Modifier.verticalScrollbar(state)) {
            item { FilterOption("All notes", vm.filter == NoteFilter()) { vm.selectFilter(NoteFilter()); dismiss() } }
            item { FilterOption("Untagged", vm.filter.untagged) { vm.selectFilter(NoteFilter(untagged = true)); dismiss() } }
            items(tags, key = { it.id }) { tag -> FilterOption(tag.name, vm.filter.tagId == tag.id) { vm.selectFilter(NoteFilter(tagId = tag.id)); dismiss() } }
            item { ListItem(headlineContent = { Text("Manage tags") }, leadingContent = { Icon(Icons.Default.Label, null) }, modifier = Modifier.clickable { manage = true }) }
        }
    }
    if (manage) ManageTagsSheet(vm) { manage = false }
}

@Composable private fun FilterOption(label: String, selected: Boolean, action: () -> Unit) {
    ListItem(headlineContent = { Text(label) }, trailingContent = { if (selected) Icon(Icons.Default.Check, "Selected") }, modifier = Modifier.clickable(onClick = action))
}

@Composable fun AssignedTags(vm: NotesViewModel, onClick: () -> Unit) {
    val tags by vm.tagCatalog.collectAsStateWithLifecycle()
    val assigned = tags.filter { it.id in vm.note?.tagIds.orEmpty() }
    if (assigned.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        assigned.forEach { tag -> SuggestionChip(onClick = onClick, label = { Text(tag.name) }) }
    }
}

@Composable fun TagPickerSheet(vm: NotesViewModel, dismiss: () -> Unit) {
    val tags by vm.tagCatalog.collectAsStateWithLifecycle()
    var create by remember { mutableStateOf(false) }
    val state = rememberLazyListState()
    ModalBottomSheet(onDismissRequest = dismiss) {
        Text("Tags", Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.titleLarge)
        LazyColumn(state = state, modifier = Modifier.verticalScrollbar(state)) {
            items(tags, key = { it.id }) { tag ->
                val checked = tag.id in vm.note?.tagIds.orEmpty()
                ListItem(headlineContent = { Text(tag.name) }, leadingContent = { Checkbox(checked, null) },
                    modifier = Modifier.toggleable(checked, role = Role.Checkbox) { vm.assignTag(tag.id, it) })
            }
            item { TextButton(onClick = { create = true }, modifier = Modifier.fillMaxWidth().padding(8.dp)) { Icon(Icons.Default.Add, null); Text("Create tag") } }
        }
    }
    if (create) TagNameDialog("Create tag", "", { create = false }) { name -> val tag = vm.createTag(name); vm.assignTag(tag.id, true) }
}

@Composable private fun ManageTagsSheet(vm: NotesViewModel, dismiss: () -> Unit) {
    val tags by vm.tagCatalog.collectAsStateWithLifecycle()
    var create by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<Tag?>(null) }
    var delete by remember { mutableStateOf<Tag?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val state = rememberLazyListState()
    ModalBottomSheet(onDismissRequest = dismiss) {
        Text("Manage tags", Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.titleLarge)
        LazyColumn(state = state, modifier = Modifier.verticalScrollbar(state)) {
            items(tags, key = { it.id }) { tag ->
                ListItem(headlineContent = { Text(tag.name) }, trailingContent = { Row {
                    IconButton(onClick = { rename = tag }) { Icon(Icons.Default.Edit, "Rename ${tag.name}") }
                    IconButton(onClick = { error = null; delete = tag }) { Icon(Icons.Default.DeleteOutline, "Delete ${tag.name}") }
                } })
            }
            item { TextButton(onClick = { create = true }, modifier = Modifier.fillMaxWidth().padding(8.dp)) { Icon(Icons.Default.Add, null); Text("Create tag") } }
        }
    }
    if (create) TagNameDialog("Create tag", "", { create = false }) { vm.createTag(it) }
    rename?.let { tag -> TagNameDialog("Rename tag", tag.name, { rename = null }) { vm.renameTag(tag.id, it) } }
    delete?.let { tag -> AlertDialog(onDismissRequest = { if (!deleting) delete = null }, title = { Text("Delete ${tag.name}?") },
        text = { Text(error ?: "This removes the tag from every note. Your notes will be kept.") },
        confirmButton = { TextButton(enabled = !deleting, onClick = {
            deleting = true
            scope.launch {
                try { vm.deleteTag(tag.id); delete = null }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { error = "The tag could not be deleted. Try again." }
                finally { deleting = false }
            }
        }) { Text("Delete") } }, dismissButton = { TextButton(enabled = !deleting, onClick = { delete = null }) { Text("Cancel") } }) }
}

@Composable private fun TagNameDialog(title: String, initial: String, dismiss: () -> Unit, save: suspend (String) -> Unit) {
    var name by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(onDismissRequest = { if (!saving) dismiss() }, title = { Text(title) }, text = {
        OutlinedTextField(name, { name = it; error = null }, singleLine = true, enabled = !saving, label = { Text("Tag name") },
            isError = error != null, supportingText = { error?.let { Text(it) } })
    }, confirmButton = { TextButton(enabled = !saving, onClick = {
        saving = true
        scope.launch {
            try { save(name); dismiss() }
            catch (e: CancellationException) { throw e }
            catch (e: IllegalArgumentException) { error = e.message }
            catch (_: Exception) { error = "The tag could not be saved. Try again." }
            finally { saving = false }
        }
    }) { Text("Save") } }, dismissButton = { TextButton(enabled = !saving, onClick = dismiss) { Text("Cancel") } })
}
