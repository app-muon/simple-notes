@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.text.selection.DisableSelection
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.input.ImeAction
import dev.securenotes.search.HitKind
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
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
import dev.securenotes.R
import dev.securenotes.document.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

@Composable
fun SecureNotesApp(vm: NotesViewModel, authenticate: () -> Unit, configureLock: () -> Unit, openFile: (String) -> Unit, recover: (CharArray) -> Unit = {}, reveal: () -> Unit = {}) {
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
            if (!unlocked) LockedScreen(vm, authenticate, configureLock, recover) else UnlockedScreens(vm, openFile, reveal) { vm.prepareCamera { camera.launch(it) } }
            vm.error?.let { message -> AlertDialog(onDismissRequest = { vm.error = null }, title = { Text("Unable to finish") }, text = { Text(message) }, confirmButton = { TextButton(onClick = { vm.error = null }) { Text("OK") } }) }
        }
    }
    }
}

@Composable private fun LockedScreen(vm: NotesViewModel, authenticate: () -> Unit, configureLock: () -> Unit, recover: (CharArray) -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var secure by remember { mutableStateOf(context.getSystemService(KeyguardManager::class.java).isDeviceSecure) }
    var recovering by remember { mutableStateOf(false) }
    // First run lets the user choose between a new store and a restore, so it never prompts automatically.
    val firstRun = !vm.app.keys.existing && !vm.app.repository.hasVault()
    val automatic by rememberUpdatedState(secure && !firstRun && !vm.keyUnavailable)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) {
            secure = context.getSystemService(KeyguardManager::class.java).isDeviceSecure
            if (automatic) authenticate()
        } }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { if (automatic && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) authenticate() }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.Lock, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(24.dp)); Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(12.dp))
        when {
            !secure -> {
                Text("Set a device PIN, password, or pattern before using your notes.")
                Spacer(Modifier.height(24.dp)); Button(onClick = configureLock) { Text("Set up device lock") }
            }
            vm.keyUnavailable -> {
                Text("This phone's unlock key for your notes is no longer available, for example because the screen lock was removed. Enter your recovery passphrase to unlock and set up a new key.")
                Spacer(Modifier.height(24.dp)); Button(onClick = { recovering = true }) { Text("Unlock with recovery passphrase") }
            }
            firstRun -> {
                Text("Your notes stay on this device, encrypted.")
                Spacer(Modifier.height(24.dp)); Button(onClick = { vm.restoreAfterUnlock = false; authenticate() }) { Text("Create new notes") }
                Spacer(Modifier.height(8.dp)); OutlinedButton(onClick = { vm.restoreAfterUnlock = true; authenticate() }) { Text("Restore from backup") }
            }
            else -> {
                Text("Your notes stay on this device.")
                Spacer(Modifier.height(24.dp)); Button(onClick = authenticate) { Text("Unlock") }
            }
        }
    }
    if (recovering) PassphraseDialog(vm.wordlist, "Recovery passphrase", "Enter the ${dev.securenotes.security.Passphrase.WORDS} words you saved when you first set up Notes.", "Unlock",
        { recovering = false }) { recovering = false; recover(it) }
}

