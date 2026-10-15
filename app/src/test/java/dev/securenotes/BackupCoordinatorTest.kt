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
        var prepares = 0
        var inspections = 0
        val artifacts = mutableListOf<File>()
        override suspend fun inspect(): BackupInspection {
            inspections++
            failure?.let { throw it }
            return BackupInspection(revision.toString().padStart(64, '0'), phrase, revision, empty)
        }
        override suspend fun prepare(progress: (String) -> Unit): PreparedBackup {
            prepares++; onPrepare()
            val content = inspect()
            afterSnapshot(content)
            val file = File(directory, "artifact-$prepares").apply { writeText("ciphertext $prepares revision ${content.revision}") }
            artifacts += file
            return PreparedBackup(file, content, file.length(), file.inputStream().use(::ciphertextDigest))
        }
    }
    private class FakeSettings(val unlocked: () -> Boolean) : BackupSettingsStore {
        var record = BackupConfiguration()
        var writes = 0
        var failWrite: Int? = null
        var applyBeforeFailure = false
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
        override fun displayName(uri: String): String? { nameFailure?.let { throw it }; return if (missingName) name else names[uri] }
        override fun delete(uri: String) { events += "delete:$uri"; data.remove(uri) }
        override fun openRead(uri: String): InputStream {
            events += "read:$uri"
            readFailure?.let { throw it }
            if (failure == "read" || failure == "read:$uri") throw IOException()
            return checkNotNull(data[uri]).inputStream()
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
        assertFalse(f.backup.choose("other").accepted); assertEquals(old, f.settings.record)
        assertTrue(old.slots.all { it.uri in f.files.data })
        f.files.createUri = "other/duplicate"
        assertFalse(f.backup.choose("other").accepted); assertEquals(old, f.settings.record)
        assertFalse("other/duplicate" in f.files.data); assertFalse("other" in f.files.grants)
    }
    @Test fun `setup cancellation removes only new files and preserves same folder grant`() = runTest {
        val f = fixture(); f.setup(); val old = f.settings.record
        f.files.onCreate = { if (f.files.counter == 3) throw CancellationException() }
        try { f.backup.choose("folder").accepted } catch (_: CancellationException) { }
        assertEquals(old.managedUris, f.files.data.keys)
        assertTrue("folder" in f.files.grants); assertEquals(old, f.settings.record)
    }
    @Test fun `uncertain setup retains both pairs and grants while failed disable never releases`() = runTest {
        val f = fixture(); f.setup(); val old = f.settings.record
        f.settings.failWrite = f.settings.writes + 1
        assertFalse(f.backup.choose("other").accepted)
        assertEquals(4, f.files.data.size); assertEquals(setOf("folder", "other"), f.files.grants)
        assertEquals(old, f.settings.record)
        f.settings.failWrite = null; f.restart()
        f.settings.failWrite = f.settings.writes + 1
        try { f.backup.turnOff(); fail("Expected failure") } catch (_: IOException) { }
        assertTrue("folder" in f.files.grants); assertEquals(old, f.settings.record)
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
        assertEquals(old.managedUris, f.files.data.keys); assertFalse("other" in f.files.grants)
        f.files.nameFailure = CancellationException()
        try { f.backup.choose("other"); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertEquals(old.managedUris, f.files.data.keys)
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
    }
}
