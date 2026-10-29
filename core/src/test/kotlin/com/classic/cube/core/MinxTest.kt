package com.classic.cube.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MinxTest {
    private fun puzzle(n: Int) = Puzzle.MINX_FAMILY.first { it.n == n }

    @Test
    fun stickersPerFace() {
        // Kilominx 5, Megaminx 11, Master Kilominx 20, Gigaminx 31, Elite Kilominx 45, Teraminx 61.
        val expected = mapOf(2 to 5, 3 to 11, 4 to 20, 5 to 31, 6 to 45, 7 to 61)
        for ((n, perFace) in expected) {
            val g = MinxGeometry.of(n)
            for (f in 0 until 12) assertEquals("n=$n face $f", perFace, g.stickers.count { it.face == f })
        }
    }

    @Test
    fun fiveTurnsAreIdentity() {
        for (n in MinxGeometry.SIZES) {
            val g = MinxGeometry.of(n)
            for (face in 0 until 12) for (layer in 0 until g.layers) {
                val m = Minx.solved(puzzle(n))
                repeat(5) { m.apply(Move(face, layer, 1)) }
                assertEquals("n=$n $face/$layer", Minx.solved(puzzle(n)), m)
            }
        }
    }

    @Test
    fun turnsArePermutations() {
        for (n in MinxGeometry.SIZES) {
            val g = MinxGeometry.of(n)
            for (face in 0 until 12) for (layer in 0 until g.layers) {
                val p = g.permutation(Move(face, layer, 1))
                assertEquals(p.size, p.toSet().size)
            }
        }
    }

    /** Stickers of one piece must agree on which layer of every face they are in, or turns tear pieces apart. */
    @Test
    fun piecesHoldTogether() {
        // Sticker count per piece -> number of pieces of that size.
        val expected = mapOf(
            2 to mapOf(3 to 20),
            3 to mapOf(1 to 12, 2 to 30, 3 to 20),
            4 to mapOf(1 to 60, 2 to 60, 3 to 20),
            6 to mapOf(1 to 240, 2 to 120, 3 to 20),
        )
        for ((n, counts) in expected) {
            val g = MinxGeometry.of(n)
            val groups = g.stickers.indices.groupBy { i -> (0 until 12).map { g.layer(i, it) } }
            val sizes = groups.values.groupingBy { it.size }.eachCount()
            assertEquals("n=$n", counts, sizes)
        }
    }

    @Test
    fun innerSliceOfEvenSizeTurnsAndUndoes() {
        val p = puzzle(4)
        val m = p.solved()
        m.apply(Move(0, 1, 1))
        assertFalse(m.isSolved())
        m.apply(Move(0, 1, -1))
        assertTrue(m.isSolved())
    }

    @Test
    fun outerTurnUnsolvesAndInverseRestores() {
        val m = Minx.solved(puzzle(3))
        m.apply(Move(0, 0, 1))
        assertFalse(m.isSolved())
        m.apply(Move(0, 0, -1))
        assertTrue(m.isSolved())
    }

    @Test
    fun scrambleReversedSolves() {
        for (n in MinxGeometry.SIZES) {
            val p = puzzle(n)
            val m = p.solved()
            val s = p.scramble(Random(n))
            m.applyAll(s)
            assertFalse(m.isSolved())
            m.applyAll(s.reversed().map { it.inverse() })
            assertTrue(m.isSolved())
        }
    }

    @Test
    fun encodingRoundTrips() {
        val p = puzzle(5)
        val m = p.solved()
        m.applyAll(p.scramble(Random(9)))
        assertEquals(m, p.decode(m.encode()))
        val moves = p.scramble(Random(1))
        assertEquals(moves, Move.decodeAll(Move.encodeAll(moves)))
    }

    @Test
    fun foldsFifths() {
        assertEquals(Move(3, 0, -1), Move.of(3, 0, 4, order = 5))
        assertEquals(Move(3, 0, -2), Move.of(3, 0, 3, order = 5))
        assertEquals(Move(3, 0, 2), Move.of(3, 0, 7, order = 5))
    }
}
