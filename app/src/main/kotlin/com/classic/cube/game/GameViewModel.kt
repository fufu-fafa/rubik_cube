package com.classic.cube.game

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.classic.cube.core.Cube
import com.classic.cube.core.Move
import com.classic.cube.core.Puzzle
import com.classic.cube.data.PrefsRepository
import com.classic.cube.data.SavedGame
import com.classic.cube.data.SavedGameRepository
import com.classic.cube.data.SizeStats
import com.classic.cube.data.StatsRepository
import com.classic.cube.gl.CubeSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class Phase {
    /** Turn freely; nothing is timed. */
    FREE,
    SCRAMBLING,
    /** Scrambled; the clock starts on the first turn. */
    READY,
    RUNNING,
    SOLVED,
    /** Playing every move back to solved. */
    REWINDING,
}

data class SolveResult(val millis: Long, val moves: Int, val previousBest: Long?) {
    val newBest get() = previousBest == null || millis < previousBest
}

data class GameUi(
    val puzzle: Puzzle = Puzzle.standard(3),
    val phase: Phase = Phase.FREE,
    val moves: Int = 0,
    val elapsedMillis: Long = 0,
    val canUndo: Boolean = false,
    val canRewind: Boolean = false,
    val result: SolveResult? = null,
)

/**
 * Game rules on top of the [CubeSession]. The session reports finished turns from the GL thread;
 * they are handed to the main thread, where all of this state lives.
 */