@Composable private fun UnlockedScreens(vm: NotesViewModel, openFile: (String) -> Unit, reveal: () -> Unit, takePhoto: () -> Unit) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { vm.import(it, "image/*") }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { runCatching { context.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        vm.import(uris)
    }
    val backupPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream"), vm::chooseBackup)
    val restorePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument(), vm::selectRestore)
    val chooseRestore = { restorePicker.launch(arrayOf("*/*")) }
    LaunchedEffect(vm.restoreAfterUnlock) { if (vm.restoreAfterUnlock) chooseRestore() }
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
    when {
        vm.restoreAfterUnlock -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Choose a Notes backup file") }
        // Nothing else is reachable until the recovery passphrase has been confirmed.
        vm.setupWords != null -> PassphraseSetupScreen(vm, chooseRestore)
        else -> when (vm.screen) {
            Screen.LIST -> NotesList(vm)
            Screen.SEARCH -> SearchScreen(vm, back)
            Screen.NOTE -> NoteScreen(vm, back, attach, openFile)
            Screen.SETTINGS -> SettingsScreen(vm, back, reveal, chooseRestore) { backupPicker.launch("Notes.ssnb") }
            Screen.IMAGE -> ImageViewer(vm, back)
        }
    }
    vm.revealedWords?.let { words -> RevealPassphraseDialog(words) { vm.revealedWords = null } }
    if (vm.replaceBackup != null) AlertDialog(onDismissRequest = { vm.replaceBackup = null }, title = { Text("Replace existing backup?") },
        text = { Text("This file already contains a Notes backup. If it came from another phone, it may be your only copy of those notes. Restore it instead, or replace it with a backup of the notes on this phone.") },
        confirmButton = { TextButton(onClick = vm::restoreChosenBackup) { Text("Restore it") } },
        dismissButton = { Row {
            TextButton(onClick = { vm.replaceBackup = null }) { Text("Cancel") }
            TextButton(onClick = vm::confirmReplaceBackup) { Text("Replace", color = MaterialTheme.colorScheme.error) }
        } })
    if (vm.share != null && !vm.choosingDestination && vm.setupWords == null) AlertDialog(onDismissRequest = { vm.share = null }, title = { Text("Add shared content") }, text = { Text("Choose where to put the shared text or files.") }, confirmButton = { TextButton(onClick = { vm.acceptShare(null) }) { Text("New note") } }, dismissButton = { TextButton(onClick = { vm.choosingDestination = true; vm.screen = Screen.LIST; vm.query = "" }) { Text("Add to existing note") } })
    if (vm.passwordMode != null) PassphraseDialog(vm.wordlist, "Backup passphrase", "Enter the recovery passphrase of the phone that made this backup. It becomes this phone's recovery passphrase.", "Continue",
        { vm.passwordMode = null }, vm::submitPassword)
    vm.restoreCount?.let { count -> AlertDialog(onDismissRequest = vm::cancelRestore, title = { Text("Replace all notes?") }, text = { Text("This backup contains $count notes. All current notes, attachments, and settings will be permanently replaced. No safety backup will be created.") }, confirmButton = { TextButton(onClick = vm::restore) { Text("Replace everything") } }, dismissButton = { TextButton(onClick = vm::cancelRestore) { Text("Cancel") } }) }
    vm.busy?.let { message -> AlertDialog(onDismissRequest = {}, title = { Text(message) }, text = { LinearProgressIndicator(Modifier.fillMaxWidth()) }, confirmButton = { TextButton(onClick = vm::cancelOperation) { Text("Cancel") } }) }
    if (vm.cameraRecovery) AlertDialog(onDismissRequest = {}, title = { Text("Unfinished photo") }, text = { Text("A camera capture was interrupted. Keep the photo if it was taken, or discard the capture.") }, confirmButton = { TextButton(onClick = { vm.resolveCamera(true) }) { Text("Keep photo") } }, dismissButton = { TextButton(onClick = { vm.resolveCamera(false) }) { Text("Discard") } })
}

