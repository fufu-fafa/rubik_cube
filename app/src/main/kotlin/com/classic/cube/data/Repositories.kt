package com.classic.cube.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.classic.cube.core.PuzzleState
import com.classic.cube.core.Move
import com.classic.cube.core.Puzzle
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class SizeStats(
    val solves: Int = 0,
    val bestMillis: Long? = null,
    val bestMoves: Int? = null,
    val totalMillis: Long = 0,
) {
    val averageMillis get() = if (solves == 0) null else totalMillis / solves
}

/** Per-puzzle totals, kept as plain preferences, keyed by [Puzzle.id]. */
class StatsRepository(private val store: DataStore<Preferences>) {
    val stats: Flow<Map<String, SizeStats>> = store.data.map { p ->
        Puzzle.ALL.map { it.id }.associateWith { n ->
            SizeStats(
                solves = p[solves(n)] ?: 0,
                bestMillis = p[best(n)],
                bestMoves = p[bestMoves(n)],
                totalMillis = p[total(n)] ?: 0L,
            )
        }
    }

    /** Records a solve; returns the previous best time. */
    suspend fun record(n: String, millis: Long, moves: Int): Long? {
        val prev = store.data.first()[best(n)]
        store.edit {
            it[solves(n)] = (it[solves(n)] ?: 0) + 1
            it[total(n)] = (it[total(n)] ?: 0L) + millis
            if (prev == null || millis < prev) it[best(n)] = millis
            val pm = it[bestMoves(n)]
            if (pm == null || moves < pm) it[bestMoves(n)] = moves
        }
        return prev
    }

    private fun solves(n: String) = intPreferencesKey("solves_$n")
    private fun best(n: String) = longPreferencesKey("best_$n")
    private fun bestMoves(n: String) = intPreferencesKey("best_moves_$n")
    private fun total(n: String) = longPreferencesKey("total_$n")
}

/** A timed solve in progress. [history] is every move since the cube was last solved. */
data class SavedGame(
    val state: PuzzleState,
    val history: List<Move>,
    /** How many of [history] are the scramble. */
    val scrambleLength: Int,
    val moves: Int,
    val elapsedMillis: Long,
    /** False until the first move after the scramble starts the clock. */
    val started: Boolean,
)

class SavedGameRepository(private val store: DataStore<Preferences>) {
    val saved: Flow<SavedGame?> = store.data.map { p -> decode(p) }

    private fun decode(p: Preferences): SavedGame? {
        val puzzle = Puzzle.byId(p[PUZZLE] ?: return null) ?: return null
        val state = puzzle.decode(p[CUBE] ?: return null) ?: return null
        val history = Move.decodeAll(p[HISTORY] ?: "") ?: return null
        return SavedGame(
            state = state,
            history = history,
            scrambleLength = (p[SCRAMBLE] ?: 0).coerceIn(0, history.size),
            moves = p[MOVES] ?: 0,
            elapsedMillis = p[ELAPSED] ?: 0L,
            started = (p[STARTED] ?: 0) == 1,
        )
    }

    suspend fun save(g: SavedGame) = store.edit {
        it[PUZZLE] = g.state.puzzle.id
        it[CUBE] = g.state.encode()
        it[HISTORY] = Move.encodeAll(g.history)
        it[SCRAMBLE] = g.scrambleLength
        it[MOVES] = g.moves
        it[ELAPSED] = g.elapsedMillis
        it[STARTED] = if (g.started) 1 else 0
    }

    suspend fun clear() = store.edit {
        for (k in listOf(PUZZLE, CUBE, HISTORY, SCRAMBLE, MOVES, ELAPSED, STARTED)) it.remove(k)
    }

    private companion object {
        val PUZZLE = stringPreferencesKey("game_puzzle")
        // Sticker homes, two characters each.
        val CUBE = stringPreferencesKey("game_homes")
        val HISTORY = stringPreferencesKey("game_history")
        val SCRAMBLE = intPreferencesKey("game_scramble")
        val MOVES = intPreferencesKey("game_moves")
        val ELAPSED = longPreferencesKey("game_elapsed")
        val STARTED = intPreferencesKey("game_started")
    }
}

/** The last puzzle picked on the menu. */
class PrefsRepository(private val store: DataStore<Preferences>) {
    val puzzle: Flow<Puzzle> = store.data.map { p ->
        p[PUZZLE]?.let(Puzzle::byId) ?: Puzzle.standard(p[SIZE] ?: 3)
    }

    suspend fun setPuzzle(puzzle: Puzzle) = store.edit { it[PUZZLE] = puzzle.id }

    private companion object {
        /** Older builds only stored a size. */
        val SIZE = intPreferencesKey("menu_size")
        val PUZZLE = stringPreferencesKey("menu_puzzle")
    }
}
