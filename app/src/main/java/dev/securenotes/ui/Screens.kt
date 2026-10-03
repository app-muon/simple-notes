@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package dev.securenotes.ui

import android.app.KeyguardManager
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.*
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.securenotes.document.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun SecureNotesApp(vm: NotesViewModel, authenticate: () -> Unit, configureLock: () -> Unit, openFile: (String) -> Unit) {
    val unlocked by vm.app.unlocked.collectAsStateWithLifecycle()
    // Register while locked as well, so a returning camera can deliver its result after process death.
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture(), vm.app.camera::result)
    val cameraRevision by vm.app.camera.revision.collectAsStateWithLifecycle()
    LaunchedEffect(unlocked, cameraRevision) { if (unlocked) vm.recoverCamera() }
    val dark = isSystemInDarkTheme()
    androidx.compose.ui.platform.InterceptPlatformTextInput(interceptor = { request, next ->
        next.startInputMethod(object : androidx.compose.ui.platform.PlatformTextInputMethodRequest {
            override fun createInputConnection(outAttributes: android.view.inputmethod.EditorInfo): android.view.inputmethod.InputConnection {
                return request.createInputConnection(outAttributes).also {
                    outAttributes.imeOptions = outAttributes.imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                }
            }
        })
    }) {
    MaterialTheme(colorScheme = if (dark) darkColorScheme(primary = Color(0xFF9AD5B5)) else lightColorScheme(primary = Color(0xFF386A54), background = Color(0xFFFAFAF6))) {
        Surface(Modifier.fillMaxSize()) {
            if (!unlocked) LockedScreen(authenticate, configureLock) else UnlockedScreens(vm, openFile) { vm.prepareCamera { camera.launch(it) } }
            vm.error?.let { message -> AlertDialog(onDismissRequest = { vm.error = null }, title = { Text("Unable to finish") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { vm.error = null }) { Text("OK") } }) }
        }
    }
    }
}

@Composable private fun LockedScreen(authenticate: () -> Unit, configureLock: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var secure by remember { mutableStateOf(context.getSystemService(KeyguardManager::class.java).isDeviceSecure) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) {
            secure = context.getSystemService(KeyguardManager::class.java).isDeviceSecure
            if (secure) authenticate()
        } }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { if (secure && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) authenticate() }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.Lock, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(24.dp)); Text("Secure Notes", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(12.dp)); Text(if (secure) "Your notes stay on this device." else "Set a device PIN, password, or pattern before using your notes.")
        Spacer(Modifier.height(24.dp)); Button(onClick = if (secure) authenticate else configureLock) { Text(if (secure) "Unlock" else "Set up device lock") }
    }
}