@Composable private fun NotesList(vm: NotesViewModel) {
    val all by vm.repository.notes.collectAsStateWithLifecycle()
    val sorted = all
    val filtered = if (vm.choosingDestination && vm.query.isNotBlank()) sorted.filter { n -> vm.results.any { it.noteId == n.id } } else sorted
    val state = rememberLazyListState()
    LaunchedEffect(Unit) { val index = sorted.indexOfFirst { it.id == vm.listAnchor }; if (index >= 0) state.scrollToItem(index, vm.listOffset) }
    Scaffold(topBar = { TopAppBar(expandedHeight = 56.dp, modifier = Modifier.then(Modifier.semantics { contentDescription = "Notes toolbar" }), title = { Text(if (vm.choosingDestination) "Add to note" else "Notes") }, actions = {
        if (vm.choosingDestination) TextButton(onClick = { vm.share = null; vm.choosingDestination = false; vm.query = "" }) { Text("Cancel") }
        else {
            IconButton(onClick = { vm.query = ""; vm.results = emptyList(); vm.screen = Screen.SEARCH }) { Icon(Icons.Default.Search, "Search") }
            IconButton(onClick = { vm.screen = Screen.SETTINGS }) { Icon(Icons.Default.Settings, "Settings") }
        }
    }) }, floatingActionButton = { if (!vm.choosingDestination) FloatingActionButton(onClick = vm::create) { Icon(Icons.Default.Add, "New note") } }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (vm.choosingDestination) OutlinedTextField(vm.query, vm::search, Modifier.fillMaxWidth().padding(16.dp), label = { Text("Search notes") }, singleLine = true)
            if (filtered.isEmpty()) Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { Text(if (all.isEmpty()) "A quiet place for your notes.\nTap + to begin." else "No matching notes", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (!vm.choosingDestination) ReorderableNotesList(all, state, onOpen = { note ->
                vm.listAnchor = all.getOrNull(state.firstVisibleItemIndex)?.id; vm.listOffset = state.firstVisibleItemScrollOffset
                vm.open(note)
            }, onReorder = vm::reorderNotes)
            else LazyColumn(state = state, modifier = Modifier.weight(1f).verticalScrollbar(state), contentPadding = PaddingValues(bottom = 96.dp)) {
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
    val state = rememberLazyListState()
    Scaffold(topBar = { TopAppBar(title = { Text("Search") }, navigationIcon = { BackButton(back) }) }) { padding ->
        Column(Modifier.padding(padding)) {
            OutlinedTextField(vm.query, vm::search, Modifier.fillMaxWidth().padding(16.dp), label = { Text("Search notes and attachments") }, singleLine = true, leadingIcon = { Icon(Icons.Default.Search, null) })
            if (vm.query.isNotBlank() && vm.results.isEmpty()) Text("No matching notes", Modifier.padding(24.dp))
            LazyColumn(state = state, modifier = Modifier.weight(1f).verticalScrollbar(state)) { items(vm.results, key = { it.noteId }) { hit ->
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
    val titleFocus = remember { FocusRequester() }
    var editor by remember { mutableStateOf<BodyEditText?>(null) }
    var selectionRevision by remember { mutableIntStateOf(0) }
    var delete by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var highlight by remember(note.id) { mutableStateOf(vm.hit) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val scroll = rememberScrollState()
    // Positions inside the scrolled content, used to jump to a search match.
    var content by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val lineTops = remember(note.id) { mutableStateMapOf<Int, Int>() }
    var attachmentsTop by remember(note.id) { mutableIntStateOf(0) }
    LaunchedEffect(vm.focusTitle, vm.editing) { if (vm.editing && vm.focusTitle) { titleFocus.requestFocus(); keyboard?.show() } }
    LaunchedEffect(vm.bodyFocus, editor) {
        val offset = vm.bodyFocus ?: return@LaunchedEffect
        val body = editor ?: return@LaunchedEffect
        body.focusAt(offset); vm.focusTitle = false; vm.bodyFocus = null
    }
    LaunchedEffect(note.id, vm.hit) {
        vm.hit?.let { hit ->
            val top = when (hit.kind) {
                HitKind.TITLE -> 0
                HitKind.BODY -> {
                    val line = DocumentEdits.lineAt(note.document.text, hit.start)
                    withTimeoutOrNull(1_000) { snapshotFlow { lineTops[line] }.filterNotNull().first() } ?: 0
                }
                HitKind.ATTACHMENT -> attachmentsTop
            }
            scroll.scrollTo(top)
            delay(2200); highlight = null
        }
    }
    val leave = { keyboard?.hide(); focus.clearFocus(); vm.back() }
    val barColor by animateColorAsState(if (vm.editing) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, label = "Editing header")
    Scaffold(modifier = Modifier.imePadding(), topBar = { TopAppBar(colors = TopAppBarDefaults.topAppBarColors(containerColor = barColor),
        title = { Text(if (vm.editing) "Editing" else "Note", style = MaterialTheme.typography.titleMedium) }, navigationIcon = { BackButton(back) }, actions = {
        if (vm.editing) IconButton(onClick = leave) { Icon(Icons.Default.Check, "Done") }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Note menu") }
            DropdownMenu(menu, { menu = false }) { DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null) }, onClick = { menu = false; delete = true }) }
        }
    }) }, bottomBar = { if (vm.editing) EditorToolbar(vm, editor, selectionRevision) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScrollbar(scroll).verticalScroll(scroll).onGloballyPositioned { content = it }
            .padding(start = 24.dp, end = 24.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (vm.editing) {
                OutlinedTextField(note.title, { vm.change(note.copy(title = it), typing = true) }, modifier = Modifier.fillMaxWidth().focusRequester(titleFocus),
                    placeholder = { Text("Title") }, textStyle = MaterialTheme.typography.headlineLarge, singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next), keyboardActions = KeyboardActions(onNext = { vm.bodyFocus = note.document.text.length }))
                RichBodyEditor(note.document, MaterialTheme.colorScheme.onSurface, MaterialTheme.colorScheme.primary, Modifier.fillMaxWidth().heightIn(min = 160.dp),
                    onReady = { editor = it }, onChange = vm::editBody, onSelection = { selectionRevision++ }, onLeave = leave)
            } else SelectionContainer {
                // One selection area for the whole note, so Select All and copy span every line.
                Column {
                    Text(note.displayTitle, Modifier.fillMaxWidth().clickable { vm.enterEditing() }.padding(vertical = 12.dp)
                        .background(if (highlight?.kind == HitKind.TITLE) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent), style = MaterialTheme.typography.headlineLarge)
                    ReadingBody(vm, note, highlight) { line, coordinates -> content?.let { lineTops[line] = it.localPositionOf(coordinates, Offset.Zero).y.toInt() } }
                }
            }
            AttachmentsSection(vm, note, highlight, attach, openFile, Modifier.onGloballyPositioned { c -> content?.let { attachmentsTop = it.localPositionOf(c, Offset.Zero).y.toInt() } })
        }
    }
    if (delete) AlertDialog(onDismissRequest = { delete = false }, title = { Text("Delete this note?") }, text = { Text("This permanently deletes the note and its attachments.") }, confirmButton = { TextButton(onClick = { delete = false; vm.delete() }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { delete = false }) { Text("Cancel") } })
}

/** Reading mode: one Text per line inside the caller's selection area; list markers are not selectable. */
@Composable private fun ReadingBody(vm: NotesViewModel, note: Note, highlight: dev.securenotes.search.SearchHit?, placed: (Int, LayoutCoordinates) -> Unit) {
    val document = note.document
    val starts = remember(document.text) { DocumentEdits.lineStarts(document.text) }
    document.lines.forEachIndexed { i, line ->
        val start = starts[i]
        val end = if (i + 1 < starts.size) starts[i + 1] - 1 else document.text.length
        val match = highlight?.takeIf { it.kind == HitKind.BODY && it.start >= start && it.end <= end }
        Row(Modifier.fillMaxWidth().onGloballyPositioned { placed(i, it) }, verticalAlignment = Alignment.CenterVertically) {
            DisableSelection {
                when (line.type) {
                    LineType.CHECKLIST -> Checkbox(line.checked, { vm.setChecked(i, it) }, modifier = Modifier.semantics { contentDescription = document.text.substring(start, end).ifBlank { "Checklist item" } })
                    LineType.BULLET -> Text("•", Modifier.padding(start = 4.dp, end = 12.dp), fontSize = 20.sp)
                    LineType.NUMBERED -> Text("${DocumentEdits.numberFor(document, i)}.", Modifier.padding(end = 12.dp), fontSize = 18.sp)
                    else -> Unit
                }
            }
            Text(annotatedLine(document, start, end, match, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.primary),
                Modifier.weight(1f).clickable { vm.enterEditing(end) }.padding(vertical = 4.dp),
                style = when (line.type) { LineType.HEADING1 -> MaterialTheme.typography.headlineMedium; LineType.HEADING2 -> MaterialTheme.typography.titleLarge; else -> MaterialTheme.typography.bodyLarge })
        }
    }
}

private fun annotatedLine(document: Document, start: Int, end: Int, match: dev.securenotes.search.SearchHit?, highlight: Color, linkColor: Color): AnnotatedString = buildAnnotatedString {
    val text = document.text.substring(start, end)
    append(text.ifEmpty { " " })
    document.bold.forEach { span ->
        val a = maxOf(span.start, start) - start; val b = minOf(span.end, end) - start
        if (b > a) addStyle(SpanStyle(fontWeight = FontWeight.Bold), a, b)
    }
    match?.let { if (it.end - start <= text.length) addStyle(SpanStyle(background = highlight), it.start - start, it.end - start) }
    val links = android.text.SpannableString(text)
    androidx.core.text.util.LinkifyCompat.addLinks(links, android.text.util.Linkify.WEB_URLS or android.text.util.Linkify.EMAIL_ADDRESSES or android.text.util.Linkify.PHONE_NUMBERS)
    links.getSpans(0, links.length, android.text.style.URLSpan::class.java).forEach { span ->
        addLink(LinkAnnotation.Url(span.url, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline))), links.getSpanStart(span), links.getSpanEnd(span))
    }
}

/** Formatting toggles act on every line in the editor's selection and show the current line's format. */
@Composable private fun EditorToolbar(vm: NotesViewModel, editor: BodyEditText?, selectionRevision: Int) {
    val scroll = rememberScrollState()
    val revision = vm.historyRevision
    val active = remember(editor, selectionRevision, revision) { editor?.activeType() }
    val bold = remember(editor, selectionRevision, revision) { editor?.isBold() == true }
    @Composable fun Toggle(type: LineType, label: String, icon: androidx.compose.ui.graphics.vector.ImageVector?) {
        IconToggleButton(checked = active == type, onCheckedChange = { editor?.toggleLine(type) }, enabled = editor != null) {
            if (icon != null) Icon(icon, label) else Text(label, fontWeight = FontWeight.Bold)
        }
    }
    Surface(tonalElevation = 3.dp) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().horizontalScrollbar(scroll).horizontalScroll(scroll).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::undo, enabled = revision >= 0 && vm.history.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
            IconButton(onClick = vm::redo, enabled = vm.history.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
            IconToggleButton(checked = bold, onCheckedChange = { editor?.toggleBold() }, enabled = editor != null) { Icon(Icons.Default.FormatBold, "Bold") }
            Toggle(LineType.HEADING1, "H1", null)
            Toggle(LineType.HEADING2, "H2", null)
            Toggle(LineType.BULLET, "Bullet list", Icons.AutoMirrored.Filled.FormatListBulleted)
            Toggle(LineType.NUMBERED, "Numbered list", Icons.Default.FormatListNumbered)
            Toggle(LineType.CHECKLIST, "Checklist", Icons.Default.Checklist)
        }
    }
}

/** Images and files live here, below the text, in both modes. Editing adds [+] and per-item Move/Remove. */
@Composable private fun AttachmentsSection(vm: NotesViewModel, note: Note, highlight: dev.securenotes.search.SearchHit?, attach: (String) -> Unit, openFile: (String) -> Unit, modifier: Modifier) {
    val ids = note.document.attachments
    if (ids.isEmpty() && !vm.editing) return
    var menu by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Attachments", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (vm.editing) Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.Add, "Add attachment") }
                DropdownMenu(menu, { menu = false }) { listOf("Photo", "Camera", "File").forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { menu = false; attach(option) }) } }
            }
        }
        if (ids.isEmpty()) Text("Add photos or files with +.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ids.forEachIndexed { index, id -> key(id) { AttachmentTile(vm, id, index, ids.size, highlight?.attachmentId == id, openFile) } }
        }
    }
}

