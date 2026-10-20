package dev.securenotes

import dev.securenotes.backup.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import java.io.*
import java.nio.file.Files

@OptIn(ExperimentalCoroutinesApi::class)
class BackupCoordinatorTest {
    private val temporary = mutableListOf<File>()
    @After fun cleanup() { temporary.forEach { it.deleteRecursively() } }
    private fun TestScope.fixture(): Fixture = Fixture(StandardTestDispatcher(testScheduler), Files.createTempDirectory("backup-test").toFile().also(temporary::add))
    private class Fixture(val dispatcher: CoroutineDispatcher, val directory: File) {
        var unlocked = true
        val files = FakeFiles()
        val source = FakeSource(directory)
        val settings = FakeSettings { unlocked }
        var backup = coordinator()
        fun coordinator() = AutoBackup(files, settings, source, { unlocked }, { 123456789L }, dispatcher)
        suspend fun setup() { backup.refresh(); assertTrue(backup.choose("folder").accepted); files.events.clear() }
        suspend fun restart() { backup = coordinator(); backup.refresh() }
    }
    private class FakeSource(val directory: File) : BackupSource {
        override var revision = 1L
        var phrase = "a".repeat(64)
        var empty = false
        var failure: Exception? = null
        var onPrepare: suspend () -> Unit = {}
        var afterSnapshot: suspend (BackupInspection) -> Unit = {}
        var onAttachment: suspend (String) -> Unit = {}
        var prepares = 0
        var wholePrepares = 0
        var inspections = 0
        val artifacts = mutableListOf<File>()
        /** Attachment ID to content, in note order. */
        val attachments = linkedMapOf<String, ByteArray>()
        /** IDs in the order their files were encrypted. */
        val encrypted = mutableListOf<String>()
        /** The attachment filenames each prepared index named. */
        val indexes = mutableListOf<Map<String, String>>()
        /** What each prepared file contains, so a check authenticates exactly what was written and nothing else. */
        private val indexContents = mutableMapOf<String, CheckedIndex>()
        private val attachmentContents = mutableMapOf<String, String>()
        /** Attachment IDs in the order a full check read their files. */
        val checked = mutableListOf<String>()
        var onCheck: suspend (String) -> Unit = {}
        override suspend fun checkIndex(input: InputStream): CheckedIndex {
            val index = indexContents[input.readBytes().decodeToString()] ?: throw IOException("authentication failed")
            return CheckedIndex(index.attachments, index.files, index.key?.copyOf())
        }
        override suspend fun checkAttachment(input: InputStream, key: ByteArray, attachment: BackupAttachment) {
            onCheck(attachment.id)
            if (attachmentContents[input.readBytes().decodeToString()] != attachment.id) throw IOException("authentication failed")
            checked += attachment.id
        }
        private fun captured() = attachments.map { (id, bytes) -> BackupAttachment(id, bytes.size.toLong(), sha(bytes)) }
        override suspend fun inspect(): BackupInspection {
            inspections++
            failure?.let { throw it }
            return BackupInspection(revision.toString().padStart(64, '0'), phrase, revision, empty)
        }
        private fun artifact(text: String, content: BackupInspection): PreparedBackup {
            val file = File(directory, "artifact-${artifacts.size}").apply { writeText(text) }
            artifacts += file
            return PreparedBackup(file, content, file.length(), file.inputStream().use(::ciphertextDigest))
        }
        override suspend fun prepare(progress: (String) -> Unit): PreparedBackup {
            wholePrepares++
            val content = inspect()
            val text = "whole $wholePrepares revision ${content.revision}"
            indexContents[text] = CheckedIndex(captured(), null, null)
            return artifact(text, content)
        }
        override suspend fun capture(progress: (String) -> Unit): BackupSnapshot {
            prepares++; onPrepare()
            val content = inspect()
            afterSnapshot(content)
            return BackupSnapshot(content, captured())
        }
        override suspend fun prepareAttachment(snapshot: BackupSnapshot, attachment: BackupAttachment, key: ByteArray): PreparedBackup {
            onAttachment(attachment.id)
            val bytes = attachments[attachment.id]?.takeIf { sha(it) == attachment.sha256 } ?: throw SnapshotChanged()
            encrypted += attachment.id
            // Each encryption differs, as Tink's fresh randomness makes it.
            val text = "attachment ${attachment.id} ${encrypted.size} ${bytes.decodeToString()}"
            attachmentContents[text] = attachment.id
            return artifact(text, snapshot.content)
        }
        override suspend fun prepareIndex(snapshot: BackupSnapshot, key: ByteArray, folder: String?, files: Map<String, String>): PreparedBackup {
            indexes += files
            val text = "ciphertext $prepares folder $folder files $files revision ${snapshot.content.revision}"
            indexContents[text] = CheckedIndex(snapshot.attachments, files, key.copyOf())
            return artifact(text, snapshot.content)
        }
    }
    private class FakeSettings(val unlocked: () -> Boolean) : BackupSettingsStore {
        var record = BackupConfiguration()
        var writes = 0
        var failWrite: Int? = null
        var applyBeforeFailure = false
        val keys = mutableMapOf<String, ByteArray>()
        val files = mutableMapOf<String, MutableMap<String, AttachmentFile>>()
        var recordWrites = 0
        var failRecord: Int? = null
        var applyRecordBeforeFailure = false
        override suspend fun load() = record
        override suspend fun save(configuration: BackupConfiguration, beginWrite: () -> Unit) {
            if (!unlocked()) throw CancellationException()
            beginWrite(); writes++
            if (writes == failWrite) {
                if (applyBeforeFailure) record = configuration
                throw IOException("private path that must never be displayed")
            }
            record = configuration
        }
        private fun commit(beginWrite: () -> Unit, apply: () -> Unit) {
            if (!unlocked()) throw CancellationException()
            beginWrite(); recordWrites++
            if (recordWrites == failRecord) {
                if (applyRecordBeforeFailure) apply()
                throw IOException("private")
            }
            apply()
        }
        override suspend fun key(pairTag: String) = keys[pairTag]?.copyOf()
        override suspend fun saveKey(pairTag: String, key: ByteArray, beginWrite: () -> Unit) = commit(beginWrite) { keys[pairTag] = key.copyOf() }
        override suspend fun files(pairTag: String): Map<String, AttachmentFile> = files[pairTag].orEmpty().toMap()
        override suspend fun saveFile(pairTag: String, file: AttachmentFile, beginWrite: () -> Unit) = commit(beginWrite) { files.getOrPut(pairTag) { mutableMapOf() }[file.id] = file }
        override suspend fun forget(pairTag: String) {
            if (!unlocked()) throw CancellationException()
            keys.remove(pairTag); files.remove(pairTag)
        }
    }
    private class FakeFiles : BackupFiles {
        val data = mutableMapOf<String, ByteArray>()
        val grants = mutableSetOf<String>()
        val events = mutableListOf<String>()
        var failure: String? = null
        var writeMode = "rwt"
        var createUri: String? = null
        var collisionSuffix = ""
        var onWrite: () -> Unit = {}
        var onCreate: () -> Unit = {}
        var counter = 0
        var grantChecks = 0
        var onGrant: () -> Unit = {}
        var name: String? = null
        var missingName = false
        var nameFailure: Exception? = null
        var openFailure: Exception? = null
        var readFailure: Exception? = null
        val directories = mutableSetOf<String>()
        var listFailure: Exception? = null
        /** Present files a listing omits, and sizes a listing or descriptor reports instead of the real one. */
        val hidden = mutableSetOf<String>()
        val listedSizes = mutableMapOf<String, Long?>()
        val measuredSizes = mutableMapOf<String, Long?>()
        var measureFailure: Exception? = null
        /** Documents that open but fail partway through reading, and listed documents that report not found on opening. */
        val unreadable = mutableSetOf<String>()
        val unopenable = mutableSetOf<String>()
        /** Stale listing entries for documents that no longer exist. */
        val phantoms = mutableListOf<BackupChild>()
        private val names = mutableMapOf<String, String>()
        override fun hasGrant(folder: String): Boolean { grantChecks++; onGrant(); return folder in grants }
        override fun retain(folder: String) { grants += folder; events += "retain:$folder" }
        override fun release(folder: String) { grants -= folder; events += "release:$folder" }
        override fun create(folder: String, name: String): String {
            onCreate()
            val uri = createUri ?: "$folder/${++counter}"
            data.putIfAbsent(uri, byteArrayOf())
            names[uri] = name + collisionSuffix
            return uri
        }
        override fun createDirectory(folder: String, name: String): String {
            onCreate()
            val uri = "$folder/${++counter}"
            directories += uri; names[uri] = name + collisionSuffix
            events += "mkdir:$uri"
            return uri
        }
        override fun children(directory: String): List<BackupChild> {
            events += "list:$directory"
            listFailure?.let { throw it }
            return data.keys.filter { it.startsWith("$directory/") && '/' !in it.removePrefix("$directory/") && it !in hidden }
                .map { BackupChild(it, names[it] ?: "", if (it in listedSizes) listedSizes[it] else data.getValue(it).size.toLong()) } +
                phantoms.filter { it.uri.startsWith("$directory/") }
        }
        override fun measure(uri: String): Long? {
            events += "measure:$uri"
            measureFailure?.let { throw it }
            val bytes = data[uri] ?: throw FileNotFoundException()
            return if (uri in measuredSizes) measuredSizes[uri] else bytes.size.toLong()
        }
        override fun displayName(uri: String): String? { nameFailure?.let { throw it }; return if (missingName) name else names[uri] }
        /** A rename by the user or a sync app, on a provider that keeps the document's URI. */
        fun rename(uri: String, name: String) { names[uri] = name }
        /** Another document with the same content, such as a sync app's copy. */
        fun copy(from: String, uri: String, name: String) { data[uri] = data.getValue(from).clone(); names[uri] = name }
        override fun delete(uri: String) {
            events += "delete:$uri"; data.remove(uri)
            if (directories.remove(uri)) data.keys.removeAll { it.startsWith("$uri/") }
        }
        override fun openRead(uri: String): InputStream {
            events += "read:$uri"
            readFailure?.let { throw it }
            if (failure == "read" || failure == "read:$uri") throw IOException()
            if (uri in unopenable) throw FileNotFoundException()
            val bytes = data[uri] ?: throw FileNotFoundException()
            if (uri in unreadable) return object : InputStream() {
                override fun read(): Int = throw IOException("provider")
            }
            return bytes.inputStream()
        }
        override fun openWrite(uri: String, mode: String): BackupOutput {
            events += "open:$uri:$mode"
            openFailure?.let { throw it }
            if (failure == "security") throw SecurityException()
            if (failure == "open" || mode != writeMode) throw IOException()
            val buffer = ByteArrayOutputStream()
            fun check(step: String) { events += step; if (failure == step) throw IOException("sensitive file name") }
            return object : BackupOutput {
                override val stream = object : OutputStream() {
                    override fun write(value: Int) { buffer.write(value) }
                    override fun write(bytes: ByteArray, offset: Int, length: Int) {
                        onWrite(); check("write"); buffer.write(bytes, offset, length)
                    }
                    override fun flush() { check("flush") }
                }
                override fun truncate(length: Long) { check("truncate") }
                override fun sync() { check("sync") }
                override fun close() {
                    data[uri] = buffer.toByteArray().let { if (failure == "verify") it + byteArrayOf(0) else it }
                    check("close")
                }
            }
        }
    }

