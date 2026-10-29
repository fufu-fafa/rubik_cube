package com.classic.cube.core

import kotlin.random.Random

/**
 * The six face directions. A sticker's color is the face it sits on when the cube is solved, so the
 * ordinal doubles as a color id.
 */
enum class Face(val nx: Int, val ny: Int, val nz: Int) {
    RIGHT(1, 0, 0), LEFT(-1, 0, 0), UP(0, 1, 0), DOWN(0, -1, 0), FRONT(0, 0, 1), BACK(0, 0, -1);

    companion object {
        fun of(x: Int, y: Int, z: Int): Face = entries.first { it.nx == x && it.ny == y && it.nz == z }
    }
}

/**
 * One sticker position. [x], [y], [z] are the center of the cubie it is glued to, in doubled
 * coordinates (-(n-1), -(n-3) .. n-1) so even and odd sizes both land on integers.
 */
class Slot(val x: Int, val y: Int, val z: Int, val face: Face) {
    fun coord(axis: Int) = when (axis) {
        0 -> x
        1 -> y
        else -> z
    }
}

/**
 * Turning one layer by [turns] steps (a quarter turn on a cube, a fifth on a minx), counterclockwise
 * seen from the positive end of the axis; -1, ±2 likewise. For cubes [axis] is 0, 1, 2 for x, y, z
 * and [layer] counts from the negative side. For a minx [axis] is the face and [layer] counts in
 * from it (see [MinxGeometry]).
 */
data class Move(val axis: Int, val layer: Int, val turns: Int) {
    init {
        require(axis in 0..35 && layer in 0..35 && turns in -2..2 && turns != 0) { "bad move $axis/$layer/$turns" }
    }

    fun inverse() = copy(turns = -turns)

    fun encode() = "${axis.digitToChar(36)}${layer.digitToChar(36)}${when (turns) { 1 -> '+'; -1 -> '-'; 2 -> '2'; else -> '=' }}"

    companion object {
        /** Folds any number of steps of a [order]-fold turn into a [Move], or null if they cancel out. */
        fun of(axis: Int, layer: Int, steps: Int, order: Int = 4): Move? {
            val s = Math.floorMod(steps, order)
            if (s == 0) return null
            return Move(axis, layer, if (s > order / 2) s - order else s)
        }

        fun decode(s: String): Move? {
            if (s.length != 3) return null
            val axis = s[0].digitToIntOrNull(36) ?: return null
            val layer = s[1].digitToIntOrNull(36) ?: return null
            val turns = when (s[2]) { '+' -> 1; '-' -> -1; '2' -> 2; '=' -> -2; else -> return null }
            return Move(axis, layer, turns)
        }

        fun encodeAll(moves: List<Move>) = moves.joinToString("") { it.encode() }

        fun decodeAll(s: String): List<Move>? =
            if (s.length % 3 != 0) null else s.chunked(3).map { decode(it) ?: return null }
    }
}

/** A puzzle's sticker positions, whatever its shape. */
interface PuzzleState {
    val puzzle: Puzzle

    fun apply(m: Move)

    fun applyAll(moves: List<Move>) = moves.forEach(::apply)

    fun isSolved(): Boolean

    fun copy(): PuzzleState

    fun encode(): String
}

/** The fixed sticker layout of an n×n×n cube and the permutation each move applies to it. */
class Geometry private constructor(val n: Int) {
    val slots: List<Slot>
    private val index = HashMap<Long, Int>()
    private val perms = HashMap<Move, IntArray>()

    init {
        val c = coords(n)
        val list = ArrayList<Slot>(6 * n * n)
        for (f in Face.entries) for (a in c) for (b in c) {
            list += when {
                f.nx != 0 -> Slot(f.nx * (n - 1), a, b, f)
                f.ny != 0 -> Slot(a, f.ny * (n - 1), b, f)
                else -> Slot(a, b, f.nz * (n - 1), f)
            }
        }
        slots = list
        list.forEachIndexed { i, s -> index[key(s.x, s.y, s.z, s.face)] = i }
    }

