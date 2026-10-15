package dev.securenotes

import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import dev.securenotes.backup.BackupProblem
import dev.securenotes.security.Crypto
import dev.securenotes.ui.NotesViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Exercises the real Activity/ViewModelStore lifecycle with a synthetic unlocked vault. */
class BackupRecreationTest {
    @Test fun acceptedSetupSurvivesActivityFinish(): Unit = acceptedSetupSurvivesClearedViewModel(queued = false)
    @Test fun queuedSetupSurvivesActivityFinishUntilNextForeground(): Unit = acceptedSetupSurvivesClearedViewModel(queued = true)

    private fun acceptedSetupSurvivesClearedViewModel(queued: Boolean): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<NotesApplication>()
        withContext(Dispatchers.Main) { app.lock() }
        app.repository.close()
        File(app.noBackupFilesDir, "vaults").deleteRecursively(); File(app.noBackupFilesDir, "active").delete()
        try {
            app.unlock(Crypto.random(32)); app.repository.confirmPassphrase()
            withTimeout(10_000) { app.autoBackup.status.first { it != null } }
            // Both submission paths must transfer ownership: trusted local provider and acknowledged warning.
            for (warning in listOf(false, true)) {
                val scenario = ActivityScenario.launch(MainActivity::class.java)
                var replacement: ActivityScenario<MainActivity>? = null
                app.repository.mutex.lock()
                try {
                    scenario.onActivity { activity ->
                        val vm = ViewModelProvider(activity)[NotesViewModel::class.java]
                        vm.launchBackupPicker { }
                        if (queued) app.backupScheduler.onLeaving()
                        val authority = if (warning) "test-provider" else "com.android.externalstorage.documents"
                        vm.chooseBackup(android.net.Uri.parse("content://$authority/tree/not-granted"))
                        if (warning) { assertNotNull(vm.backupProviderWarning); vm.acceptBackupProvider() }
                        assertTrue(app.backupScheduler.state.value.selecting)
                        assertEquals(queued, app.backupScheduler.state.value.setupQueued)
                        assertEquals(if (queued) 0 else 1, app.keptAlive.value)
                    }
                    scenario.close() // Finishes the Activity and clears its ViewModelStore, unlike recreation.
                    withContext(Dispatchers.Main) {
                        assertTrue(app.backupScheduler.state.value.selecting)
                        assertEquals(if (queued) 0 else 1, app.keptAlive.value)
                    }
                    if (queued) {
                        replacement = ActivityScenario.launch(MainActivity::class.java)
                        withContext(Dispatchers.Main) {
                            assertFalse(app.backupScheduler.state.value.setupQueued)
                            assertEquals(1, app.keptAlive.value)
                        }
                    }
                } finally { app.repository.mutex.unlock(); scenario.close() }
                try {
                    // The synthetic URI has no grant: reaching this error proves setup ran after VM clearing.
                    val status = withTimeout(30_000) { app.autoBackup.status.first { it?.setupError != null } }!!
                    assertEquals(BackupProblem.ACCESS, status.setupError!!.problem)
                    withTimeout(10_000) { app.backupScheduler.state.first { !it.mutationsBlocked } }
                    assertEquals(0, app.keptAlive.value)
                } finally { replacement?.close() }
            }
        } finally {
            withContext(Dispatchers.Main) { app.lock() }; app.repository.close()
            File(app.noBackupFilesDir, "vaults").deleteRecursively(); File(app.noBackupFilesDir, "active").delete()
        }
    }

    @Test fun selectionSurvivesActivityRecreationButLockedCallbacksMustSelectAgain(): Unit = runBlocking {
        val app = ApplicationProvider.getApplicationContext<NotesApplication>()
        withContext(Dispatchers.Main) { app.lock() }
        app.repository.close()
        File(app.noBackupFilesDir, "vaults").deleteRecursively(); File(app.noBackupFilesDir, "active").delete()
        val root = Crypto.random(32)
        try {
            app.unlock(root.copyOf()); app.repository.confirmPassphrase()
            withTimeout(10_000) { app.autoBackup.status.first { it != null } }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var original: NotesViewModel
                scenario.onActivity { activity ->
                    original = ViewModelProvider(activity)[NotesViewModel::class.java]
                    original.launchBackupPicker { }
                    assertTrue(app.backupScheduler.state.value.selecting)
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    val retained = ViewModelProvider(activity)[NotesViewModel::class.java]
                    assertSame(original, retained)
                    assertTrue(app.backupScheduler.state.value.selecting); assertEquals(0, app.keptAlive.value)
                    app.lock()
                }
                app.unlock(root.copyOf())
                withTimeout(10_000) { app.autoBackup.status.first { it != null } }
                scenario.onActivity {
                    var relaunched = false
                    original.launchBackupPicker { relaunched = true }
                    assertFalse(relaunched)
                    original.chooseBackup(android.net.Uri.parse("content://test-provider/tree/stale"))
                    assertEquals(BackupProblem.SELECT_AGAIN, app.autoBackup.status.value!!.setupError!!.problem)
                    assertFalse(app.backupScheduler.state.value.selecting); assertEquals(0, app.keptAlive.value)
                }
            }
        } finally {
            root.fill(0); withContext(Dispatchers.Main) { app.lock() }; app.repository.close()
            File(app.noBackupFilesDir, "vaults").deleteRecursively(); File(app.noBackupFilesDir, "active").delete()
        }
    }
}