@Composable private fun UnlockedScreens(vm: NotesViewModel, openFile: (String) -> Unit, takePhoto: () -> Unit) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { vm.import(it, "image/*") }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { runCatching { context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        vm.import(uris)
    }
    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { vm.exportBackup(it) }
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), vm::selectRestore)
    LaunchedEffect(vm.backupFile) { if (vm.backupFile != null) backupPicker.launch("SecureNotes-${java.time.LocalDate.now()}.ssnb") }
    val back = { keyboard?.hide(); focus.clearFocus(); vm.back() }
    BackHandler(enabled = vm.screen != Screen.LIST || vm.choosingDestination, onBack = back)
    val activity = androidx.activity.compose.LocalActivity.current
    val currentBack by rememberUpdatedState(back)
    val editingNote = vm.screen == Screen.NOTE && vm.editing
    DisposableEffect(activity, editingNote) {
        // Editing is a temporary mode: one Back must dismiss both it and the IME.
        // Overlay priority receives system/gesture Back before the keyboard's callback.
        val dispatcher = activity?.onBackInvokedDispatcher
        val callback = android.window.OnBackInvokedCallback { currentBack() }
        if (editingNote) dispatcher?.registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY, callback)
        onDispose { dispatcher?.unregisterOnBackInvokedCallback(callback) }
    }
    val attach: (String) -> Unit = { type ->
        keyboard?.hide()
        when (type) {
            "Photo" -> photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            "File" -> files.launch(arrayOf("*/*"))
            "Camera" -> takePhoto()
        }
    }
    when (vm.screen) {
        Screen.LIST -> NotesList(vm)
        Screen.SEARCH -> SearchScreen(vm, back)
        Screen.NOTE -> NoteScreen(vm, back, attach, openFile)
        Screen.SETTINGS -> SettingsScreen(vm, back) { restorePicker.launch(arrayOf("*/*")) }
        Screen.IMAGE -> ImageViewer(vm, back)
    }
    if (vm.share != null && !vm.choosingDestination) AlertDialog(onDismissRequest = { vm.share = null }, title = { Text("Add shared content") }, text = { Text("Choose where to put the shared text or files.") }, confirmButton = { TextButton(onClick = { vm.acceptShare(null) }) { Text("New note") } }, dismissButton = { TextButton(onClick = { vm.choosingDestination = true; vm.screen = Screen.LIST; vm.query = "" }) { Text("Add to existing note") } })
    vm.passwordMode?.let { mode -> PasswordDialog(mode, { vm.passwordMode = null }, vm::submitPassword) }
    vm.restoreCount?.let { count -> AlertDialog(onDismissRequest = vm::cancelRestore, title = { Text("Replace all notes?") }, text = { Text("This backup contains $count notes. All current notes, attachments, and settings will be permanently replaced. No safety backup will be created.") }, confirmButton = { TextButton(onClick = vm::restore) { Text("Replace everything") } }, dismissButton = { TextButton(onClick = vm::cancelRestore) { Text("Cancel") } }) }
    vm.busy?.let { message -> AlertDialog(onDismissRequest = {}, title = { Text(message) }, text = { LinearProgressIndicator(Modifier.fillMaxWidth()) }, confirmButton = { TextButton(onClick = vm::cancelOperation) { Text("Cancel") } }) }
    if (vm.cameraRecovery) AlertDialog(onDismissRequest = {}, title = { Text("Unfinished photo") }, text = { Text("A camera capture was interrupted. Keep the photo if it was taken, or discard the capture.") }, confirmButton = { TextButton(onClick = { vm.resolveCamera(true) }) { Text("Keep photo") } }, dismissButton = { TextButton(onClick = { vm.resolveCamera(false) }) { Text("Discard") } })
}

