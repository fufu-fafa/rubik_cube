package com.classic.cube.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TowerTest {
    private val tower = Puzzle.TOWER
    private fun solved() = tower.solved() as ShapeShift

    @Test
    fun layout() {
        val t = solved()
        assertEquals(16, t.count)
        // Four 2×4 sides, a 2×2 top and bottom.
        assertEquals(4 * 8 + 2 * 4, (0 until t.count).sumOf { t.stickers(it).size })
        assertTrue(t.isSolved())
    }

    @Test
    fun quarterTurnOfATallSideBendsItOutOfShape() {
        val t = solved()
        t.apply(Move(0, 2, 1)) // the right half, x = +1, a quarter turn about x
        assertFalse(t.isSolved())
        // Its cubies now lie flat: z runs -3..3, y only -1..1.
        val right = (0 until t.count).map { t.position(it) }.filter { it[0] == 1 }
        assertEquals(setOf(-3, -1, 1, 3), right.map { it[2] }.toSet())
        assertEquals(setOf(-1, 1), right.map { it[1] }.toSet())
        t.apply(Move(0, 2, -1))
        assertEquals(solved(), t)
    }

    @Test
    fun fourQuarterTurnsAreIdentity() {
        for (axis in 0..2) for (layer in 0 until 4) {
            val t = solved()
            repeat(4) { t.apply(Move(axis, layer, 1)) }
            assertEquals(solved(), t)
        }
    }

    @Test
    fun turningTheWholeTowerKeepsItSolved() {
        // Both halves along x turned together: the tower lies on its side, still one color a face.
        val t = solved()
        t.apply(Move(0, 1, 1))
        t.apply(Move(0, 2, 1))
        assertTrue(t.isSolved())
    }

    @Test
    fun halfTurnSwapsStickersWithoutChangingShape() {
        val t = solved()
        t.apply(Move(0, 2, 2))
        assertFalse(t.isSolved())
        t.apply(Move(0, 2, 2))
        assertTrue(t.isSolved())
    }

    @Test
    fun scramblesOnlyTurnOccupiedLayersAndReverse() {
        val t = solved()
        val s = tower.scramble(Random(5))
        for (m in s) {
            assertTrue(t.occupied(m.axis, m.layer))
            t.apply(m)
        }
        assertFalse(t.isSolved())
        t.applyAll(s.reversed().map { it.inverse() })
        assertTrue(t.isSolved())
    }

    @Test
    fun encodingRoundTrips() {
        val t = solved()
        t.applyAll(tower.scramble(Random(8)))
        assertEquals(t, tower.decode(t.encode()))
    }
}