    @Test fun `all provider failure steps preserve active evidence and retry unchanged forced work`() = runTest {
        for (step in listOf("open", "write", "flush", "truncate", "sync", "close", "verify", "read:folder/1")) {
            val f = fixture(); f.setup()
            val before = f.settings.record
            val active = before.slots[before.active]
            val bytes = f.files.data.getValue(active.uri).clone()
            f.files.writeMode = if (step == "truncate") "w" else "rwt"
            f.files.failure = step
            f.backup.run(force = true)
            assertEquals(step, before.active, f.settings.record.active)
            assertEquals(step, active, f.settings.record.slots[before.active])
            assertArrayEquals(step, bytes, f.files.data[active.uri])
            assertTrue(step, f.settings.record.pending)
            assertNotNull(f.backup.status.value?.error)
            assertFalse(f.backup.status.value!!.error!!.diagnostic.contains("sensitive"))
            f.files.failure = null
            f.backup.run()
            assertEquals(step, 1 - before.active, f.settings.record.active)
            assertFalse(f.settings.record.pending); assertNull(f.settings.record.error)
            assertTrue(f.source.artifacts.none { it.exists() })
        }
    }
    @Test fun `active mismatch and active read errors never open inactive slot`() = runTest {
        for (damage in listOf(true, false)) {
            val f = fixture(); f.setup()
            val active = f.settings.record.slots[1].uri
            if (damage) f.files.data[active] = byteArrayOf(7) else f.files.failure = "read:$active"
            f.backup.run(true)
            assertTrue(f.files.events.none { it.startsWith("open:") })
            assertEquals(if (damage) BackupProblem.ACTIVE_DAMAGED else BackupProblem.IO, f.settings.record.error?.problem)
            f.files.events.clear(); f.files.failure = null
            f.backup.run(true)
            assertEquals(!damage, f.files.events.any { it.startsWith("open:") })
        }
    }
    @Test fun `fallback occurs only at opening and security never falls back`() = runTest {
        val f = fixture(); f.setup()
        f.files.failure = "security"; f.backup.run(true)
        assertEquals(1, f.files.events.count { it.startsWith("open:") })
        assertEquals(BackupProblem.ACCESS, f.settings.record.error?.problem)
        f.files.events.clear(); f.files.failure = "write"; f.backup.refreshGrant(); f.backup.run(true)
        assertEquals(1, f.files.events.count { it.startsWith("open:") })
    }
    @Test fun `unreadable active retries fail before encryption and security stays retryable after refresh`() = runTest {
        for (failure in listOf(IOException(), IOException("private", SecurityException("secret")))) {
            val f = fixture(); f.setup(); val before = f.settings.record
            f.files.readFailure = failure
            repeat(3) {
                f.backup.refreshGrant(); f.backup.run(true)
                assertEquals(1, f.source.prepares)
                assertEquals("verify-active", f.settings.record.error!!.step)
                assertEquals(if (failure.hasSecurityCause()) BackupProblem.ACCESS else BackupProblem.IO, f.settings.record.error!!.problem)
                assertEquals(before.slots, f.settings.record.slots)
                assertTrue(f.files.events.none { it.startsWith("open:") })
            }
            f.files.readFailure = null; f.backup.refreshGrant(); f.backup.run()
            assertEquals(2, f.source.prepares); assertNull(f.settings.record.error)
        }
    }
    @Test fun `active integrity is checked before encryption and again immediately before writing`() = runTest {
        for (duringPreparation in listOf(false, true)) {
            val f = fixture(); f.setup()
            val active = f.settings.record.slots[f.settings.record.active].uri
            val damage = { f.files.data[active] = byteArrayOf(7) }
            if (duringPreparation) f.source.afterSnapshot = { damage() } else damage()
            f.backup.run(true)
            assertEquals(if (duringPreparation) 2 else 1, f.source.prepares)
            assertEquals(if (duringPreparation) 2 else 1, f.files.events.count { it == "read:$active" })
            assertEquals(BackupProblem.ACTIVE_DAMAGED, f.settings.record.error!!.problem)
            assertTrue(f.files.events.none { it.startsWith("open:") })
            assertTrue(f.source.artifacts.none { it.exists() })
        }
        val f = fixture(); f.setup(); f.backup.run()
        assertTrue(f.files.events.isEmpty()); assertEquals(1, f.source.prepares)
    }
    @Test fun `pending survives restart and empty automatic backups never replace content`() = runTest {
        val f = fixture(); f.setup()
        f.files.failure = "write"; f.backup.run(true)
        f.files.failure = null; f.restart()
        assertTrue(f.backup.mayWrite()); f.backup.run()
        assertFalse(f.settings.record.pending)
        f.source.empty = true; f.source.revision++; f.files.events.clear()
        f.backup.run(); assertTrue(f.files.events.none { it.startsWith("open:") })
        f.backup.run(true); assertTrue(f.files.events.any { it.startsWith("open:") })
    }
    @Test fun `uncertain pending or final settings commit blocks until restart including lock and restore`() = runTest {
        for (offset in 1..2) for (applied in listOf(false, true)) {
            val f = fixture(); f.setup()
            f.settings.failWrite = f.settings.writes + offset; f.settings.applyBeforeFailure = applied
            f.backup.run(true)
            assertTrue(f.backup.status.value!!.restartRequired)
            val count = f.files.events.size
            f.backup.run(true); assertFalse(f.backup.choose("other").accepted); f.backup.turnOff()
            assertEquals(count, f.files.events.size)
            f.unlocked = false; f.backup.clearStatus(); assertNull(f.backup.status.value)
            f.unlocked = true; f.backup.refresh(); assertTrue(f.backup.status.value!!.restartRequired)
            f.backup.publishRestore(f.source.phrase) { config, begin -> begin(); f.settings.record = config }
            assertTrue(f.backup.status.value!!.restartRequired)
            f.settings.failWrite = null; f.restart(); assertFalse(f.backup.status.value!!.restartRequired)
            f.backup.run(); assertFalse(f.settings.record.pending)
        }
    }
    @Test fun `lock during ciphertext copy leaves pending and prior active without restart block`() = runTest {
        val f = fixture(); f.setup(); val before = f.settings.record
        f.files.onWrite = { f.unlocked = false; f.backup.clearStatus() }
        try { f.backup.run(true) } catch (_: CancellationException) { }
        assertEquals(before.active, f.settings.record.active); assertTrue(f.settings.record.pending)
        assertNull(f.backup.status.value)
        f.unlocked = true; f.files.onWrite = {}; f.backup.refresh()
        assertFalse(f.backup.status.value!!.restartRequired)
        f.backup.run(); assertFalse(f.settings.record.pending)
    }
    @Test fun `setup keeps actual collision names and rejects duplicate or managed URIs`() = runTest {
        val f = fixture(); f.files.collisionSuffix = " (2)"; f.setup()
        assertTrue(f.settings.record.slots.all { it.name.endsWith(" (2)") })
        val old = f.settings.record
        f.files.createUri = old.slots.first().uri
        assertFalse(f.backup.choose("other").accepted); assertEquals(old, f.settings.record.copy(setup = null))
        assertTrue(old.slots.all { it.uri in f.files.data })
        f.files.createUri = "other/duplicate"
        assertFalse(f.backup.choose("other").accepted); assertEquals(old, f.settings.record.copy(setup = null))
        // The duplicate was recorded before it was rejected; discarding removes it and the permission the setup took.
        assertEquals(listOf("other/duplicate"), f.settings.record.setup?.created)
        f.files.createUri = null; assertEquals(BackupAdmission.ACCEPTED, f.backup.discardSetup())
        assertEquals(old, f.settings.record)
        assertFalse("other/duplicate" in f.files.data); assertFalse("other" in f.files.grants)
    }
    @Test fun `setup cancellation keeps new files for resume and discarding removes only them`() = runTest {
        val f = fixture(); f.setup(); val old = f.settings.record
        f.files.onCreate = { if (f.files.counter == 3) throw CancellationException() }
        try { f.backup.choose("folder").accepted } catch (_: CancellationException) { }
        assertEquals(1, f.settings.record.setup?.created?.size); assertEquals(old, f.settings.record.copy(setup = null))
        f.files.onCreate = {}; f.backup.discardSetup()
        assertEquals(old.managedUris, f.files.data.keys)
        assertTrue("folder" in f.files.grants); assertEquals(old, f.settings.record)
    }
    @Test fun `uncertain setup retains both pairs and grants while failed disable never releases`() = runTest {
        val f = fixture(); f.setup(); val old = f.settings.record
        // Activation is a setup's sixth configuration write without attachments: start, then each index's URI and name.
        f.settings.failWrite = f.settings.writes + 6
        assertFalse(f.backup.choose("other").accepted)
        assertEquals(4, f.files.data.size); assertEquals(setOf("folder", "other"), f.files.grants)
        assertEquals(old, f.settings.record.copy(setup = null))
        f.settings.failWrite = null; f.restart()
        f.settings.failWrite = f.settings.writes + 1
        try { f.backup.turnOff(); fail("Expected failure") } catch (_: IOException) { }
        assertTrue("folder" in f.files.grants); assertEquals(old, f.settings.record.copy(setup = null))
    }
    @Test fun `disable commits before grant release and never deletes backup files`() = runTest {
        val f = fixture(); f.setup(); val uris = f.files.data.keys.toSet()
        f.backup.turnOff()
        assertEquals(BackupMode.DISABLED, f.settings.record.mode); assertTrue(f.files.grants.isEmpty())
        assertEquals(uris, f.files.data.keys)
    }
    @Test fun `phrase changes pause old pair until reviewed and new pair is committed`() = runTest {
        val f = fixture(); f.setup(); val old = f.settings.record
        f.source.phrase = "b".repeat(64)
        f.backup.publishRestore(f.source.phrase) { config, begin -> begin(); f.settings.record = config }
        assertEquals(old.slots, f.settings.record.slots)
        f.backup.run(true); assertTrue(f.files.events.none { it.startsWith("open:") })
        assertFalse(f.backup.choose("folder").accepted); assertEquals(old.slots, f.settings.record.slots)
        f.backup.phraseReviewed(f.source.phrase)
        assertTrue(f.backup.choose("folder").accepted); assertEquals(4, f.files.data.size)
        assertEquals(f.source.phrase, f.settings.record.phraseHash)
    }
    @Test fun `missing phrase and migration never write the old destination`() = runTest {
        val f = fixture()
        f.settings.record = BackupConfiguration(mode = BackupMode.LEGACY, legacyUri = "old", legacyName = "old.ssnb", savedAt = 42)
        f.files.data["old"] = byteArrayOf(1); f.files.grants += "old"
        f.backup.refresh(); f.backup.run(true)
        assertTrue(f.files.events.isEmpty()); assertEquals(42L, f.backup.status.value?.savedAt)
        f.source.failure = BackupFailure(BackupProblem.PHRASE_MISSING)
        assertFalse(f.backup.choose("folder").accepted); assertEquals(setOf("old"), f.files.grants)
        f.source.failure = null; assertTrue(f.backup.choose("folder").accepted); assertTrue("old" in f.files.data)
    }
    @Test fun `oversized attempt waits for new revision including edits during failure`() = runTest {
        val f = fixture(); f.setup(); val revision = f.source.revision
        f.source.failure = ManifestTooLarge(revision); f.backup.run(true)
        assertFalse(f.backup.mayWrite()); assertTrue(f.backup.mayWrite(pendingEdits = true)); f.source.revision++
        assertTrue(f.backup.mayWrite()); f.source.failure = null; f.backup.run()
        assertNull(f.settings.record.error)
    }
    @Test fun `restore publication waits for save and takes latest configuration`() = runTest {
        val f = fixture(); f.setup()
        val entered = CompletableDeferred<Unit>(); val resume = CompletableDeferred<Unit>()
        f.source.onPrepare = { entered.complete(Unit); resume.await() }
        val save = launch { f.backup.run(true) }; entered.await()
        assertFalse(f.backup.choose("another").accepted)
        var copied: BackupConfiguration? = null
        val restore = launch { f.backup.publishRestore(f.source.phrase) { config, begin -> begin(); copied = config; f.settings.record = config } }
        runCurrent(); assertNull(copied); resume.complete(Unit); save.join(); restore.join()
        assertEquals(0, copied!!.active); assertTrue(copied!!.pending)
        f.source.onPrepare = {}; f.backup.turnOff()
        f.backup.publishRestore(f.source.phrase) { config, _ -> assertEquals(BackupMode.DISABLED, config.mode) }
    }
    @Test fun `manifest limit unchanged below at and above boundary`() {
        val limit = BackupService.MANIFEST_LIMIT
        for (size in listOf(limit - 1, limit)) {
            BackupService.checkManifestSize(size, 7)
            assertEquals(size, BackupService.readBounded(ByteArray(size).inputStream(), limit).size)
        }
        assertThrows(ManifestTooLarge::class.java) { BackupService.checkManifestSize(limit + 1, 7) }
        assertThrows(IllegalArgumentException::class.java) { BackupService.readBounded(ByteArray(limit + 1).inputStream(), limit) }
    }
    @Test fun `scheduler debounces changes independently balances holds and stops when disabled`() = runTest {
        val f = fixture(); f.setup()
        val changes = kotlinx.coroutines.flow.MutableStateFlow(f.source.revision)
        var holds = 0; var flushes = 0
        val scheduler = BackupScheduler(backgroundScope, f.backup, changes, { f.unlocked }, { false }, { flushes++ }, { holds++; { holds-- } }, { fail("Flush failed") })
        scheduler.onForeground(); scheduler.onUnlocked(); runCurrent()
        val prepared = f.source.prepares
        f.source.revision++; changes.value = f.source.revision
        advanceTimeBy(1_001); runCurrent(); assertEquals(prepared, f.source.prepares)
        advanceTimeBy(58_999); runCurrent(); assertEquals(prepared + 1, f.source.prepares)
        assertEquals(0, holds)
        f.source.revision++; changes.value = f.source.revision; scheduler.onLeaving()
        assertEquals(1, holds); runCurrent(); assertEquals(0, holds)
        scheduler.onForeground(); runCurrent()
        val manual = scheduler.manual().job!!; assertEquals(1, holds); manual.cancel(); runCurrent(); assertEquals(0, holds)
        scheduler.disable(); runCurrent(); val stopped = f.source.prepares
        f.source.revision++; changes.value = f.source.revision; advanceTimeBy(120_000); runCurrent()
        assertEquals(stopped, f.source.prepares)
        scheduler.onLocked(); assertNull(f.backup.status.value); assertEquals(0, holds)
    }
    @Test fun `later edits do not cancel running saves and normal foreground retries failures`() = runTest {
        val f = fixture(); f.setup()
        val changes = kotlinx.coroutines.flow.MutableStateFlow(f.source.revision)
        var holds = 0
        val scheduler = BackupScheduler(backgroundScope, f.backup, changes, { f.unlocked }, { false }, {}, { holds++; { holds-- } }, {})
        scheduler.onForeground(); scheduler.onUnlocked(); runCurrent()
        val resume = CompletableDeferred<Unit>(); var cancelled = false
        f.source.onPrepare = { try { resume.await() } catch (e: CancellationException) { cancelled = true; throw e } }
        f.source.revision++; changes.value = f.source.revision; scheduler.trigger(); runCurrent()
        f.source.revision++; changes.value = f.source.revision; advanceTimeBy(60_001); runCurrent()
        assertFalse(cancelled); resume.complete(Unit); runCurrent(); assertEquals(0, holds)
        f.source.onPrepare = {}; f.files.failure = "write"; scheduler.manual(); runCurrent()
        assertNotNull(f.settings.record.error); f.files.failure = null
        scheduler.onLeaving(); scheduler.onForeground(); runCurrent()
        assertNull(f.settings.record.error); assertEquals(0, holds); scheduler.onLocked()
    }
    @Test fun `lock cancels preparation without pending writes and releases its service hold`() = runTest {
        val f = fixture(); f.setup(); val before = f.settings.record
        val entered = CompletableDeferred<Unit>()
        f.source.onPrepare = { entered.complete(Unit); awaitCancellation() }
        var holds = 0
        val scheduler = BackupScheduler(backgroundScope, f.backup, kotlinx.coroutines.flow.MutableStateFlow(1L), { f.unlocked }, { false }, {}, { holds++; { holds-- } }, {})
        scheduler.onForeground(); scheduler.manual(); entered.await()
        assertEquals(1, holds)
        f.unlocked = false; scheduler.onLocked(); runCurrent()
        assertEquals(0, holds); assertEquals(before, f.settings.record)
        assertTrue(f.files.events.none { it.startsWith("open:") }); assertNull(f.backup.status.value)
    }
    @Test fun `lost grants wait and setup busy rejection never takes a grant`() = runTest {
        val f = fixture(); f.setup()
        f.files.grants.clear(); f.backup.run(true); val count = f.source.prepares
        f.backup.run(true); assertEquals(count, f.source.prepares); assertFalse(f.backup.mayWrite())
        f.files.grants += "folder"; assertFalse(f.backup.mayWrite()); f.backup.refreshGrant(); assertTrue(f.backup.mayWrite()); f.backup.run(); assertNull(f.settings.record.error)
        val resume = CompletableDeferred<Unit>(); val entered = CompletableDeferred<Unit>()
        f.source.onPrepare = { entered.complete(Unit); resume.await() }
        val save = launch { f.backup.run(true) }; entered.await()
        assertFalse(f.backup.choose("other").accepted); assertFalse("other" in f.files.grants)
        resume.complete(Unit); save.join()
    }
    @Test fun `uncertain restore configuration transfer blocks future mutations`() = runTest {
        val f = fixture(); f.setup()
        try { f.backup.publishRestore(f.source.phrase) { _, begin -> begin(); throw IOException() }; fail("Expected failure") } catch (_: IOException) { }
        assertTrue(f.backup.status.value!!.restartRequired)
        assertFalse(f.backup.choose("other").accepted)
    }
    @Test fun `restored content clears obsolete source errors but preserves damaged active evidence`() = runTest {
        val f = fixture(); f.setup(); val pair = f.settings.record
        for (problem in listOf(BackupProblem.PHRASE_CHANGED, BackupProblem.PHRASE_MISSING, BackupProblem.MANIFEST_TOO_LARGE)) {
            f.settings.record = pair.copy(error = BackupError(problem, "old-source", revision = f.source.revision))
            f.backup.publishRestore(f.source.phrase) { config, begin -> begin(); f.settings.record = config }
            assertNull(f.settings.record.error); assertTrue(f.backup.mayWrite())
        }
        f.settings.record = pair.copy(error = BackupError(BackupProblem.ACTIVE_DAMAGED, "verify-active"))
        f.backup.publishRestore(f.source.phrase) { config, begin -> begin(); f.settings.record = config }
        assertEquals(BackupProblem.ACTIVE_DAMAGED, f.settings.record.error?.problem); assertFalse(f.backup.mayWrite())
        var holds = 0
        val scheduler = BackupScheduler(backgroundScope, f.backup, kotlinx.coroutines.flow.MutableStateFlow(1L), { true }, { false }, {}, { holds++; {} }, {})
        scheduler.onForeground(); assertEquals(BackupAdmission.INELIGIBLE, scheduler.manual().admission); assertEquals(0, holds)
    }