@Composable private fun NotesList(vm: NotesViewModel) {
    val all by vm.repository.notes.collectAsStateWithLifecycle()
    val sort by vm.repository.sort.collectAsStateWithLifecycle()
    val sorted = remember(all, sort) { sortedNotes(all, sort) }
    val filtered = if (vm.choosingDestination && vm.query.isNotBlank()) sorted.filter { n -> vm.results.any { it.noteId == n.id } } else sorted
    val state = rememberLazyListState()
    LaunchedEffect(Unit) { val index = sorted.indexOfFirst { it.id == vm.listAnchor }; if (index >= 0) state.scrollToItem(index, vm.listOffset) }
    Scaffold(topBar = { LargeTopAppBar(title = { Text(if (vm.choosingDestination) "Add to note" else "Notes") }, actions = {
        if (vm.choosingDestination) TextButton(onClick = { vm.share = null; vm.choosingDestination = false; vm.query = "" }) { Text("Cancel") }
        else {
            IconButton(onClick = { vm.query = ""; vm.results = emptyList(); vm.screen = Screen.SEARCH }) { Icon(Icons.Default.Search, "Search") }
            IconButton(onClick = { vm.screen = Screen.SETTINGS }) { Icon(Icons.Default.Settings, "Settings") }
        }
    }) }, floatingActionButton = { if (!vm.choosingDestination) FloatingActionButton(onClick = vm::create) { Icon(Icons.Default.Add, "New note") } }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (vm.choosingDestination) OutlinedTextField(vm.query, vm::search, Modifier.fillMaxWidth().padding(16.dp), label = { Text("Search notes") }, singleLine = true)
            if (filtered.isEmpty()) Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { Text(if (all.isEmpty()) "A quiet place for your notes.\nTap + to begin." else "No matching notes", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            LazyColumn(state = state, contentPadding = PaddingValues(bottom = 96.dp)) {
                items(filtered, key = { it.id }) { note ->
                    Column(Modifier.fillMaxWidth().clickable {
                        vm.listAnchor = filtered.getOrNull(state.firstVisibleItemIndex)?.id; vm.listOffset = state.firstVisibleItemScrollOffset
                        if (vm.choosingDestination) vm.acceptShare(note) else vm.open(note)
                    }.padding(horizontal = 24.dp, vertical = 22.dp)) { Text(note.displayTitle, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    HorizontalDivider(Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                }
            }
        }
    }
}

@Composable private fun SearchScreen(vm: NotesViewModel, back: () -> Unit) {
    val all by vm.repository.notes.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text("Search") }, navigationIcon = { BackButton(back) }) }) { padding ->
        Column(Modifier.padding(padding)) {
            OutlinedTextField(vm.query, vm::search, Modifier.fillMaxWidth().padding(16.dp), label = { Text("Search notes and attachments") }, singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) })
            if (vm.query.isNotBlank() && vm.results.isEmpty()) Text("No matching notes", Modifier.padding(24.dp))
            LazyColumn { items(vm.results, key = { it.noteId }) { hit ->
                Column(Modifier.fillMaxWidth().clickable { all.firstOrNull { it.id == hit.noteId }?.let { vm.open(it, hit) } }.padding(24.dp)) {
                    Text(hit.title, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp)); Text(hit.snippet, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    hit.context?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp)) }
                }; HorizontalDivider(Modifier.padding(horizontal = 24.dp))
            } }
        }
    }
}