    /** Slot indices grouped by the cubie they are glued to. */
    val cubies: List<IntArray> = slots.indices.groupBy { Triple(slots[it].x, slots[it].y, slots[it].z) }
        .values.map { it.toIntArray() }

    /**
     * The 24 whole-cube rotations as slot permutations: w[i] is where the sticker in slot i goes.
     */
    val wholeRotations: List<IntArray> by lazy {
        fun whole(axis: Int): IntArray {
            var p = IntArray(slots.size) { it }
            for (layer in 0 until n) {
                val step = permutation(Move(axis, layer, 1))
                p = IntArray(p.size) { step[p[it]] }
            }
            return p
        }
        val gens = listOf(whole(0), whole(1))
        val seen = LinkedHashMap<List<Int>, IntArray>()
        val start = IntArray(slots.size) { it }
        val todo = ArrayDeque(listOf(start))
        seen[start.toList()] = start
        while (todo.isNotEmpty()) {
            val p = todo.removeFirst()
            for (g in gens) {
                val q = IntArray(p.size) { g[p[it]] }
                if (seen.putIfAbsent(q.toList(), q) == null) todo += q
            }
        }
        seen.values.toList()
    }

    /** The doubled coordinate of [layer] along any axis. */
    fun layerCoord(layer: Int) = 2 * layer - (n - 1)

    fun layerOf(coord: Int) = (coord + n - 1) / 2

    fun inLayer(slot: Slot, axis: Int, layer: Int) = slot.coord(axis) == layerCoord(layer)

    /** perm[i] is where the sticker in slot i ends up. */
    @Synchronized
    fun permutation(m: Move): IntArray = perms.getOrPut(m) {
        val q = Math.floorMod(m.turns, 4)
        IntArray(slots.size) { i ->
            val s = slots[i]
            if (!inLayer(s, m.axis, m.layer)) return@IntArray i
            var p = intArrayOf(s.x, s.y, s.z)
            var nv = intArrayOf(s.face.nx, s.face.ny, s.face.nz)
            repeat(q) {
                p = rotate(p, m.axis)
                nv = rotate(nv, m.axis)
            }
            index.getValue(key(p[0], p[1], p[2], Face.of(nv[0], nv[1], nv[2])))
        }
    }

    private fun key(x: Int, y: Int, z: Int, f: Face) =
        ((x + 64L) shl 24) or ((y + 64L) shl 16) or ((z + 64L) shl 8) or f.ordinal.toLong()

    companion object {
        const val MIN_SIZE = 2
        const val MAX_SIZE = 7
        private val cache = HashMap<Int, Geometry>()

        @Synchronized
        fun of(n: Int): Geometry {
            require(n in MIN_SIZE..MAX_SIZE) { "size $n" }
            return cache.getOrPut(n) { Geometry(n) }
        }

        fun coords(n: Int) = IntArray(n) { 2 * it - (n - 1) }

        /** The 24 proper rotations as row-major signed permutation matrices. */
        val ROTATIONS: List<IntArray> = buildList {
            val perms = listOf(intArrayOf(0, 1, 2), intArrayOf(0, 2, 1), intArrayOf(1, 0, 2), intArrayOf(1, 2, 0), intArrayOf(2, 0, 1), intArrayOf(2, 1, 0))
            for (p in perms) for (signs in 0 until 8) {
                val m = IntArray(9)
                for (row in 0..2) m[3 * row + p[row]] = if (signs shr row and 1 == 1) -1 else 1
                val det = m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])
                if (det == 1) add(m)
            }
        }

        /** A +90° turn about [axis], right-handed. */
        fun rotate(v: IntArray, axis: Int): IntArray = when (axis) {
            0 -> intArrayOf(v[0], -v[2], v[1])
            1 -> intArrayOf(v[2], v[1], -v[0])
            else -> intArrayOf(-v[1], v[0], v[2])
        }
    }
}

/**
 * Where every sticker is, indexed like [Geometry.slots]: homes[i] is the slot the sticker now in slot
 * i started in. Its color is that home slot's face.
 */