    @Test fun `oversize suppression survives lock but never a process restart with matching counters`() = runTest {
        val f = fixture(); f.setup()
        f.source.failure = ManifestTooLarge(f.source.revision); f.backup.run(true)
        assertNull(f.settings.record.error!!.revision)
        f.unlocked = false; f.backup.clearStatus(); f.unlocked = true; f.backup.refresh()
        assertFalse(f.backup.mayWrite(force = true))
        // Simulate a record written by the old implementation, with a coincidentally matching counter.
        f.settings.record = f.settings.record.copy(error = f.settings.record.error!!.copy(revision = f.source.revision))
        f.restart(); assertTrue(f.backup.mayWrite())
        f.backup.run(); assertFalse(f.backup.mayWrite())
        f.source.failure = null
        f.backup.publishRestore(f.source.phrase) { config, begin -> begin(); f.settings.record = config }
        assertTrue(f.backup.mayWrite()); f.backup.run(); assertNull(f.settings.record.error)
    }
    @Test fun `oversize captures failed snapshot revision even when an edit arrives during preparation`() = runTest {
        val f = fixture(); f.setup()
        f.source.onPrepare = { val captured = f.source.revision; f.source.revision++; throw ManifestTooLarge(captured) }
        f.backup.run(true)
        assertTrue(f.backup.mayWrite()); assertNull(f.settings.record.error!!.revision)
    }
    @Test fun `eligibility uses cached grants and stale access is rechecked inside error boundary`() = runTest {
        val f = fixture(); f.setup()
        f.files.grants.clear()
        val checks = f.files.grantChecks
        repeat(20) { f.backup.mayWrite(force = true); f.backup.canMutate() }
        assertEquals(checks, f.files.grantChecks)
        f.backup.run(true); assertEquals(BackupProblem.ACCESS, f.backup.status.value!!.error!!.problem)
        f.files.grants += "folder"
        assertFalse(f.backup.mayWrite()); f.backup.refreshGrant(); assertTrue(f.backup.mayWrite())
        f.files.onGrant = { throw IOException("secret", SecurityException("private")) }
        f.backup.run(); assertEquals(BackupProblem.ACCESS, f.backup.status.value!!.error!!.problem)
        assertFalse(f.backup.status.value!!.error!!.diagnostic.contains("private"))
    }
    @Test fun `grant checks run on IO including storage attempts`() = runBlocking {
        val directory = Files.createTempDirectory("grant-thread-test").toFile().also(temporary::add)
        java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "backup-provider-io") }.asCoroutineDispatcher().use { dispatcher ->
            val f = Fixture(dispatcher, directory)
            f.files.onGrant = { assertTrue(Thread.currentThread().name.startsWith("backup-provider-io")) }
            f.setup(); f.backup.refreshGrant(); f.backup.run(true)
            assertNull(f.settings.record.error)
        }
    }
    @Test fun `wrapped security stops fallback and cycle safe diagnostics are bounded and redacted`() = runTest {
        val f = fixture(); f.setup()
        var nested: Throwable = SecurityException("secret name")
        repeat(20) { nested = IOException("private path", nested) }
        f.files.openFailure = IOException("private", nested); f.backup.run(true)
        assertEquals(1, f.files.events.count { it.startsWith("open:") })
        assertEquals(BackupProblem.ACCESS, f.settings.record.error!!.problem)
        assertEquals(5, f.settings.record.error!!.diagnostic.split(" / ").size)
        val first = IOException("secret"); val second = IllegalStateException("private")
        first.initCause(second); second.initCause(first)
        assertEquals(listOf(first, second), first.causeChain().toList())
        assertFalse(first.hasSecurityCause())
        assertEquals("IOException / IllegalStateException", f.files.diagnostic(first))
    }
    @Test fun `unknown display names use labeled fallback while security and cancellation clean recorded URIs`() = runTest {
        for (value in listOf(null, "", "  ")) {
            val f = fixture(); f.files.missingName = true; f.files.name = value; f.setup()
            assertTrue(f.settings.record.slots.all { !it.nameConfirmed && it.name.endsWith(".ssnb") })
        }
        val f = fixture(); f.files.nameFailure = IOException("private"); f.setup()
        assertTrue(f.settings.record.slots.none { it.nameConfirmed })
        val old = f.settings.record
        f.files.nameFailure = IOException("private", SecurityException())
        assertEquals(BackupAdmission.FAILED, f.backup.choose("other"))
        assertEquals(BackupProblem.ACCESS, f.backup.status.value!!.setupError!!.problem)
        // The document was recorded before its name was asked for, so discarding removes it.
        assertEquals(1, f.settings.record.setup?.created?.size)
        f.files.nameFailure = null; f.backup.discardSetup()
        assertEquals(old.managedUris, f.files.data.keys); assertFalse("other" in f.files.grants)
        f.files.nameFailure = CancellationException()
        try { f.backup.choose("other"); fail("Expected cancellation") } catch (_: CancellationException) { }
        f.files.nameFailure = null; f.backup.discardSetup()
        assertEquals(old.managedUris, f.files.data.keys); assertEquals(old, f.settings.record)
        val decoded = dev.securenotes.document.documentJson.decodeFromString<BackupSlot>("""{"uri":"old","name":"old.ssnb","length":1,"sha256":"${"a".repeat(64)}"}""")
        assertTrue(decoded.nameConfirmed)
    }
    private class Scheduling(scope: CoroutineScope, val f: Fixture) {
        var holds = 0
        var acquisitions = 0
        var releases = 0
        var pendingEdits = false
        var flushes = 0
        var failures = 0
        var onFlush: suspend () -> Unit = {}
        val changes = kotlinx.coroutines.flow.MutableStateFlow(f.source.revision)
        val scheduler = BackupScheduler(scope, f.backup, changes, { f.unlocked }, { pendingEdits },
            { flushes++; onFlush() }, { acquisitions++; holds++; { releases++; holds-- } }, { failures++ })
    }
    @Test fun `leaving after snapshot capture flushes and backs up later edits under the original hold`() = runTest {
        for (manual in listOf(false, true)) {
            val f = fixture(); f.setup(); val s = Scheduling(backgroundScope, f)
            s.scheduler.onForeground(); runCurrent()
            val first = CompletableDeferred<Unit>(); val second = CompletableDeferred<Unit>()
            val snapshots = mutableListOf<Long>()
            f.source.afterSnapshot = { content ->
                snapshots += content.revision
                assertEquals(1, s.holds)
                if (snapshots.size == 1) first.await() else second.await()
            }
            s.onFlush = { if (s.pendingEdits) { f.source.revision++; s.pendingEdits = false } }
            f.source.revision++
            if (manual) s.scheduler.manual() else s.scheduler.trigger()
            runCurrent()
            assertEquals(listOf(2L), snapshots)
            s.pendingEdits = true; s.scheduler.onLeaving(); s.scheduler.trigger(); runCurrent()
            f.files.onGrant = { assertEquals(1, s.holds) }
            first.complete(Unit); runCurrent()
            assertEquals(listOf(2L, 3L), snapshots)
            assertEquals(if (manual) 3 else 2, s.flushes); assertFalse(s.pendingEdits)
            assertEquals(1, s.acquisitions); assertEquals(0, s.releases)
            second.complete(Unit); runCurrent()
            assertEquals(0, s.holds); assertEquals(1, s.releases)
            val active = f.settings.record.slots[f.settings.record.active]
            assertTrue(f.files.data.getValue(active.uri).decodeToString().endsWith("revision 3"))
            assertFalse(f.settings.record.pending); assertFalse(s.scheduler.state.value.mutationsBlocked)
        }
    }
    @Test fun `pause flush still runs when the first backup becomes ineligible`() = runTest {
        val f = fixture(); f.setup(); val s = Scheduling(backgroundScope, f)
        s.scheduler.onForeground(); runCurrent()
        val gate = CompletableDeferred<Unit>()
        f.source.afterSnapshot = { gate.await() }
        s.onFlush = { if (s.pendingEdits) { f.source.revision++; s.pendingEdits = false } }
        f.source.revision++; s.scheduler.trigger(); runCurrent()
        s.pendingEdits = true; s.scheduler.onLeaving()
        f.files.data[f.settings.record.slots[f.settings.record.active].uri] = byteArrayOf(7)
        gate.complete(Unit); runCurrent()
        assertFalse(s.pendingEdits); assertEquals(2, s.flushes)
        assertEquals(2, f.source.prepares); assertEquals(BackupProblem.ACTIVE_DAMAGED, f.settings.record.error!!.problem)
        assertEquals(1, s.acquisitions); assertEquals(1, s.releases); assertEquals(0, s.holds)
    }
    @Test fun `lock cancels a background followup and releases its original hold once`() = runTest {
        val f = fixture(); f.setup(); val s = Scheduling(backgroundScope, f)
        s.scheduler.onForeground(); runCurrent()
        val first = CompletableDeferred<Unit>(); var attempts = 0
        f.source.afterSnapshot = { if (++attempts == 1) first.await() else awaitCancellation() }
        f.source.revision++; s.scheduler.trigger(); runCurrent()
        f.source.revision++; s.scheduler.onLeaving(); first.complete(Unit); runCurrent()
        assertEquals(2, attempts); val lastGood = f.settings.record
        f.unlocked = false; s.scheduler.onLocked(); runCurrent()
        assertEquals(lastGood, f.settings.record)
        assertEquals(1, s.acquisitions); assertEquals(1, s.releases); assertEquals(0, s.holds)
        assertFalse(s.scheduler.state.value.mutationsBlocked)
    }
    @Test fun `access refresh enables retry without encryption writes or service holds`() = runTest {
        val f = fixture(); f.setup(); f.files.grants.clear(); f.backup.run(true)
        val s = Scheduling(backgroundScope, f); f.files.events.clear()
        assertFalse(f.backup.mayWrite(force = true))
        f.files.grants += "folder"
        assertTrue(s.scheduler.refreshAccess().admission.accepted)
        assertEquals(BackupAdmission.BUSY, s.scheduler.beginSetup().admission)
        runCurrent()
        assertTrue(f.backup.mayWrite(force = true))
        assertEquals(1, f.source.prepares); assertTrue(f.files.events.isEmpty())
        assertEquals(0, s.acquisitions); assertFalse(s.scheduler.state.value.mutationsBlocked)
    }
    @Test fun `reservation defers automatic work and picker pause only flushes without a hold`() = runTest {
        val f = fixture(); f.setup(); val s = Scheduling(backgroundScope, f)
        s.scheduler.onForeground(); runCurrent()
        val token = s.scheduler.beginSetup().token!!
        assertTrue(s.scheduler.state.value.mutationsBlocked)
        f.source.revision++; s.scheduler.trigger(); s.scheduler.trigger(); s.scheduler.onLeaving()
        assertEquals(0, s.holds); runCurrent(); assertEquals(1, s.flushes); assertEquals(1, f.source.prepares)
        s.scheduler.onForeground(); runCurrent(); assertEquals(1, f.source.prepares)
        s.scheduler.cancelSetup(token); runCurrent()
        assertEquals(2, f.source.prepares); assertEquals(0, s.holds)
        assertFalse(s.scheduler.state.value.mutationsBlocked)
    }
    @Test fun `picker callbacks before and after resume own hold before slow initial flush`() = runTest {
        for (beforeResume in listOf(true, false)) {
            val f = fixture(); f.setup(); val s = Scheduling(backgroundScope, f)
            s.scheduler.onForeground(); runCurrent(); val token = s.scheduler.beginSetup().token!!
            s.scheduler.onLeaving(); runCurrent()
            val gate = CompletableDeferred<Unit>()
            s.onFlush = { assertEquals(1, s.holds); gate.await() }
            if (!beforeResume) s.scheduler.onForeground()
            assertTrue(s.scheduler.submitSetup(token, "new").admission.accepted)
            if (beforeResume) { runCurrent(); assertEquals(0, s.holds); assertTrue(s.scheduler.state.value.setupQueued); s.scheduler.onForeground() }
            assertEquals(1, s.holds); runCurrent(); assertEquals(1, f.source.prepares)
            assertEquals(BackupAdmission.BUSY, s.scheduler.manual().admission)
            s.scheduler.trigger(); assertEquals(BackupAdmission.BUSY, s.scheduler.beginSetup().admission)
            assertEquals(BackupProblem.BUSY, f.backup.status.value!!.setupError!!.problem)
            gate.complete(Unit); runCurrent()
            assertEquals("new", f.settings.record.folder); assertEquals(2, f.source.prepares)
            assertEquals(0, s.holds); assertFalse(s.scheduler.state.value.mutationsBlocked)
        }
    }
    @Test fun `running preparatory flush rejects setup and lock invalidates pending callbacks`() = runTest {
        val f = fixture(); f.setup(); val s = Scheduling(backgroundScope, f)
        s.scheduler.onForeground(); runCurrent()
        val gate = CompletableDeferred<Unit>(); s.onFlush = { gate.await() }
        s.scheduler.manual(); runCurrent()
        assertEquals(BackupAdmission.BUSY, s.scheduler.beginSetup().admission)
        assertEquals(BackupProblem.BUSY, f.backup.status.value!!.setupError!!.problem)
        gate.complete(Unit); runCurrent(); s.onFlush = {}
        val token = s.scheduler.beginSetup().token!!
        f.unlocked = false; s.scheduler.onLocked()
        f.unlocked = true; f.backup.refresh()
        assertEquals(BackupAdmission.SELECT_AGAIN, s.scheduler.submitSetup(token, "new").admission)
        assertEquals(BackupProblem.SELECT_AGAIN, f.backup.status.value!!.setupError!!.problem)
        assertEquals("folder", f.settings.record.folder); assertEquals(0, s.holds)
    }
    @Test fun `setup cancellation before body and flush failure release reservation exactly once`() = runTest {
        val f = fixture(); f.setup(); val s = Scheduling(backgroundScope, f)
        s.scheduler.onForeground(); runCurrent()
        var token = s.scheduler.beginSetup().token!!
        val job = s.scheduler.submitSetup(token, "new").job!!
        assertEquals(1, s.holds); job.cancel(); runCurrent()
        assertEquals(0, s.holds); assertFalse(s.scheduler.state.value.mutationsBlocked)
        s.scheduler.cancelSetup(token); assertEquals(0, s.holds)
        token = s.scheduler.beginSetup().token!!; s.onFlush = { throw IOException() }
        s.scheduler.submitSetup(token, "new"); runCurrent()
        assertEquals(1, s.failures); assertEquals(0, s.holds); assertFalse(s.scheduler.state.value.mutationsBlocked)
        assertEquals("folder", f.settings.record.folder)
    }
    @Test fun `late grant refresh cannot start a new background backup`() = runTest {
        val f = fixture(); f.setup(); f.files.grants.clear(); f.backup.run(true)
        val s = Scheduling(backgroundScope, f)
        f.files.grants += "folder"
        s.scheduler.onForeground(); s.scheduler.onLeaving(); runCurrent()
        assertTrue(f.backup.mayWrite()); assertEquals(1, f.source.prepares); assertEquals(0, s.holds)
        s.scheduler.onForeground(); runCurrent()
        assertEquals(2, f.source.prepares); assertNull(f.settings.record.error); assertEquals(0, s.holds)
    }
    @Test fun `reservation defers fingerprint changes and automatic preparation rejects admission`() = runTest {
        val f = fixture(); f.setup(); val s = Scheduling(backgroundScope, f)
        s.scheduler.onForeground(); s.scheduler.onUnlocked(); runCurrent()
        val token = s.scheduler.beginSetup().token!!
        val inspections = f.source.inspections
        f.source.revision++; s.changes.value = f.source.revision
        advanceTimeBy(60_001); runCurrent()
        assertEquals(inspections, f.source.inspections)
        val gate = CompletableDeferred<Unit>(); s.onFlush = { gate.await() }
        s.scheduler.cancelSetup(token); runCurrent()
        assertEquals(BackupAdmission.BUSY, s.scheduler.beginSetup().admission)
        assertEquals(BackupProblem.BUSY, f.backup.status.value!!.setupError!!.problem)
        gate.complete(Unit); runCurrent(); assertEquals(0, s.holds)
        s.scheduler.onLocked()
    }
    @Test fun `reserved setup evaluates restored phrase and preserves restart explanation`() = runTest {
        for (uncertain in listOf(false, true)) {
            val f = fixture(); f.setup(); val s = Scheduling(backgroundScope, f)
            s.scheduler.onForeground(); runCurrent(); val token = s.scheduler.beginSetup().token!!
            f.source.phrase = "b".repeat(64)
            try { f.backup.publishRestore(f.source.phrase) { config, begin -> begin(); if (uncertain) throw IOException(); f.settings.record = config } }
            catch (_: IOException) { }
            val outcome = s.scheduler.submitSetup(token, "new").admission
            assertEquals(if (uncertain) BackupAdmission.RESTART_REQUIRED else BackupAdmission.PHRASE_REVIEW_REQUIRED, outcome)
            assertEquals(if (uncertain) BackupProblem.RESTART_REQUIRED else BackupProblem.PHRASE_CHANGED, f.backup.status.value!!.setupError!!.problem)
            assertFalse(s.scheduler.state.value.selecting); assertEquals(0, s.holds)
            assertFalse("new" in f.files.grants)
        }
    }
    @Test fun `main immediate completion never leaves scheduler ownership behind`() = runTest {
        val f = fixture(); f.setup()
        val immediateScope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(testScheduler))
        try {
            val s = Scheduling(immediateScope, f)
            s.scheduler.onForeground()
            // Disable finishes synchronously on the caller when the provider work also runs immediately.
            s.scheduler.disable(); runCurrent()
            assertFalse(s.scheduler.state.value.mutationsBlocked)
            val token = s.scheduler.beginSetup().token!!
            s.scheduler.submitSetup(token, "new"); runCurrent()
            assertEquals("new", f.settings.record.folder)
            assertFalse(s.scheduler.state.value.mutationsBlocked); assertEquals(0, s.holds)
        } finally { immediateScope.cancel() }
    }
    @Test fun `recovery resource distinguishes unverified active file damage and setup failure`() {
        assertEquals(R.string.backup_active_unverified, dev.securenotes.ui.backupErrorResource(BackupError(BackupProblem.IO, "verify-active")))
        assertEquals(R.string.backup_active_access, dev.securenotes.ui.backupErrorResource(BackupError(BackupProblem.ACCESS, "verify-active")))
        assertEquals(R.string.backup_damaged, dev.securenotes.ui.backupErrorResource(BackupError(BackupProblem.ACTIVE_DAMAGED, "verify-active")))
        assertEquals(R.string.backup_io, dev.securenotes.ui.backupErrorResource(BackupError(BackupProblem.IO, "write-rwt")))
        assertEquals(R.string.backup_setup_io, dev.securenotes.ui.backupErrorResource(BackupError(BackupProblem.IO, "write-rwt"), setup = true))
        assertEquals(R.string.backup_setup_io, dev.securenotes.ui.backupErrorResource(BackupError(BackupProblem.ACTIVE_DAMAGED, "verify"), setup = true))
        assertEquals(R.string.backup_attachments_unverified, dev.securenotes.ui.backupErrorResource(BackupError(BackupProblem.IO, "check-attachments")))
        assertEquals(R.string.backup_attachments_access, dev.securenotes.ui.backupErrorResource(BackupError(BackupProblem.ACCESS, "check-attachments")))
        assertEquals(R.string.backup_attachment_damaged, dev.securenotes.ui.backupErrorResource(BackupError(BackupProblem.ATTACHMENT_DAMAGED, "check-attachments")))
        assertEquals(R.string.backup_attachment_conflict, dev.securenotes.ui.backupErrorResource(BackupError(BackupProblem.ATTACHMENT_DAMAGED, "attachment-identity")))
    }

    private fun Fixture.saved(): Map<String, AttachmentFile> = settings.files.getValue(checkNotNull(settings.record.pairTag))
    /** Events that touch an attachment file itself; listing its folder does not. */
    private fun Fixture.attachmentEvents(): List<String> {
        val uris = saved().values.map { it.uri }
        return files.events.filter { event -> uris.any { uri -> event.substringAfter(':').let { it == uri || it.startsWith("$uri:") } } }
    }
    @Test fun `setup saves attachments once in a confirmed folder before both indexes`() = runTest {
        val f = fixture(); f.files.collisionSuffix = " (2)"
        f.source.attachments[id(1)] = "first".toByteArray(); f.source.attachments[id(2)] = "second".toByteArray()
        f.setup()
        val config = f.settings.record
        val folder = checkNotNull(config.attachmentFolder)
        assertEquals("Notes-19700102T101736789Z-attachments (2)", folder.name)
        assertEquals(listOf(id(1), id(2)), f.source.encrypted)
        val saved = f.saved()
        assertTrue(saved.values.all { it.complete && it.uri.startsWith("${folder.uri}/") && it.name == "${it.id}.ssna (2)" })
        saved.values.forEach { assertEquals(it.digest, f.files.data.getValue(it.uri).inputStream().use(::ciphertextDigest)) }
        assertEquals(mapOf(id(1) to "${id(1)}.ssna (2)", id(2) to "${id(2)}.ssna (2)"), f.source.indexes.single())
        assertEquals(config.slots.map { f.files.data.getValue(it.uri).decodeToString() }.distinct().size, 1)
        assertNotNull(f.settings.key(checkNotNull(config.pairTag)))
        assertTrue(folder.uri in config.managedUris)
    }
    @Test fun `text edits reordering and removal never reopen saved attachments`() = runTest {
        val f = fixture()
        f.source.attachments[id(1)] = "first".toByteArray(); f.source.attachments[id(2)] = "second".toByteArray()
        f.setup()
        val folder = checkNotNull(f.settings.record.attachmentFolder).uri
        val ciphertext = f.saved().values.associate { it.uri to f.files.data.getValue(it.uri).clone() }
        fun indexWrites() = f.files.events.count { it.startsWith("open:") && f.settings.record.slots.any { slot -> it.startsWith("open:${slot.uri}:") } }
        // A text edit: one directory query, one index write, and no attachment file read, measured or written.
        f.source.revision++; f.backup.run()
        assertEquals(listOf("list:$folder"), f.files.events.filter { it.startsWith("list:") })
        assertTrue(f.attachmentEvents().isEmpty()); assertEquals(1, indexWrites())
        assertEquals(1, f.files.events.count { it.startsWith("open:") })
        // Reordering only changes the index.
        f.files.events.clear()
        val reordered = f.source.attachments.entries.reversed().associate { it.key to it.value }
        f.source.attachments.clear(); f.source.attachments.putAll(reordered); f.source.revision++; f.backup.run()
        assertTrue(f.attachmentEvents().isEmpty()); assertEquals(1, f.files.events.count { it.startsWith("open:") })
        // Adding one attachment writes only that file and one index.
        f.files.events.clear(); f.source.attachments[id(3)] = "third".toByteArray(); f.source.revision++; f.backup.run()
        val added = f.saved().getValue(id(3))
        assertTrue(added.complete)
        assertEquals(listOf("open:${added.uri}:rwt"), f.files.events.filter { it.startsWith("open:") && !it.startsWith("open:${f.settings.record.slots[0].uri}") && !it.startsWith("open:${f.settings.record.slots[1].uri}") })
        assertEquals(2, f.files.events.count { it.startsWith("open:") })
        assertEquals(listOf(id(1), id(2), id(3)), f.source.encrypted)
        // Removal deletes no file; the index names only current attachments.
        f.files.events.clear(); f.source.attachments.remove(id(1)); f.source.revision++; f.backup.run()
        assertTrue(f.files.events.none { it.startsWith("delete:") }); assertEquals(1, f.files.events.count { it.startsWith("open:") })
        assertEquals(setOf(id(2), id(3)), f.source.indexes.last().keys)
        ciphertext.forEach { (uri, bytes) -> assertArrayEquals(bytes, f.files.data[uri]) }
        // An unchanged automatic save writes nothing; a forced save still writes the index, and only the index.
        f.files.events.clear(); f.backup.run(); assertTrue(f.files.events.isEmpty())
        f.backup.run(force = true)
        assertTrue(f.attachmentEvents().isEmpty()); assertEquals(1, f.files.events.count { it.startsWith("open:") })
        assertNull(f.settings.record.error)
    }
    @Test fun `attachment damage needs two signals and no check outcome opens an index`() = runTest {
        val cases = listOf<Triple<String, BackupProblem, (FakeFiles, AttachmentFile) -> Unit>>(
            Triple("missing", BackupProblem.ATTACHMENT_DAMAGED) { files, file -> files.data.remove(file.uri) },
            Triple("wrong size", BackupProblem.ATTACHMENT_DAMAGED) { files, file -> files.data[file.uri] = files.data.getValue(file.uri) + 1 },
            Triple("listing fails", BackupProblem.IO) { files, _ -> files.listFailure = IOException("private") },
            Triple("listing denied", BackupProblem.ACCESS) { files, _ -> files.listFailure = SecurityException("private") },
            Triple("unknown listed size", BackupProblem.IO) { files, file -> files.listedSizes[file.uri] = null },
            Triple("listing omits a present file", BackupProblem.IO) { files, file -> files.hidden += file.uri },
            Triple("descriptor disagrees with listing", BackupProblem.IO) { files, file -> files.listedSizes[file.uri] = 1 },
            Triple("descriptor size unknown", BackupProblem.IO) { files, file -> files.listedSizes[file.uri] = 1; files.measuredSizes[file.uri] = null },
            Triple("listed but not found on open", BackupProblem.IO) { files, file -> files.listedSizes[file.uri] = 1; files.measureFailure = FileNotFoundException() },
            Triple("listing omits and descriptor denied", BackupProblem.ACCESS) { files, file -> files.hidden += file.uri; files.measureFailure = SecurityException() },
        )
        for ((name, expected, arrange) in cases) {
            val f = fixture(); f.source.attachments[id(1)] = "first".toByteArray(); f.setup()
            arrange(f.files, f.saved().getValue(id(1)))
            f.source.revision++; f.backup.run()
            assertEquals(name, expected, f.settings.record.error?.problem)
            assertEquals(name, "check-attachments", f.settings.record.error?.step)
            assertTrue(name, f.files.events.none { it.startsWith("open:") })
            assertEquals(name, 1, f.source.prepares)
            if (expected == BackupProblem.ATTACHMENT_DAMAGED) assertFalse(name, f.backup.mayWrite(force = true))
            if (expected == BackupProblem.IO) {
                // Unverified stays retryable and recovers once the provider answers consistently.
                f.files.listFailure = null; f.files.hidden.clear(); f.files.listedSizes.clear(); f.files.measuredSizes.clear(); f.files.measureFailure = null
                f.backup.run(); assertNull(name, f.settings.record.error)
            }
        }
    }
    @Test fun `unfinished attachments commit fresh measurements before any rewrite`() = runTest {
        val f = fixture(); f.setup()
        f.source.attachments[id(1)] = "first".toByteArray(); f.source.revision++
        f.files.failure = "write"; f.backup.run()
        val first = f.saved().getValue(id(1))
        assertFalse(first.complete); assertEquals(BackupProblem.IO, f.settings.record.error?.problem)
        assertTrue(f.settings.record.slots.none { slot -> f.files.events.any { it.startsWith("open:${slot.uri}:") } })
        f.files.failure = null
        // Locked before the measurements commit: nothing is reopened and nothing needs a restart.
        f.files.events.clear(); f.source.onAttachment = { f.unlocked = false }
        try { f.backup.run(force = true) } catch (_: CancellationException) { }
        assertTrue(f.attachmentEvents().none { it.startsWith("open:") }); assertEquals(first, f.saved().getValue(id(1)))
        f.source.onAttachment = {}; f.unlocked = true; f.backup.refresh(); assertFalse(f.backup.status.value!!.restartRequired)
        // A failed commit with an uncertain outcome never reopens the document and blocks until restart.
        for (applied in listOf(false, true)) {
            f.files.events.clear()
            f.settings.failRecord = f.settings.recordWrites + 1; f.settings.applyRecordBeforeFailure = applied
            f.backup.run(force = true)
            assertTrue(applied.toString(), f.attachmentEvents().none { it.startsWith("open:") })
            assertTrue(f.backup.status.value!!.restartRequired)
            f.settings.failRecord = null; f.restart()
        }
        // Measurements committed for an encryption that was never written: the next attempt re-encrypts, commits
        // fresh measurements and rewrites the same document exactly once.
        f.settings.files.getValue(checkNotNull(f.settings.record.pairTag))[id(1)] = first.copy(length = 99, digest = "e".repeat(64))
        f.restart(); f.files.events.clear()
        f.backup.run(force = true)
        val finished = f.saved().getValue(id(1))
        assertTrue(finished.complete); assertEquals(first.uri, finished.uri)
        assertNotEquals("e".repeat(64), finished.digest); assertNotEquals(first.digest, finished.digest)
        assertEquals(1, f.files.events.count { it.startsWith("open:${first.uri}:") })
        assertEquals(finished.digest, f.files.data.getValue(first.uri).inputStream().use(::ciphertextDigest))
        assertNull(f.settings.record.error)
        // A completed attachment is never reopened, even by a forced save.
        f.files.events.clear(); f.backup.run(force = true)
        assertTrue(f.attachmentEvents().none { it.startsWith("open:") || it.startsWith("read:") })
    }
    @Test fun `a written but unrecorded attachment completes without rewriting and a lost one gets a new document`() = runTest {
        for (lost in listOf(false, true)) {
            val f = fixture(); f.setup()
            f.source.attachments[id(1)] = "first".toByteArray(); f.source.revision++
            // The file is written and read back, but recording its completion has an uncertain outcome.
            f.settings.failRecord = f.settings.recordWrites + 2
            f.backup.run(); assertTrue(f.backup.status.value!!.restartRequired)
            f.settings.failRecord = null; f.restart()
            val unfinished = f.saved().getValue(id(1))
            assertFalse(unfinished.complete)
            assertEquals(unfinished.digest, f.files.data.getValue(unfinished.uri).inputStream().use(::ciphertextDigest))
            if (lost) f.files.data.remove(unfinished.uri)
            f.files.events.clear(); f.backup.run(force = true)
            val finished = f.saved().getValue(id(1))
            assertTrue(finished.complete); assertNull(f.settings.record.error)
            if (lost) {
                assertNotEquals(unfinished.uri, finished.uri)
                assertEquals(1, f.files.events.count { it.startsWith("open:${finished.uri}:") })
            } else {
                assertEquals(unfinished.copy(complete = true), finished)
                assertTrue(f.files.events.none { it.startsWith("open:${unfinished.uri}:") })
            }
            assertEquals(mapOf(id(1) to finished.name), f.source.indexes.last())
        }
    }
    @Test fun `an attachment ID with other content pauses the pair without writing`() = runTest {
        val f = fixture(); f.source.attachments[id(1)] = "first".toByteArray(); f.setup()
        f.source.attachments[id(1)] = "other".toByteArray(); f.source.revision++; f.backup.run(force = true)
        assertEquals(BackupError(BackupProblem.ATTACHMENT_DAMAGED, "attachment-identity", "BackupFailure"), f.settings.record.error)
        assertTrue(f.files.events.none { it.startsWith("open:") }); assertFalse(f.backup.mayWrite(force = true))
        f.backup.publishRestore(f.source.phrase) { config, begin -> begin(); f.settings.record = config }
        assertEquals(BackupProblem.ATTACHMENT_DAMAGED, f.settings.record.error?.problem)
    }
    @Test fun `the attachment folder is created with the first attachment and a removed source abandons the snapshot`() = runTest {
        val f = fixture(); f.setup()
        assertNull(f.settings.record.attachmentFolder); assertTrue(f.files.directories.isEmpty())
        f.source.attachments[id(1)] = "first".toByteArray(); f.source.revision++
        f.source.onAttachment = { f.source.attachments.clear() }
        f.backup.run()
        assertNull(f.settings.record.error); assertTrue(f.settings.record.slots.none { slot -> f.files.events.any { it.startsWith("open:${slot.uri}:") } })
        assertNotNull(f.settings.record.attachmentFolder)
        f.source.onAttachment = {}; f.source.attachments[id(1)] = "first".toByteArray(); f.source.revision++; f.files.events.clear()
        f.backup.run()
        assertEquals(1, f.files.directories.size); assertTrue(f.saved().getValue(id(1)).complete)
        assertTrue(f.files.events.none { it.startsWith("mkdir:") }); assertNull(f.settings.record.error)
    }
    @Test fun `failed setups keep their files and discarding removes the folder files key and records they created`() = runTest {
        for (failure in listOf("write", "name")) {
            val f = fixture(); f.source.attachments[id(1)] = "first".toByteArray(); f.setup()
            val old = f.settings.record; val data = f.files.data.keys.toSet(); val directories = f.files.directories.toSet()
            f.source.attachments[id(2)] = "second".toByteArray()
            if (failure == "write") f.files.failure = "write" else { f.files.missingName = true; f.files.name = null }
            assertEquals(BackupAdmission.FAILED, f.backup.choose("folder"))
            assertNotNull(failure, f.settings.record.setup); assertEquals(failure, old, f.settings.record.copy(setup = null))
            assertNotEquals(failure, directories, f.files.directories)
            f.files.failure = null; f.files.missingName = false
            assertEquals(BackupAdmission.ACCEPTED, f.backup.discardSetup())
            assertEquals(data, f.files.data.keys); assertEquals(directories, f.files.directories)
            assertEquals(old, f.settings.record)
            assertEquals(setOf(old.pairTag), f.settings.keys.keys); assertEquals(setOf(old.pairTag), f.settings.files.keys)
        }
    }
    @Test fun `a new pair forgets the old pair's records but keeps its files`() = runTest {
        val f = fixture(); f.source.attachments[id(1)] = "first".toByteArray(); f.setup()
        val old = f.settings.record; val oldFile = f.saved().getValue(id(1))
        assertTrue(f.backup.choose("folder").accepted)
        val next = f.settings.record
        assertNotEquals(old.pairTag, next.pairTag); assertNotEquals(old.attachmentFolder, next.attachmentFolder)
        assertEquals(setOf(next.pairTag), f.settings.keys.keys); assertEquals(setOf(next.pairTag), f.settings.files.keys)
        assertTrue(oldFile.uri in f.files.data); assertNotEquals(oldFile.uri, f.saved().getValue(id(1)).uri)
        f.backup.turnOff()
        assertTrue(f.settings.keys.isEmpty()); assertTrue(f.settings.files.isEmpty()); assertTrue(oldFile.uri in f.files.data)
    }
    @Test fun `pairs from before attachment files keep whole-file saves`() = runTest {
        val f = fixture(); f.source.attachments[id(1)] = "first".toByteArray(); f.setup()
        f.settings.record = f.settings.record.copy(pairTag = null, attachmentFolder = null); f.settings.keys.clear(); f.settings.files.clear()
        f.restart(); f.files.events.clear(); f.source.revision++; f.backup.run()
        assertEquals(1, f.source.wholePrepares); assertTrue(f.files.events.none { it.startsWith("list:") || it.startsWith("mkdir:") })
        assertTrue(f.files.data.getValue(f.settings.record.slots[f.settings.record.active].uri).decodeToString().startsWith("whole"))
        assertNull(f.settings.record.pairTag); assertNull(f.settings.record.error)
    }
    @Test fun `configuration version 1 records still load and validate`() {
        val decoded = dev.securenotes.document.documentJson.decodeFromString<BackupConfiguration>(
            """{"version":1,"mode":"PAIR","folder":"folder","slots":[{"uri":"a","name":"A","length":1,"sha256":"${"a".repeat(64)}"},{"uri":"b","name":"B","length":1,"sha256":"${"b".repeat(64)}"}],"active":1,"phraseHash":"${"c".repeat(64)}","fingerprint":"${"d".repeat(64)}","savedAt":1,"pending":false,"error":null,"legacyUri":null,"legacyName":null}""")
        decoded.validate(); assertNull(decoded.pairTag); assertNull(decoded.attachmentFolder)
        assertThrows(IllegalArgumentException::class.java) { decoded.copy(version = 3).validate() }
        assertThrows(IllegalArgumentException::class.java) { decoded.copy(attachmentFolder = BackupFolder("f", "F")).validate() }
        assertThrows(IllegalArgumentException::class.java) { decoded.copy(pairTag = "not a uuid").validate() }
        assertThrows(IllegalArgumentException::class.java) { BackupConfiguration(pairTag = id(1)).validate() }
    }

    @Test fun `a full check reads both indexes and each shared attachment once and records the digests it read`() = runTest {
        val f = fixture(); f.source.attachments[id(1)] = "first".toByteArray(); f.source.attachments[id(2)] = "second".toByteArray(); f.setup()
        // A and B now differ, and both name the same attachment files.
        f.source.revision++; f.backup.run(); f.files.events.clear()
        f.backup.verify()
        val check = checkNotNull(f.settings.record.check)
        assertEquals(listOf(SlotCheck.VERIFIED, SlotCheck.VERIFIED), check.slots)
        assertEquals(123456789L, check.at); assertTrue(f.settings.record.checkIsCurrent)
        assertEquals(listOf(id(1), id(2)), f.source.checked)
        assertTrue(f.files.events.none { it.startsWith("open:") || it.startsWith("delete:") || it.startsWith("mkdir:") })
        assertNull(f.backup.status.value!!.checkError); assertNull(f.settings.record.error)
        // A later save makes the result history without discarding it.
        f.source.revision++; f.backup.run()
        assertFalse(f.settings.record.checkIsCurrent); assertEquals(check, f.settings.record.check)
        // Whole-file pairs are checked from their own contents.
        f.settings.record = f.settings.record.copy(pairTag = null, attachmentFolder = null, check = null)
        f.restart(); f.source.revision++; f.backup.run(); f.backup.run(force = true)
        f.source.checked.clear(); f.backup.verify()
        assertEquals(listOf(SlotCheck.VERIFIED, SlotCheck.VERIFIED), f.settings.record.check?.slots); assertTrue(f.source.checked.isEmpty())
    }
    @Test fun `corruption found by a full check pauses the pair`() = runTest {
        for (target in listOf("attachment", "active index")) {
            val f = fixture(); f.source.attachments[id(1)] = "first".toByteArray(); f.setup()
            if (target == "attachment") {
                val file = f.saved().getValue(id(1))
                f.files.data[file.uri] = f.files.data.getValue(file.uri).let { it.copyOf().also { bytes -> bytes[bytes.lastIndex] = (bytes.last() + 1).toByte() } }
                // The same size: routine saves deliberately do not notice.
                f.source.revision++; f.backup.run(); assertNull(target, f.settings.record.error)
            } else f.files.data[f.settings.record.slots[f.settings.record.active].uri] = "other".toByteArray()
            f.files.events.clear(); f.backup.verify()
            val expected = if (target == "attachment") BackupProblem.ATTACHMENT_DAMAGED else BackupProblem.ACTIVE_DAMAGED
            assertEquals(target, expected, f.settings.record.error?.problem)
            assertNull(target, f.settings.record.check); assertNull(target, f.backup.status.value!!.checkError)
            assertFalse(target, f.backup.mayWrite(force = true)); assertTrue(target, f.files.events.none { it.startsWith("open:") })
        }
        // Missing, when the folder's listing agrees.
        val f = fixture(); f.source.attachments[id(1)] = "first".toByteArray(); f.setup()
        f.files.data.remove(f.saved().getValue(id(1)).uri); f.backup.verify()
        assertEquals(BackupProblem.ATTACHMENT_DAMAGED, f.settings.record.error?.problem)
    }
    @Test fun `a full check that cannot read every file changes nothing`() = runTest {
        val cases = listOf<Pair<String, (Fixture) -> Unit>>(
            "attachment read fails" to { f -> f.files.unreadable += f.saved().getValue(id(1)).uri },
            "index read fails" to { f -> f.files.unreadable += f.settings.record.slots[0].uri },
            "listed attachment not found" to { f -> f.files.unopenable += f.saved().getValue(id(1)).uri },
            "access denied" to { f -> f.files.readFailure = IOException("private", SecurityException()) },
            "phrase changed" to { f -> f.source.phrase = "b".repeat(64) },
        )
        for ((name, arrange) in cases) {
            val f = fixture(); f.source.attachments[id(1)] = "first".toByteArray(); f.setup()
            val before = f.settings.record
            arrange(f); f.backup.verify()
            assertEquals(name, before, f.settings.record)
            assertNotNull(name, f.backup.status.value!!.checkError)
            if (name != "phrase changed") assertTrue(name, f.backup.mayWrite(force = true))
        }
        assertEquals(R.string.backup_check_unverified, dev.securenotes.ui.backupCheckErrorResource(BackupError(BackupProblem.IO, "attachments")))
        assertEquals(R.string.backup_check_unverified, dev.securenotes.ui.backupCheckErrorResource(BackupError(BackupProblem.ACCESS, "index-A")))
        assertEquals(R.string.backup_phrase_changed, dev.securenotes.ui.backupCheckErrorResource(BackupError(BackupProblem.PHRASE_CHANGED, "check")))
    }
    @Test fun `an interrupted or damaged inactive index is reported without pausing the pair`() = runTest {
        val f = fixture(); f.source.attachments[id(1)] = "first".toByteArray(); f.setup()
        f.files.failure = "write"; f.source.revision++; f.backup.run(); f.files.failure = null
        assertTrue(f.settings.record.pending)
        val active = f.settings.record.active
        f.backup.verify()
        assertEquals(SlotCheck.VERIFIED, f.settings.record.check!!.slots[active]); assertEquals(SlotCheck.INCOMPLETE, f.settings.record.check!!.slots[1 - active])
        assertEquals(BackupProblem.IO, f.settings.record.error?.problem); assertTrue(f.backup.mayWrite())
        f.backup.run(); assertFalse(f.settings.record.pending); assertNull(f.settings.record.error)
        val inactive = f.settings.record.slots[1 - f.settings.record.active].uri
        f.files.data[inactive] = "junk".toByteArray(); f.backup.verify()
        assertEquals(SlotCheck.DAMAGED, f.settings.record.check!!.slots[1 - f.settings.record.active])
        assertNull(f.settings.record.error); assertTrue(f.backup.mayWrite(force = true))
    }
    @Test fun `a full check that does not finish records nothing`() = runTest {
        val f = fixture(); f.source.attachments[id(1)] = "first".toByteArray(); f.setup(); val before = f.settings.record
        f.source.onCheck = { f.unlocked = false; throw CancellationException() }
        try { f.backup.verify(); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertEquals(before, f.settings.record)
    }
    @Test fun `leaving the app cancels a full check and still saves under a service hold`() = runTest {
        val f = fixture(); f.source.attachments[id(1)] = "first".toByteArray(); f.setup(); val s = Scheduling(backgroundScope, f)
        assertEquals(BackupAdmission.BUSY, s.scheduler.verify().admission)
        s.scheduler.onForeground(); runCurrent()
        val reading = CompletableDeferred<Unit>(); var cancelled = false
        f.source.onCheck = { reading.complete(Unit); try { awaitCancellation() } catch (e: CancellationException) { cancelled = true; throw e } }
        assertTrue(s.scheduler.verify().admission.accepted); reading.await()
        assertEquals(0, s.holds); assertTrue(s.scheduler.state.value.mutationsBlocked)
        f.source.revision++; s.scheduler.onLeaving(); runCurrent()
        assertTrue(cancelled); assertNull(f.settings.record.check)
        assertEquals(1, s.acquisitions); assertEquals(0, s.holds); assertEquals(1, s.flushes)
        assertTrue(f.files.data.getValue(f.settings.record.slots[f.settings.record.active].uri).decodeToString().endsWith("revision ${f.source.revision}"))
        assertFalse(s.scheduler.state.value.mutationsBlocked)
    }

    /** A pair with [id] 1 and 2, then a new setup in [folder] whose second attachment write fails. */
    private suspend fun TestScope.interruptedSetup(folder: String = "folder"): Fixture {
        val f = fixture(); f.source.attachments[id(1)] = "first".toByteArray(); f.source.attachments[id(2)] = "second".toByteArray(); f.setup()
        var writes = 0
        f.files.onWrite = { if (++writes == 2) throw IOException("provider") }
        assertEquals(BackupAdmission.FAILED, f.backup.choose(folder))
        f.files.onWrite = {}
        val pending = checkNotNull(f.settings.record.setup)
        val files = f.settings.files.getValue(pending.pairTag)
        assertTrue(files.getValue(id(1)).complete); assertFalse(files.getValue(id(2)).complete)
        return f
    }
    @Test fun `an interrupted setup resumes reusing completed files while the current pair keeps saving`() = runTest {
        val f = interruptedSetup(); val old = f.settings.record; val pending = checkNotNull(old.setup)
        // Meanwhile the current pair saves, and keeps the unfinished setup as it was.
        f.source.revision++; f.backup.run()
        assertNull(f.settings.record.error); assertEquals(1 - old.active, f.settings.record.active); assertEquals(pending, f.settings.record.setup)
        val latest = f.settings.record
        // Resuming captures current content and rewrites only what is not complete.
        f.source.attachments[id(3)] = "third".toByteArray(); f.source.revision++
        val encrypted = f.source.encrypted.size
        assertEquals(BackupAdmission.ACCEPTED, f.backup.resumeSetup())
        val next = f.settings.record
        assertEquals(pending.pairTag, next.pairTag); assertNull(next.setup); assertNull(next.error)
        assertEquals(listOf(id(2), id(3)), f.source.encrypted.drop(encrypted))
        assertEquals(setOf(id(1), id(2), id(3)), f.source.indexes.last().keys)
        assertEquals(pending.attachmentFolder, next.attachmentFolder)
        // The previous pair's files stay, its records go, and the shared folder permission is kept.
        assertTrue(latest.slots.all { it.uri in f.files.data }); assertTrue("folder" in f.files.grants)
        assertEquals(setOf(next.pairTag), f.settings.keys.keys); assertEquals(setOf(next.pairTag), f.settings.files.keys)
        assertEquals(BackupAdmission.INELIGIBLE, f.backup.resumeSetup())
    }
    @Test fun `discarding a setup removes only its documents and keeps a permission still in use`() = runTest {
        for (target in listOf("folder", "other")) {
            val f = interruptedSetup(target); val pending = checkNotNull(f.settings.record.setup)
            val made = pending.created + f.settings.files.getValue(pending.pairTag).values.map { it.uri }
            f.source.revision++; f.backup.run(); val latest = f.settings.record
            val kept = f.settings.files.getValue(checkNotNull(latest.pairTag)).values.map { it.uri } + latest.managedUris - pending.created.toSet()
            assertEquals(BackupAdmission.ACCEPTED, f.backup.discardSetup())
            assertEquals(target, latest.copy(setup = null), f.settings.record)
            assertTrue(target, made.none { it in f.files.data || it in f.files.directories })
            assertTrue(target, kept.all { it in f.files.data || it in f.files.directories })
            assertEquals(target, target == "folder", target in f.files.grants); assertTrue("folder" in f.files.grants)
            assertEquals(setOf(latest.pairTag), f.settings.keys.keys)
        }
    }
    @Test fun `a restore while a setup is pending needs review for a new phrase and discarding for other content`() = runTest {
        val f = interruptedSetup()
        f.backup.publishRestore(f.source.phrase) { config, begin -> begin(); f.settings.record = config }
        assertNotNull(f.settings.record.setup)
        f.source.attachments[id(3)] = "restored".toByteArray(); f.source.revision++
        assertEquals(BackupAdmission.ACCEPTED, f.backup.resumeSetup()); assertEquals(setOf(id(1), id(2), id(3)), f.source.indexes.last().keys)

        val g = interruptedSetup()
        g.source.phrase = "b".repeat(64)
        g.backup.publishRestore(g.source.phrase) { config, begin -> begin(); g.settings.record = config }
        assertEquals(BackupAdmission.FAILED, g.backup.resumeSetup())
        assertEquals(BackupError(BackupProblem.PHRASE_CHANGED, "resume", "BackupFailure"), g.backup.status.value!!.setupError)
        assertEquals(R.string.backup_setup_phrase, dev.securenotes.ui.backupErrorResource(g.backup.status.value!!.setupError!!, setup = true))
        assertTrue(g.backup.setupNeedsReview()); assertNotNull(g.settings.record.setup)
        g.backup.phraseReviewed(g.source.phrase)
        // Nothing encrypted under the old phrase's setup is reused: a new key, tag and folder, with its files kept.
        val earlier = checkNotNull(g.settings.record.setup)
        val earlierFiles = g.settings.files.getValue(earlier.pairTag).values.map { it.uri } + earlier.created
        val encrypted = g.source.encrypted.size
        assertEquals(BackupAdmission.ACCEPTED, g.backup.resumeSetup())
        val fresh = g.settings.record
        assertEquals(g.source.phrase, fresh.phraseHash); assertNull(fresh.error)
        assertNotEquals(earlier.pairTag, fresh.pairTag); assertNotEquals(earlier.attachmentFolder, fresh.attachmentFolder)
        assertEquals(listOf(id(1), id(2)), g.source.encrypted.drop(encrypted))
        assertTrue(earlierFiles.all { it in g.files.data || it in g.files.directories })
        assertFalse(earlier.pairTag in g.settings.keys); assertFalse(earlier.pairTag in g.settings.files)

        val h = interruptedSetup()
        h.source.attachments[id(1)] = "other".toByteArray(); h.source.revision++
        assertEquals(BackupAdmission.FAILED, h.backup.resumeSetup())
        val conflict = h.backup.status.value!!.setupError!!
        assertEquals(BackupProblem.ATTACHMENT_DAMAGED to "attachment-identity", conflict.problem to conflict.step)
        assertEquals(R.string.backup_setup_conflict, dev.securenotes.ui.backupErrorResource(conflict, setup = true))
        assertEquals(BackupAdmission.ACCEPTED, h.backup.discardSetup()); assertNull(h.settings.record.setup)
    }
    @Test fun `resume never activates a pair whose completed files have gone`() = runTest {
        val f = interruptedSetup(); val old = f.settings.record; val pending = checkNotNull(old.setup)
        val completed = f.settings.files.getValue(pending.pairTag).getValue(id(1))
        // Unverified: nothing is activated, and a later attempt can still finish.
        f.files.listFailure = IOException("provider")
        assertEquals(BackupAdmission.FAILED, f.backup.resumeSetup())
        assertEquals(BackupProblem.IO to "check-attachments", f.backup.status.value!!.setupError!!.let { it.problem to it.step })
        assertEquals(old.pairTag, f.settings.record.pairTag); assertEquals(old.slots, f.settings.record.slots)
        assertTrue(f.settings.record.setup!!.slots.isEmpty())
        // Confirmed missing: the setup cannot finish; the current pair stays active and keeps saving.
        f.files.listFailure = null; f.files.data.remove(completed.uri)
        assertEquals(BackupAdmission.FAILED, f.backup.resumeSetup())
        val damaged = f.backup.status.value!!.setupError!!
        assertEquals(BackupProblem.ATTACHMENT_DAMAGED to "check-attachments", damaged.problem to damaged.step)
        assertEquals(R.string.backup_setup_damaged, dev.securenotes.ui.backupErrorResource(damaged, setup = true))
        assertEquals(old.pairTag, f.settings.record.pairTag); assertTrue(f.settings.record.setup!!.slots.isEmpty())
        f.source.revision++; f.backup.run(); assertNull(f.settings.record.error); assertEquals(old.pairTag, f.settings.record.pairTag)
        assertEquals(BackupAdmission.ACCEPTED, f.backup.discardSetup())
    }
    @Test fun `routine saves look for files where restore will`() = runTest {
        val cases = listOf<Triple<String, BackupProblem?, (FakeFiles, AttachmentFile) -> Unit>>(
            // A provider collision suffix still resolves by ID, as restore resolves it.
            Triple("suffixed", null) { files, file -> files.rename(file.uri, "${file.id}.ssna (1)") },
            Triple("renamed away", BackupProblem.ATTACHMENT_DAMAGED) { files, file -> files.rename(file.uri, "photo.ssna") },
            // The listing alone reports the rename: unverified.
            Triple("renamed in the listing only", BackupProblem.IO) { files, file -> files.rename(file.uri, "photo.ssna"); files.missingName = true; files.name = file.name },
            // Restore would read another document; only a full check can tell whether it is a valid copy.
            Triple("another document has the name", BackupProblem.IO) { files, file -> files.rename(file.uri, "photo.ssna"); files.copy(file.uri, "folder/1/copy", file.name) },
            Triple("ambiguous", BackupProblem.ATTACHMENT_DAMAGED) { files, file ->
                files.rename(file.uri, "${file.id} (1).ssna"); files.copy(file.uri, "folder/1/copy", "${file.id} (2).ssna")
            },
            // The competing entries come from the listing alone until each document confirms its own name.
            Triple("stale duplicate of an ID-prefixed name", BackupProblem.IO) { files, file ->
                files.rename(file.uri, "${file.id} (1).ssna"); files.phantoms += BackupChild("folder/1/gone", "${file.id} (2).ssna", 5)
            },
            Triple("stale duplicate of the exact name", BackupProblem.IO) { files, file -> files.phantoms += BackupChild("folder/1/gone", file.name, 5) },
            Triple("duplicate whose own name disagrees", BackupProblem.IO) { files, file ->
                files.rename(file.uri, "${file.id} (1).ssna"); files.copy(file.uri, "folder/1/copy", "other.ssna"); files.phantoms += BackupChild("folder/1/copy", "${file.id} (2).ssna", 5)
            },
            Triple("confirmed duplicates of the exact name", BackupProblem.ATTACHMENT_DAMAGED) { files, file ->
                files.copy(file.uri, "folder/1/copy", file.name)
            },
        )
        for ((name, expected, arrange) in cases) {
            val f = fixture(); f.source.attachments[id(1)] = "first".toByteArray(); f.setup()
            arrange(f.files, f.saved().getValue(id(1)))
            f.source.revision++; f.backup.run()
            assertEquals(name, expected, f.settings.record.error?.problem)
            if (expected != null) assertTrue(name, f.files.events.none { it.startsWith("open:") })
            // A stale listing pauses nothing: once it recovers, the next save goes through.
            if (name.startsWith("stale")) { f.files.phantoms.clear(); f.backup.run(); assertNull(name, f.settings.record.error) }
        }
    }
    @Test fun `a full check reads what restore would read`() = runTest {
        val cases = listOf<Triple<String, SlotCheck?, (FakeFiles, AttachmentFile) -> Unit>>(
            Triple("suffixed", SlotCheck.VERIFIED) { files, file -> files.rename(file.uri, "${file.id}.ssna (1)") },
            Triple("renamed away", null) { files, file -> files.rename(file.uri, "photo.ssna") },
            // The recorded document is gone, but restore would find a valid copy under the suffixed name.
            Triple("valid copy only", SlotCheck.VERIFIED) { files, file -> files.copy(file.uri, "folder/1/copy", "${file.id}.ssna (1)"); files.data.remove(file.uri) },
            // Restore would read a copy whose content does not authenticate.
            Triple("damaged copy only", null) { files, file -> files.copy(file.uri, "folder/1/copy", "${file.id}.ssna (1)"); files.data["folder/1/copy"] = "junk".toByteArray(); files.data.remove(file.uri) },
        )
        for ((name, expected, arrange) in cases) {
            val f = fixture(); f.source.attachments[id(1)] = "first".toByteArray(); f.setup()
            arrange(f.files, f.saved().getValue(id(1)))
            f.backup.verify()
            if (expected == null) {
                assertEquals(name, BackupProblem.ATTACHMENT_DAMAGED, f.settings.record.error?.problem); assertNull(name, f.settings.record.check)
            } else {
                assertEquals(name, listOf(expected, expected), f.settings.record.check?.slots); assertNull(name, f.settings.record.error)
            }
        }
    }
    @Test fun `an uncertain setup commit blocks both configurations until restart`() = runTest {
        val f = interruptedSetup()
        f.settings.failRecord = f.settings.recordWrites + 1
        assertEquals(BackupAdmission.FAILED, f.backup.resumeSetup()); assertTrue(f.backup.status.value!!.restartRequired)
        val events = f.files.events.size; val record = f.settings.record
        f.source.revision++; f.backup.run(true)
        assertEquals(BackupAdmission.RESTART_REQUIRED, f.backup.resumeSetup()); assertEquals(BackupAdmission.RESTART_REQUIRED, f.backup.discardSetup())
        assertFalse(f.backup.choose("other").accepted)
        assertEquals(events, f.files.events.size); assertEquals(record, f.settings.record)
        f.settings.failRecord = null; f.restart()
        assertEquals(BackupAdmission.ACCEPTED, f.backup.resumeSetup()); assertNull(f.settings.record.setup)
    }
    @Test fun `a failed setup leaves no reservation so the current pair keeps saving automatically`() = runTest {
        val f = fixture(); f.source.attachments[id(1)] = "first".toByteArray(); f.setup(); val s = Scheduling(backgroundScope, f)
        s.scheduler.onForeground(); runCurrent()
        val token = s.scheduler.beginSetup().token!!
        f.files.failure = "write"; s.scheduler.submitSetup(token, "folder"); runCurrent(); f.files.failure = null
        assertNotNull(f.settings.record.setup); assertFalse(s.scheduler.state.value.mutationsBlocked)
        f.source.revision++; s.scheduler.trigger(); runCurrent()
        assertTrue(f.files.data.getValue(f.settings.record.slots[f.settings.record.active].uri).decodeToString().endsWith("revision ${f.source.revision}"))
        assertNotNull(f.settings.record.setup)
        assertTrue(s.scheduler.resumeSetup().admission.accepted); runCurrent()
        assertNull(f.settings.record.setup); assertNull(f.backup.status.value!!.setupError)
        assertEquals(0, s.holds); assertFalse(s.scheduler.state.value.mutationsBlocked)
    }
    @Test fun `upgrading a whole-file pair creates a new pair in its folder and keeps its files`() = runTest {
        val f = fixture(); f.source.attachments[id(1)] = "first".toByteArray(); f.setup()
        f.settings.record = f.settings.record.copy(pairTag = null, attachmentFolder = null); f.settings.keys.clear(); f.settings.files.clear()
        f.restart(); f.source.revision++; f.backup.run()
        val whole = f.settings.record
        assertTrue(f.files.data.getValue(whole.slots[whole.active].uri).decodeToString().startsWith("whole"))
        assertTrue(f.backup.choose(checkNotNull(whole.folder)).accepted)
        val upgraded = f.settings.record
        assertNotNull(upgraded.pairTag); assertNotNull(upgraded.attachmentFolder); assertEquals(whole.folder, upgraded.folder)
        assertTrue(whole.slots.all { it.uri in f.files.data }); assertEquals(setOf("folder"), f.files.grants)
        assertTrue(f.saved().getValue(id(1)).complete)
    }
}

private fun sha(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
private fun id(n: Int) = java.util.UUID(0, n.toLong()).toString()