@Composable private fun NoteScreen(vm: NotesViewModel, back: () -> Unit, attach: (String) -> Unit, openFile: (String) -> Unit) {
    val note = vm.note ?: return
    val state = rememberLazyListState()
    val titleFocus = remember { FocusRequester() }
    var editor by remember { mutableStateOf<BlockEditText?>(null) }
    var delete by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var highlight by remember(note.id) { mutableStateOf(vm.hit) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    LaunchedEffect(vm.focusTitle, vm.editing) { if (vm.editing && vm.focusTitle) { titleFocus.requestFocus(); keyboard?.show() } }
    LaunchedEffect(vm.focusedBlock, vm.editing) {
        if (vm.editing) {
            val index = note.document.blocks.indexOfFirst { it.id == vm.focusedBlock }
            if (index >= 0) state.animateScrollToItem(index + 1)
        }
    }
    LaunchedEffect(note.id, vm.hit) {
        vm.hit?.let { hit ->
            val index = note.document.blocks.indexOfFirst { it.id == hit.blockId }
            state.scrollToItem(if (index >= 0) index + 1 else 0)
            delay(2200); highlight = null
        }
    }
    Scaffold(modifier = Modifier.imePadding(), topBar = { TopAppBar(title = { Text(if (vm.editing) "Editing" else "Note", style = MaterialTheme.typography.titleMedium) }, navigationIcon = { BackButton(back) }, actions = {
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Note menu") }
            DropdownMenu(menu, { menu = false }) { DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null) }, onClick = { menu = false; delete = true }) }
        }
    }) }, bottomBar = { if (vm.editing) EditorToolbar(vm, { editor?.bold() }, attach) }) { padding ->
        LazyColumn(state = state, modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item(key = "title") {
                if (vm.editing) OutlinedTextField(note.title, { vm.change(note.copy(title = it)) }, modifier = Modifier.fillMaxWidth().focusRequester(titleFocus).onFocusChanged { if (it.isFocused) { editor = null; vm.focusedBlock = null } }, placeholder = { Text("Title") }, textStyle = MaterialTheme.typography.headlineLarge, singleLine = true)
                else SelectionContainer { Text(note.displayTitle, Modifier.fillMaxWidth().clickable { vm.enterEditing() }.padding(vertical = 12.dp)
                    .background(if (highlight != null && highlight?.blockId == null) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent), style = MaterialTheme.typography.headlineLarge) }
            }
            items(note.document.blocks, key = { it.id }) { block ->
                val selected = highlight?.blockId == block.id
                if (block.isAttachment) AttachmentBlock(vm, block, selected, openFile, state)
                else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                    when (block.type) {
                        BlockType.CHECKLIST -> Checkbox(block.checked, { vm.block(block.copy(checked = it)) }, modifier = Modifier.semantics { contentDescription = if (block.text.isBlank()) "Checklist item" else block.text })
                        BlockType.BULLET -> Text("•", Modifier.padding(end = 12.dp, top = 8.dp), fontSize = 20.sp)
                        BlockType.NUMBERED -> {
                            val index = note.document.blocks.indexOf(block)
                            val number = note.document.blocks.take(index + 1).takeLastWhile { it.type == BlockType.NUMBERED }.size
                            Text("$number.", Modifier.padding(end = 12.dp, top = 8.dp), fontSize = 18.sp)
                        }
                        else -> Unit
                    }
                    if (vm.editing) RichTextEditor(block, MaterialTheme.colorScheme.onSurface, vm.focusedBlock == block.id, Modifier.weight(1f),
                        onFocus = { editor = it; vm.focusedBlock = block.id; vm.focusTitle = false },
                        onChange = { text, spans -> vm.split(block.id, text, spans) }, onJoin = { vm.joinPrevious(block.id) }, onLeave = { keyboard?.hide(); focus.clearFocus(); vm.back() }, focusOffset = vm.focusOffset)
                    else {
                        val match = if (selected) highlight else null
                        val text = annotated(block, match?.start, match?.end, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.primary)
                        SelectionContainer { Text(text, modifier = Modifier.weight(1f).heightIn(min = 48.dp).clickable { vm.enterEditing(block.id) }.padding(vertical = 6.dp), style = when (block.type) { BlockType.HEADING1 -> MaterialTheme.typography.headlineMedium; BlockType.HEADING2 -> MaterialTheme.typography.titleLarge; else -> MaterialTheme.typography.bodyLarge }) }
                    }
                }
            }
            if (vm.editing) item { TextButton(onClick = { vm.addText() }) { Icon(Icons.Default.Add, null); Text("Text") } }
        }
    }
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text("Delete this note?") }, text = { Text("This permanently deletes the note and its attachments.") }, confirmButton = { TextButton(onClick = { delete = false; vm.delete() }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { delete = false }) { Text("Cancel") } })
}

private fun annotated(block: Block, start: Int?, end: Int?, highlight: Color, linkColor: Color): AnnotatedString = buildAnnotatedString {
    append(block.text.ifEmpty { " " })
    block.bold.forEach { addStyle(SpanStyle(fontWeight = FontWeight.Bold), it.start, it.end) }
    if (start != null && end != null && start >= 0 && end <= length && start < end) addStyle(SpanStyle(background = highlight), start, end)
    val links = android.text.SpannableString(block.text)
    androidx.core.text.util.LinkifyCompat.addLinks(links, android.text.util.Linkify.WEB_URLS or android.text.util.Linkify.EMAIL_ADDRESSES or android.text.util.Linkify.PHONE_NUMBERS)
    links.getSpans(0, links.length, android.text.style.URLSpan::class.java).forEach { span ->
        addLink(LinkAnnotation.Url(span.url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline))), links.getSpanStart(span), links.getSpanEnd(span))
    }
}

