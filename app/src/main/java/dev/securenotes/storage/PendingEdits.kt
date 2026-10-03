package dev.securenotes.storage

import dev.securenotes.document.Note
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.Serializable

@Serializable
data class PendingEdit(val note: Note, val preserveEmpty: Boolean)

/** Latest snapshot per note; a quiet-period debounce with a maximum continuous-typing delay. */
class PendingEdits(private val scope: CoroutineScope, private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L }, private val persist: suspend () -> Unit) {
    private val edits = linkedMapOf<String, PendingEdit>()
    private val signals = Channel<Unit>(Channel.CONFLATED)
    private var worker: Job? = null
    @Synchronized fun put(edit: PendingEdit) { edits[edit.note.id] = edit; signals.trySend(Unit) }
    @Synchronized fun snapshot(): List<PendingEdit> = edits.values.toList()
    @Synchronized fun committed(edit: PendingEdit) { if (edits[edit.note.id] == edit) edits.remove(edit.note.id) }
    @Synchronized fun clear() { edits.clear() }
    fun start() {
        stop()
        worker = scope.launch {
            for (signal in signals) {
                val deadline = nowMillis() + 1_000L
                while (true) {
                    val remaining = (deadline - nowMillis()).coerceAtLeast(0)
                    if (remaining == 0L || withTimeoutOrNull(minOf(300L, remaining)) { signals.receive() } == null) break
                }
                persist()
            }
        }
    }
    fun stop() { worker?.cancel(); worker = null }
}
