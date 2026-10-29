package com.classic.cube.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MirrorTest {
    private val mirror = Puzzle.MIRROR

    @Test
    fun solvedOnlyWhenEveryPieceIsHome() {
        val c = Cube.solved(mirror)
        assertTrue(c.isSolved())
        // A mirror cube looks the same color everywhere, so a face-color check would call this solved.
        c.apply(Move(0, 2, 1))
        assertFalse(c.isSolved())
        c.apply(Move(0, 2, -1))
        assertTrue(c.isSolved())
    }

    @Test
    fun wholeCubeRotationStillSolved() {
        val c = Cube.solved(mirror)
        for (layer in 0..2) c.apply(Move(1, layer, 1))
        assertTrue(c.isSolved())
        assertEquals(24, mirror.geometry.wholeRotations.size)
    }

    @Test
    fun scrambleReversedSolves() {
        val c = Cube.solved(mirror)
        val s = Cube.scramble(3, Random(11))
        c.applyAll(s)
        assertFalse(c.isSolved())
        c.applyAll(s.reversed().map { it.inverse() })
        assertTrue(c.isSolved())
    }

    @Test
    fun solvedPiecesFillTheCuboid() {
        val pieces = Cube.solved(mirror).pieces()
        assertEquals(26, pieces.size)
        val ident = intArrayOf(1, 0, 0, 0, 1, 0, 0, 0, 1)
        for (p in pieces) assertArrayEquals(ident, p.rot)
        val corner = pieces.first { it.x == 2 && it.y == 2 && it.z == 2 }
        assertArrayEquals(floatArrayOf(1f, 2.3f, 1f, 2.1f, 1f, 3.2f), mirror.box(corner), 1e-5f)
    }

    @Test
    fun turnedPieceKeepsItsShape() {
        val c = Cube.solved(mirror)
        c.apply(Move(1, 2, 1)) // U layer, +90° about y: front-right corner goes to back-right.
        val p = c.pieces().first { it.x == 2 && it.y == 2 && it.z == -2 }
        assertEquals(2, p.hx); assertEquals(2, p.hz)
        val b = mirror.box(p)
        // Its x extent (the thin R layer, 0.3 past the grid) now runs along z toward the back.
        assertArrayEquals(floatArrayOf(1f, 3.2f, 1f, 2.1f, -2.3f, -1f), b, 1e-5f)
    }

    @Test
    fun puzzlesLookUpById() {
        for (p in Puzzle.ALL) assertEquals(p, Puzzle.byId(p.id))
        assertEquals("3", Puzzle.standard(3).id)
    }

    @Test
    fun mirrorEncodingRoundTrips() {
        val c = Cube.solved(mirror)
        c.applyAll(Cube.scramble(3, Random(2)))
        assertEquals(c, Cube.decode(mirror, c.encode()))
    }
}