@Composable private fun EditorToolbar(vm: NotesViewModel, bold: () -> Unit, attach: (String) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val revision = vm.historyRevision
    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.Add, "Attach") }
                DropdownMenu(menu, { menu = false }) { listOf("Photo", "Camera", "File").forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { menu = false; attach(option) }) } }
            }
            IconButton(onClick = vm::undo, enabled = revision >= 0 && vm.history.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
            IconButton(onClick = vm::redo, enabled = vm.history.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
            IconButton(onClick = bold) { Icon(Icons.Default.FormatBold, "Bold selected text") }
            TextButton(onClick = { vm.format(BlockType.PARAGRAPH) }) { Text("Text") }
            TextButton(onClick = { vm.format(BlockType.HEADING1) }) { Text("H1") }
            TextButton(onClick = { vm.format(BlockType.HEADING2) }) { Text("H2") }
            IconButton(onClick = { vm.format(BlockType.BULLET) }) { Icon(Icons.AutoMirrored.Filled.FormatListBulleted, "Bullet list") }
            IconButton(onClick = { vm.format(BlockType.NUMBERED) }) { Icon(Icons.Default.FormatListNumbered, "Numbered list") }
            IconButton(onClick = { vm.format(BlockType.CHECKLIST) }) { Icon(Icons.Default.Checklist, "Checklist") }
        }
    }
}

@Composable private fun AttachmentBlock(vm: NotesViewModel, block: Block, highlighted: Boolean, openFile: (String) -> Unit, listState: androidx.compose.foundation.lazy.LazyListState) {
    val id = block.attachmentId ?: return
    val attachment = vm.attachments[id] ?: return
    var menu by remember { mutableStateOf(false) }
    var drag by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val bitmap by attachmentBitmap(vm, id, 1200)
    val dragModifier = if (vm.editing) Modifier.pointerInput(block.id) {
        detectDragGesturesAfterLongPress(
            onDragStart = { drag = 0f; dragging = true },
            onDragCancel = { drag = 0f; dragging = false },
            onDragEnd = {
                val items = listState.layoutInfo.visibleItemsInfo
                val current = items.firstOrNull { it.key == block.id }
                if (current != null) {
                    val center = current.offset + current.size / 2f + drag
                    val blocks = vm.note?.document?.blocks.orEmpty()
                    val target = items.filter { item -> blocks.any { it.id == item.key } }.minByOrNull { kotlin.math.abs(it.offset + it.size / 2f - center) }
                    val from = blocks.indexOfFirst { it.id == block.id }
                    val to = target?.let { item -> blocks.indexOfFirst { it.id == item.key } } ?: -1
                    if (from >= 0 && to >= 0) vm.move(block.id, to - from)
                }
                drag = 0f; dragging = false
            },
            onDrag = { change, amount ->
                change.consume(); drag += amount.y
            },
        )
    } else Modifier
    Card(Modifier.fillMaxWidth().zIndex(if (dragging) 1f else 0f).graphicsLayer { translationY = drag }.then(dragModifier),
        border = if (dragging) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        colors = CardDefaults.cardColors(containerColor = if (highlighted || dragging) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
        if (block.type == BlockType.IMAGE && bitmap != null) Image(bitmap!!.asImageBitmap(), attachment.filename, Modifier.fillMaxWidth().heightIn(max = 360.dp).clickable { if (!vm.editing) { vm.imageId = id; vm.screen = Screen.IMAGE } else menu = true })
        Row(Modifier.fillMaxWidth().clickable { if (!vm.editing) { if (block.type == BlockType.IMAGE) { vm.imageId = id; vm.screen = Screen.IMAGE } else openFile(id) } else menu = true }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (block.type == BlockType.IMAGE) Icons.Default.Image else Icons.AutoMirrored.Filled.InsertDriveFile, null)
            Text(attachment.filename, Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (vm.editing) Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Attachment options") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Move up") }, onClick = { menu = false; vm.move(block.id, -1) })
                    DropdownMenuItem(text = { Text("Move down") }, onClick = { menu = false; vm.move(block.id, 1) })
                    DropdownMenuItem(text = { Text("Remove attachment") }, onClick = { menu = false; vm.removeBlock(block.id) })
                }
            }
        }
    }
}