class GameViewModel(
    private val prefs: PrefsRepository,
    private val savedGames: SavedGameRepository,
    private val statsRepo: StatsRepository,
) : ViewModel() {
    val session = CubeSession(Cube.solved(3))

    private val _ui = MutableStateFlow(GameUi())
    val ui: StateFlow<GameUi> = _ui.asStateFlow()

    val saved: StateFlow<SavedGame?> = savedGames.saved.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val stats: StateFlow<Map<String, SizeStats>> = statsRepo.stats.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    val menuPuzzle: StateFlow<Puzzle> = prefs.puzzle.stateIn(viewModelScope, SharingStarted.Eagerly, Puzzle.standard(3))

    private enum class Pending { SCRAMBLE, UNDO, REWIND }

    /** Every move since the cube was last solved, scramble first. */
    private val history = ArrayList<Move>()
    private var scrambleLength = 0
    private var pending: Pending? = null

    private var elapsedBase = 0L
    private var runningSince = 0L
    private var visible = false
    private var ticker: Job? = null

    init {
        session.onPlayerMove = { m ->
            val solved = session.state.isSolved()
            viewModelScope.launch { onPlayerMove(m, solved) }
        }
        session.onQueueDrained = {
            val solved = session.state.isSolved()
            viewModelScope.launch { onDrained(solved) }
        }
    }

    fun setMenuPuzzle(p: Puzzle) {
        viewModelScope.launch { prefs.setPuzzle(p) }
    }

    fun newGame(puzzle: Puzzle, scramble: Boolean) {
        stopClock()
        elapsedBase = 0
        history.clear()
        scrambleLength = 0
        session.setState(puzzle.solved())
        session.resetCamera()
        if (scramble) {
            val moves = puzzle.scramble()
            history += moves
            scrambleLength = moves.size
            pending = Pending.SCRAMBLE
            session.inputEnabled = false
            // About six seconds however long the scramble.
            session.play(moves, secondsPerMove = (6f / moves.size).coerceIn(0.03f, 0.1f))
            _ui.value = GameUi(puzzle = puzzle, phase = Phase.SCRAMBLING)
        } else {
            pending = null
            session.inputEnabled = true
            _ui.value = GameUi(puzzle = puzzle, phase = Phase.FREE)
        }
    }

    fun resume(g: SavedGame) {
        stopClock()
        session.setState(g.state.copy())
        session.inputEnabled = true
        pending = null
        history.clear()
        history += g.history
        scrambleLength = g.scrambleLength
        elapsedBase = g.elapsedMillis
        _ui.value = GameUi(
            puzzle = g.state.puzzle,
            phase = if (g.started) Phase.RUNNING else Phase.READY,
            moves = g.moves,
            elapsedMillis = g.elapsedMillis,
        ).withButtons()
        if (visible) startClockIfRunning()
    }

    fun undo() {
        val phase = _ui.value.phase
        if (pending != null || session.isBusy() || history.size <= scrambleLength) return
        if (phase == Phase.SCRAMBLING || phase == Phase.REWINDING) return
        val m = history.removeAt(history.lastIndex)
        pending = Pending.UNDO
        if (phase == Phase.RUNNING) _ui.update { it.copy(moves = (it.moves - 1).coerceAtLeast(0)) }
        _ui.update { it.withButtons() }
        session.play(listOf(m.inverse()), secondsPerMove = 0.16f)
    }

    /** Plays the whole history backwards. Abandons a timed solve. */
    fun rewind() {
        if (history.isEmpty() || pending != null) return
        stopClock()
        val moves = history.reversed().map { it.inverse() }
        pending = Pending.REWIND
        session.inputEnabled = false
        session.play(moves, secondsPerMove = (5f / moves.size).coerceIn(0.05f, 0.2f))
        _ui.update { it.copy(phase = Phase.REWINDING, canUndo = false, canRewind = false) }
        viewModelScope.launch { savedGames.clear() }
    }

    fun resetCamera() = session.resetCamera()

    fun dismissResult() = _ui.update { it.copy(result = null) }

    /** The game screen is on (or off) screen; the clock only runs while it is. */
    fun setVisible(v: Boolean) {
        if (v == visible) return
        visible = v
        if (v) startClockIfRunning() else {
            stopClock()
            save()
        }
    }

    private fun onPlayerMove(m: Move, solved: Boolean) {
        history += m
        when (_ui.value.phase) {
            Phase.READY -> {
                _ui.update { it.copy(phase = Phase.RUNNING, moves = 1) }
                startClockIfRunning()
            }
            Phase.RUNNING -> _ui.update { it.copy(moves = it.moves + 1) }
            else -> {}
        }
        if (solved) onSolved() else save()
        _ui.update { it.withButtons() }
    }

    private fun onDrained(solved: Boolean) {
        val was = pending
        pending = null
        when (was) {
            Pending.SCRAMBLE -> {
                session.inputEnabled = true
                _ui.update { it.copy(phase = Phase.READY) }
                save()
            }
            Pending.UNDO -> if (solved) onSolved() else save()
            Pending.REWIND -> {
                session.inputEnabled = true
                history.clear()
                scrambleLength = 0
                elapsedBase = 0
                _ui.update { it.copy(phase = Phase.FREE, moves = 0, elapsedMillis = 0) }
            }
            null -> {}
        }
        _ui.update { it.withButtons() }
    }

    private fun onSolved() {
        history.clear()
        scrambleLength = 0
        val ui = _ui.value
        if (ui.phase != Phase.RUNNING && ui.phase != Phase.READY) return
        stopClock()
        val millis = elapsedBase
        _ui.update { it.copy(phase = Phase.SOLVED, elapsedMillis = millis) }
        viewModelScope.launch {
            savedGames.clear()
            val prev = statsRepo.record(ui.puzzle.id, millis, ui.moves)
            _ui.update { it.copy(result = SolveResult(millis, ui.moves, prev)) }
        }
    }

    private fun GameUi.withButtons() = copy(
        canUndo = history.size > scrambleLength && (phase == Phase.RUNNING || phase == Phase.FREE || phase == Phase.SOLVED),
        canRewind = history.isNotEmpty() && phase != Phase.SCRAMBLING && phase != Phase.REWINDING,
    )

    private fun save() {
        val ui = _ui.value
        if (ui.phase != Phase.READY && ui.phase != Phase.RUNNING) return
        val g = SavedGame(
            state = session.snapshot(),
            history = history.toList(),
            scrambleLength = scrambleLength,
            moves = ui.moves,
            elapsedMillis = currentElapsed(),
            started = ui.phase == Phase.RUNNING,
        )
        viewModelScope.launch { savedGames.save(g) }
    }

    private fun currentElapsed() =
        elapsedBase + if (runningSince > 0) SystemClock.elapsedRealtime() - runningSince else 0

    private fun startClockIfRunning() {
        if (_ui.value.phase != Phase.RUNNING || runningSince > 0 || !visible) return
        runningSince = SystemClock.elapsedRealtime()
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) {
                _ui.update { it.copy(elapsedMillis = currentElapsed()) }
                delay(47)
            }
        }
    }

    private fun stopClock() {
        if (runningSince > 0) elapsedBase = currentElapsed()
        runningSince = 0
        ticker?.cancel()
        ticker = null
        _ui.update { it.copy(elapsedMillis = elapsedBase) }
    }
}