class Cube private constructor(override val puzzle: Puzzle, private var homes: IntArray) : PuzzleState {
    val n get() = puzzle.n
    val geometry = puzzle.geometry

    fun color(slot: Int): Face = geometry.slots[homes[slot]].face

    fun home(slot: Int) = homes[slot]

    override fun apply(m: Move) {
        val perm = geometry.permutation(m)
        val next = IntArray(homes.size)
        for (i in homes.indices) next[perm[i]] = homes[i]
        homes = next
    }

    override fun isSolved(): Boolean = puzzle.isSolved(this)

    /** Every face shows one color. Whole-cube orientation doesn't matter. */
    internal fun facesUniform(): Boolean {
        val first = IntArray(6) { -1 }
        for (i in homes.indices) {
            val f = geometry.slots[i].face.ordinal
            val c = color(i).ordinal
            if (first[f] == -1) first[f] = c else if (first[f] != c) return false
        }
        return true
    }

    /** Every sticker is home, give or take turning the whole cube. Centers may be spun. */
    internal fun isRotationOfSolved(): Boolean =
        geometry.wholeRotations.any { w -> homes.indices.all { h -> homes[w[h]] == h } }

    /** The surface cubies, with where each came from and how it is turned. */
    fun pieces(): List<Piece> = geometry.cubies.map { slotsHere ->
        val here = geometry.slots[slotsHere[0]]
        val homeSlot = geometry.slots[homes[slotsHere[0]]]
        val rot = Geometry.ROTATIONS.first { r ->
            slotsHere.all { i ->
                val h = geometry.slots[homes[i]].face
                val c = geometry.slots[i].face
                r[0] * h.nx + r[1] * h.ny + r[2] * h.nz == c.nx &&
                    r[3] * h.nx + r[4] * h.ny + r[5] * h.nz == c.ny &&
                    r[6] * h.nx + r[7] * h.ny + r[8] * h.nz == c.nz
            }
        }
        Piece(here.x, here.y, here.z, homeSlot.x, homeSlot.y, homeSlot.z, rot)
    }

    override fun copy() = Cube(puzzle, homes.copyOf())

    /** Two base-36 digits per sticker. */
    override fun encode(): String = buildString(homes.size * 2) {
        for (h in homes) {
            append((h / 36).digitToChar(36))
            append((h % 36).digitToChar(36))
        }
    }

    override fun equals(other: Any?) = other is Cube && other.puzzle == puzzle && other.homes.contentEquals(homes)

    override fun hashCode() = 31 * puzzle.hashCode() + homes.contentHashCode()

    companion object {
        fun solved(puzzle: Puzzle) = Cube(puzzle, IntArray(puzzle.geometry.slots.size) { it })

        fun solved(n: Int) = solved(Puzzle.standard(n))

        fun decode(puzzle: Puzzle, s: String): Cube? {
            val size = puzzle.geometry.slots.size
            if (s.length != 2 * size) return null
            val homes = IntArray(size) {
                val hi = s[2 * it].digitToIntOrNull(36) ?: return null
                val lo = s[2 * it + 1].digitToIntOrNull(36) ?: return null
                hi * 36 + lo
            }
            // Must be a permutation.
            if (homes.any { it !in 0 until size } || homes.toSet().size != size) return null
            return Cube(puzzle, homes)
        }

        fun scrambleLength(n: Int) = when (n) {
            2 -> 11
            3 -> 20
            else -> 20 * (n - 2)
        }

        /**
         * Random turns, never two in a row on the same axis (so none cancel). On odd cubes the middle
         * layers stay put, which keeps the centers where the player expects them.
         */
        fun scramble(n: Int, random: Random = Random.Default, length: Int = scrambleLength(n)): List<Move> {
            val layers = (0 until n).filter { n % 2 == 0 || it != n / 2 }
            val moves = ArrayList<Move>(length)
            var lastAxis = -1
            while (moves.size < length) {
                val axis = random.nextInt(3)
                if (axis == lastAxis) continue
                lastAxis = axis
                moves += Move(axis, layers.random(random), listOf(1, -1, 2).random(random))
            }
            return moves
        }
    }
}