@Composable private fun AttachmentTile(vm: NotesViewModel, id: String, index: Int, count: Int, highlighted: Boolean, openFile: (String) -> Unit) {
    val attachment = vm.attachments[id] ?: return
    val image = attachment.mime.startsWith("image/")
    val bitmap by attachmentBitmap(vm, id, 400)
    var menu by remember { mutableStateOf(false) }
    Box {
        Card(Modifier.size(104.dp).clickable { if (image) { vm.imageId = id; vm.screen = Screen.IMAGE } else openFile(id) },
            border = if (highlighted) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
            colors = CardDefaults.cardColors(containerColor = if (highlighted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer)) {
            val shown = bitmap
            if (image && shown != null) Image(shown.asImageBitmap(), attachment.filename, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Icon(if (image) Icons.Default.Image else Icons.AutoMirrored.Filled.InsertDriveFile, null)
                Text(attachment.filename, style = MaterialTheme.typography.labelSmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        if (vm.editing) {
            IconButton(onClick = { menu = true }, modifier = Modifier.align(Alignment.TopEnd).size(36.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = .85f), androidx.compose.foundation.shape.CircleShape)) { Icon(Icons.Default.MoreVert, "Options for ${attachment.filename}") }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Move left") }, enabled = index > 0, onClick = { menu = false; vm.moveAttachment(id, -1) })
                DropdownMenuItem(text = { Text("Move right") }, enabled = index < count - 1, onClick = { menu = false; vm.moveAttachment(id, 1) })
                DropdownMenuItem(text = { Text("Remove") }, onClick = { menu = false; vm.removeAttachment(id) })
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

@Composable private fun SettingsScreen(vm: NotesViewModel, back: () -> Unit, reveal: () -> Unit, restore: () -> Unit, chooseBackup: () -> Unit) {
    val status by vm.app.autoBackup.status.collectAsStateWithLifecycle()
    val scroll = rememberScrollState()
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { BackButton(back) }) }) { padding ->
        Column(Modifier.padding(padding).verticalScrollbar(scroll).verticalScroll(scroll).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Recovery passphrase", style = MaterialTheme.typography.titleLarge)
            Text("Your ${dev.securenotes.security.Passphrase.WORDS}-word passphrase opens your notes on a new phone and encrypts your backups.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = reveal, modifier = Modifier.fillMaxWidth()) { Text("View recovery passphrase") }
            Spacer(Modifier.height(8.dp))
            Text("Automatic backup", style = MaterialTheme.typography.titleLarge)
            val location = status?.location
            if (location == null) {
                Text("Choose a file, for example in Dropbox or Google Drive. Notes keeps it updated with an encrypted copy of all notes and attachments whenever they change.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(onClick = chooseBackup, modifier = Modifier.fillMaxWidth()) { Text("Choose backup file") }
            } else {
                Text(location, style = MaterialTheme.typography.titleMedium)
                Text(status?.savedAt?.let { "Last saved " + java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(it)) } ?: "Not saved yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                status?.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                FilledTonalButton(onClick = vm::backupNow, modifier = Modifier.fillMaxWidth()) { Text("Back up now") }
                OutlinedButton(onClick = chooseBackup, modifier = Modifier.fillMaxWidth()) { Text("Change backup file") }
                TextButton(onClick = vm::turnOffBackup, modifier = Modifier.fillMaxWidth()) { Text("Turn off automatic backup") }
            }
            OutlinedButton(onClick = restore, modifier = Modifier.fillMaxWidth()) { Text("Restore from backup") }
            Spacer(Modifier.height(16.dp)); Text("${stringResource(R.string.app_name)} · ${dev.securenotes.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable private fun BackButton(back: () -> Unit) { IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }
