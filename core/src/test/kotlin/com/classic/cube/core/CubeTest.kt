package com.classic.cube.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CubeTest {
    private val r = Move(0, 2, -1) // R: clockwise seen from +x
    private val u = Move(1, 2, -1) // U: clockwise seen from +y

    @Test
    fun solvedCubeIsSolved() {
        for (n in Geometry.MIN_SIZE..Geometry.MAX_SIZE) assertTrue(Cube.solved(n).isSolved())
    }

    @Test
    fun fourQuarterTurnsAreIdentity() {
        for (n in 2..5) for (axis in 0..2) for (layer in 0 until n) {
            val c = Cube.solved(n)
            repeat(4) { c.apply(Move(axis, layer, 1)) }
            assertEquals(Cube.solved(n), c)
        }
    }

    @Test
    fun outerTurnUnsolvesAndInverseRestores() {
        val c = Cube.solved(3)
        c.apply(r)
        assertFalse(c.isSolved())
        c.apply(r.inverse())
        assertTrue(c.isSolved())
    }

    @Test
    fun middleSliceKeepsFacesSolvedOnlyForWholeCube() {
        // Turning all three layers together is a whole-cube rotation: still solved.
        val c = Cube.solved(3)
        for (layer in 0..2) c.apply(Move(1, layer, 1))
        assertTrue(c.isSolved())
        assertFalse(c == Cube.solved(3))
    }

    @Test
    fun sexyMoveHasOrderSix() {
        val c = Cube.solved(3)
        val seq = listOf(r, u, r.inverse(), u.inverse())
        repeat(5) { c.applyAll(seq) }
        assertFalse(c.isSolved())
        c.applyAll(seq)
        assertTrue(c.isSolved())
    }

    @Test
    fun scrambleReversedSolves() {
        val random = Random(7)
        for (n in 2..6) {
            val c = Cube.solved(n)
            val s = Cube.scramble(n, random)
            c.applyAll(s)
            assertFalse(c.isSolved())
            c.applyAll(s.reversed().map { it.inverse() })
            assertTrue(c.isSolved())
        }
    }

    @Test
    fun scrambleKeepsOddCentersFixed() {
        val c = Cube.solved(3)
        c.applyAll(Cube.scramble(3, Random(1), 200))
        val g = c.geometry
        for (i in g.slots.indices) {
            val s = g.slots[i]
            if (listOf(s.x, s.y, s.z).count { it == 0 } == 2) assertEquals(s.face, c.color(i))
        }
    }

    @Test
    fun encodingRoundTrips() {
        val c = Cube.solved(4)
        c.applyAll(Cube.scramble(4, Random(3)))
        assertEquals(c, Cube.decode(Puzzle.standard(4), c.encode()))
        assertNull(Cube.decode(Puzzle.standard(3), c.encode()))

        val moves = Cube.scramble(5, Random(4))
        assertEquals(moves, Move.decodeAll(Move.encodeAll(moves)))
        assertNull(Move.decodeAll("x0"))
    }

    @Test
    fun moveFolding() {
        assertEquals(Move(0, 1, -1), Move.of(0, 1, 3))
        assertEquals(Move(0, 1, 2), Move.of(0, 1, -2))
        assertNull(Move.of(0, 1, 4))
    }
}