@Composable private fun attachmentBitmap(vm: NotesViewModel, id: String, maxDimension: Int): State<Bitmap?> = produceState<Bitmap?>(null, id) {
    if (vm.attachments[id]?.mime?.startsWith("image/") != true) return@produceState
    value = try { vm.repository.access { v ->
        v.decodeImage(id, checkNotNull(vm.attachments[id]).size, maxDimension)
    } } catch (_: Exception) { null }
}

@Composable private fun ImageViewer(vm: NotesViewModel, back: () -> Unit) {
    val id = vm.imageId ?: return
    val bitmap by attachmentBitmap(vm, id, 3000)
    var scale by remember { mutableFloatStateOf(1f) }; var x by remember { mutableFloatStateOf(0f) }; var y by remember { mutableFloatStateOf(0f) }
    Scaffold(topBar = { TopAppBar(title = { Text(vm.attachments[id]?.filename ?: "Image", maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = { BackButton(back) }) }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).pointerInput(Unit) { detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 6f); if (scale > 1) { x += pan.x; y += pan.y } else { x = 0f; y = 0f } } }, contentAlignment = Alignment.Center) {
            if (bitmap == null) Text("Image unavailable") else Image(bitmap!!.asImageBitmap(), vm.attachments[id]?.filename, Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale; translationX = x; translationY = y })
        }
    }
}

@Composable private fun SettingsScreen(vm: NotesViewModel, back: () -> Unit, restore: () -> Unit) {
    val sort by vm.repository.sort.collectAsStateWithLifecycle()
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { BackButton(back) }) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Notes", style = MaterialTheme.typography.titleLarge)
            Text("Sort order", color = MaterialTheme.colorScheme.onSurfaceVariant)
            SortOrder.entries.forEach { order -> Row(Modifier.fillMaxWidth().clickable { vm.task(null, "Settings could not be saved.") { vm.repository.setSort(order) } }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(sort == order, { vm.task(null, "Settings could not be saved.") { vm.repository.setSort(order) } }); Text(order.label)
            } }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text("Backup & restore", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { vm.passwordMode = "configure" }) { Text("Configure / change backup password") }
            FilledTonalButton(onClick = vm::requestBackup, modifier = Modifier.fillMaxWidth()) { Text("Create encrypted backup") }
            OutlinedButton(onClick = restore, modifier = Modifier.fillMaxWidth()) { Text("Restore encrypted backup") }
            Spacer(Modifier.height(16.dp)); Text("Secure Notes · ${dev.securenotes.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun PasswordDialog(mode: String, dismiss: () -> Unit, submit: (CharArray) -> Unit) {
    var password by remember { mutableStateOf("") }; var confirmation by remember { mutableStateOf("") }
    val configure = mode.startsWith("configure")
    AlertDialog(onDismissRequest = { password = ""; confirmation = ""; dismiss() }, title = { Text(if (configure) "Backup password" else "Enter backup password") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (configure) Text("If you forget this backup password, the encrypted backup cannot be recovered. Changing it only affects future backups.")
            OutlinedTextField(password, { password = it }, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true)
            if (configure) OutlinedTextField(confirmation, { confirmation = it }, label = { Text("Confirm password") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true)
        }
    }, confirmButton = { TextButton(enabled = password.isNotEmpty() && (!configure || password == confirmation), onClick = { val chars = password.toCharArray(); password = ""; confirmation = ""; submit(chars) }) { Text(if (configure) "Configure" else "Continue") } }, dismissButton = { TextButton(onClick = { password = ""; confirmation = ""; dismiss() }) { Text("Cancel") } })
}

@Composable private fun BackButton(back: () -> Unit) { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
