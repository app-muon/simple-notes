package dev.securenotes.search

import kotlinx.coroutines.*

/** Main-thread owner of cancellable work. Only immutable preparations cross the worker boundary. */
class FindSession(
    private val scope: CoroutineScope,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val prepare: (String, () -> Unit) -> FindText.Prepared = FindText::prepare,
    private val publish: (FindState) -> Unit,
    private val scroll: () -> Unit,
) {
    var state = FindState()
        private set
    private var title = ""
    private var body = ""
    private var preparedTitle: FindText.Prepared? = null
    private var preparedBody: FindText.Prepared? = null
    private var job: Job? = null
    private var generation = 0L
    private var action = 0L
    private var preferred: FindMatch? = null
    private var preferredIndex = -1

    private fun emit(value: FindState) { state = value; publish(value) }

    fun search(title: String, body: String, query: String, jump: Boolean) {
        job?.cancel()
        val request = ++generation
        if (jump) action++
        val requestedAction = action
        val changed = this.title != title || this.body != body
        if (state.matches.isNotEmpty()) { preferred = state.active; preferredIndex = state.activeIndex }
        this.title = title; this.body = body
        if (preparedTitle?.original != title) preparedTitle = null
        if (preparedBody?.original != body) preparedBody = null
        val next = state.copy(query = query, pending = query.isNotEmpty(),
            queryGeneration = state.queryGeneration + if (query != state.query) 1 else 0,
            documentGeneration = state.documentGeneration + if (changed) 1 else 0)
        emit(if (changed || query.isEmpty()) next.copy(matches = emptyList(), activeIndex = -1,
            bodyMatchesByLine = emptyMap(), titleMatches = emptyList()) else next)
        if (query.isEmpty()) { preferred = null; preferredIndex = -1; return }
        val compute = computation(state)
        job = scope.launch {
            delay(150)
            val result = compute()
            if (request != generation) return@launch
            preparedTitle = result.title; preparedBody = result.body
            emit(result.state)
            if (jump && requestedAction == action) scroll()
        }
    }

    fun move(delta: Int) {
        if (state.pending || state.matches.isEmpty()) return
        action++; emit(state.move(delta)); scroll()
    }
    fun cancelScroll() { action++ }

    private data class Result(val state: FindState, val title: FindText.Prepared, val body: FindText.Prepared)
    private fun computation(snapshot: FindState): suspend () -> Result {
        val titleText = title; val bodyText = body
        val cachedTitle = preparedTitle; val cachedBody = preparedBody
        val active = snapshot.active ?: preferred
        val index = snapshot.activeIndex.takeIf { it >= 0 } ?: preferredIndex
        return {
            withContext(worker) {
                val context = currentCoroutineContext()
                val checkpoint = { context.ensureActive() }
                val t = cachedTitle ?: prepare(titleText, checkpoint)
                val b = cachedBody ?: prepare(bodyText, checkpoint)
                val matches = FindText.matches(t, b, snapshot.query, checkpoint)
                val selected = if (matches.isEmpty()) -1 else matches.indexOf(active).takeIf { it >= 0 } ?: index.coerceIn(0, matches.lastIndex)
                Result(snapshot.copy(matches = matches, activeIndex = selected, pending = false,
                    bodyMatchesByLine = groupBodyMatches(bodyText, matches, checkpoint),
                    titleMatches = matches.mapIndexedNotNull { i, match -> if (match.kind == HitKind.TITLE) IndexedFindMatch(i, match) else null }), t, b)
            }
        }
    }

    /** Close resolves a pending query immediately, without the debounce or republishing highlights. */
    fun close(resolve: Boolean, completed: (FindState) -> Unit) {
        val snapshot = state
        val compute = if (resolve && snapshot.pending) computation(snapshot) else null
        clear()
        val request = generation
        if (compute == null) completed(snapshot)
        else job = scope.launch {
            val result = compute()
            if (request == generation) completed(result.state)
        }
    }

    fun clear() {
        job?.cancel(); job = null; generation++; action++
        title = ""; body = ""; preparedTitle = null; preparedBody = null; preferred = null; preferredIndex = -1
        emit(FindState())
    }
}
