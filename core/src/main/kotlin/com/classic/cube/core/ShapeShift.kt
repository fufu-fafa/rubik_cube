package com.classic.cube.core

import kotlin.random.Random

/**
 * A cuboid ([Puzzle.dims] cubies) whose every layer turns in quarter turns, even when that leaves it
 * out of shape: a tall side turned 90° sticks out sideways. The cubies live on the grid of a cube as
 * wide as the cuboid's longest side ([size]), turning about its center like that cube would, and any
 * layer of that grid holding a cubie can turn.
 *
 * Each cubie keeps its home position, where it is now, and how it is turned. It is solved when the
 * cubies make the cuboid again, any way up, with every face one color.
 */
class ShapeShift private constructor(
    override val puzzle: Puzzle,
    /** x, y, z per cubie, doubled coordinates. */
    private val pos: IntArray,
    /** Index into [Geometry.ROTATIONS] per cubie: home directions to current ones. */
    private val rot: IntArray,
) : PuzzleState {
    val size = puzzle.dims.max()
    val count get() = homes.size
    private val homes = homePositions(puzzle.dims)

    fun position(i: Int) = intArrayOf(pos[3 * i], pos[3 * i + 1], pos[3 * i + 2])

    fun rotation(i: Int): IntArray = Geometry.ROTATIONS[rot[i]]

    /** The outward directions of cubie [i]'s stickers, as it sat at home. Each is also its color. */
    fun stickers(i: Int): List<Face> = stickersAt(puzzle.dims, homes[i])

    /** The doubled coordinate of [layer] along any axis. */
    fun layerCoord(layer: Int) = 2 * layer - (size - 1)

    fun layerOf(coord: Int) = (coord + size - 1) / 2

    fun occupied(axis: Int, layer: Int) = (0 until count).any { pos[3 * it + axis] == layerCoord(layer) }

    override fun apply(m: Move) {
        val q = Math.floorMod(m.turns, 4)
        val c = layerCoord(m.layer)
        for (i in 0 until count) {
            if (pos[3 * i + m.axis] != c) continue
            var p = position(i)
            var r = rotation(i)
            repeat(q) {
                p = Geometry.rotate(p, m.axis)
                r = turned(r, m.axis)
            }
            p.copyInto(pos, 3 * i)
            rot[i] = Geometry.ROTATIONS.indexOfFirst { it.contentEquals(r) }
        }
    }

    override fun isSolved(): Boolean {
        val here = (0 until count).map { position(it).toList() }.toSet()
        return Geometry.ROTATIONS.any { w ->
            here == homes.map { apply(w, it).toList() }.toSet() &&
                (0 until count).all { i ->
                    stickers(i).all { f ->
                        val h = intArrayOf(f.nx, f.ny, f.nz)
                        apply(rotation(i), h).contentEquals(apply(w, h))
                    }
                }
        }
    }

    override fun copy() = ShapeShift(puzzle, pos.copyOf(), rot.copyOf())

    /** Per cubie: x, y, z offset by size - 1, then the rotation index, all base 36. */
    override fun encode(): String = buildString {
        for (i in 0 until count) {
            for (a in 0..2) append((pos[3 * i + a] + size - 1).digitToChar(36))
            append(rot[i].digitToChar(36))
        }
    }

    override fun equals(other: Any?) =
        other is ShapeShift && other.puzzle == puzzle && other.pos.contentEquals(pos) && other.rot.contentEquals(rot)

    override fun hashCode() = 31 * pos.contentHashCode() + rot.contentHashCode()

    companion object {
        private val IDENTITY = Geometry.ROTATIONS.indexOfFirst { it.contentEquals(intArrayOf(1, 0, 0, 0, 1, 0, 0, 0, 1)) }

        fun solved(puzzle: Puzzle): ShapeShift {
            val homes = homePositions(puzzle.dims)
            return ShapeShift(puzzle, homes.flatMap { it.toList() }.toIntArray(), IntArray(homes.size) { IDENTITY })
        }

        fun decode(puzzle: Puzzle, s: String): ShapeShift? {
            val n = homePositions(puzzle.dims).size
            val size = puzzle.dims.max()
            if (s.length != 4 * n) return null
            val pos = IntArray(3 * n)
            val rot = IntArray(n)
            for (i in 0 until n) {
                for (a in 0..2) {
                    val v = s[4 * i + a].digitToIntOrNull(36) ?: return null
                    if (v !in 0..2 * (size - 1) || v % 2 != 0) return null
                    pos[3 * i + a] = v - (size - 1)
                }
                rot[i] = s[4 * i + 3].digitToIntOrNull(36)?.takeIf { it < Geometry.ROTATIONS.size } ?: return null
            }
            return ShapeShift(puzzle, pos, rot)
        }

        /**
         * Random quarter and half turns of layers that hold cubies, never the same axis twice in a
         * row. Played out as it goes, since what is occupied changes with every turn.
         */
        fun scramble(puzzle: Puzzle, random: Random = Random.Default, length: Int = 25): List<Move> {
            val state = solved(puzzle)
            val moves = ArrayList<Move>(length)
            var lastAxis = -1
            while (moves.size < length) {
                val axis = random.nextInt(3)
                if (axis == lastAxis) continue
                val layers = (0 until state.size).filter { state.occupied(axis, it) }
                val m = Move(axis, layers.random(random), listOf(1, -1, 2).random(random))
                state.apply(m)
                moves += m
                lastAxis = axis
            }
            return moves
        }

        private fun homePositions(dims: IntArray): List<IntArray> {
            val (cx, cy, cz) = dims.map { Geometry.coords(it) }
            return buildList { for (x in cx) for (y in cy) for (z in cz) add(intArrayOf(x, y, z)) }
        }

        private fun stickersAt(dims: IntArray, home: IntArray): List<Face> = Face.entries.filter { f ->
            val n = intArrayOf(f.nx, f.ny, f.nz)
            (0..2).any { a -> n[a] != 0 && home[a] == n[a] * (dims[a] - 1) }
        }

        /** Row-major matrix times vector. */
        fun apply(m: IntArray, v: IntArray) = IntArray(3) { j -> m[3 * j] * v[0] + m[3 * j + 1] * v[1] + m[3 * j + 2] * v[2] }

        /** [r] followed by a +90° turn about [axis]: each column turned. */
        private fun turned(r: IntArray, axis: Int): IntArray {
            val out = IntArray(9)
            for (col in 0..2) {
                val c = Geometry.rotate(intArrayOf(r[col], r[3 + col], r[6 + col]), axis)
                for (row in 0..2) out[3 * row + col] = c[row]
            }
            return out
        }
    }
}
