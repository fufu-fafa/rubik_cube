package com.classic.cube.core

import kotlin.random.Random

/**
 * A kind of puzzle. Cubes all turn like an n×n×n cube and differ in how pieces look and what counts
 * as solved; minxes are dodecahedra (see [MinxGeometry]). Add new cube variants to [UNIQUE].
 */
class Puzzle private constructor(
    /** Stable key for saves and stats. Classic cubes use their size ("3") to keep old stats. */
    val id: String,
    val name: String,
    val n: Int,
    val kind: Kind,
    /**
     * Outer faces of the solved shape in doubled units, as min x, max x, min y, max y, min z, max z,
     * or null for a regular cube. Inner cuts always sit on the standard grid.
     */
    private val outer: FloatArray? = null,
    /** Cubies along x, y, z; [n] on every axis except for the tower. */
    val dims: IntArray = intArrayOf(n, n, n),
    private val title: String? = null,
) {
    enum class Kind {
        /** Colored stickers; solved when every face shows one color. */
        STANDARD,
        /** One color, uneven layers; solved when the pieces make a cuboid again. */
        MIRROR,
        /** A dodecahedron turned in fifths; [n] is the number of layers per face. */
        MINX,
        /** A cuboid whose layers turn in quarters even when that bends it out of shape. */
        SHAPESHIFT,
    }

    val geometry get() = Geometry.of(n)

    val isClassic get() = kind == Kind.STANDARD

    val isMinx get() = kind == Kind.MINX

    val label get() = title ?: if (isClassic) "$n×$n" else name

    /** Steps in a full turn of a layer. */
    val turnOrder get() = if (isMinx) 5 else 4

    val minx: MinxGeometry get() = MinxGeometry.of(n)

    fun solved(): PuzzleState = when (kind) {
        Kind.MINX -> Minx.solved(this)
        Kind.SHAPESHIFT -> ShapeShift.solved(this)
        else -> Cube.solved(this)
    }

    fun decode(s: String): PuzzleState? = when (kind) {
        Kind.MINX -> Minx.decode(this, s)
        Kind.SHAPESHIFT -> ShapeShift.decode(this, s)
        else -> Cube.decode(this, s)
    }

    fun scramble(random: Random = Random.Default): List<Move> = when (kind) {
        Kind.MINX -> Minx.scramble(n, random)
        Kind.SHAPESHIFT -> ShapeShift.scramble(this, random)
        else -> Cube.scramble(n, random)
    }

    /** Low edge of [layer] along [axis] in the solved puzzle, doubled units. */
    fun lo(axis: Int, layer: Int): Float =
        if (layer == 0 && outer != null) outer[2 * axis] else (2 * layer - dims[axis]).toFloat()

    fun hi(axis: Int, layer: Int): Float =
        if (layer == dims[axis] - 1 && outer != null) outer[2 * axis + 1] else (2 * layer - dims[axis] + 2).toFloat()

    /** Middle of the solved shape along [axis], so the camera can center it. */
    fun center(axis: Int) = if (isMinx) 0f else (lo(axis, 0) + hi(axis, dims[axis] - 1)) / 2

    /** Half the largest width of the solved shape, plus room for pieces that stick out when scrambled. */
    val viewRadius: Float get() {
        if (isMinx) return 0.82f
        // Out of shape it can reach the edge of a cube as wide as its longest side.
        if (kind == Kind.SHAPESHIFT) return dims.max() * 1.05f
        val half = (0..2).maxOf { (hi(it, dims[it] - 1) - lo(it, 0)) / 2 }
        return if (outer == null) half else half * 1.12f
    }

    /** The box piece [p] fills right now, as min x, max x, min y, max y, min z, max z. */
    fun box(p: Piece): FloatArray {
        val out = FloatArray(6)
        val home = intArrayOf(p.hx, p.hy, p.hz)
        val now = intArrayOf(p.x, p.y, p.z)
        for (j in 0..2) {
            // Row j of the rotation picks which home axis lands on axis j, and with which sign.
            val k = (0..2).first { p.rot[3 * j + it] != 0 }
            val s = p.rot[3 * j + k]
            val layer = geometry.layerOf(home[k])
            val a = lo(k, layer) - home[k]
            val b = hi(k, layer) - home[k]
            out[2 * j] = now[j] + if (s > 0) a else -b
            out[2 * j + 1] = now[j] + if (s > 0) b else -a
        }
        return out
    }

    fun isSolved(cube: Cube): Boolean = when (kind) {
        Kind.STANDARD -> cube.facesUniform()
        Kind.MIRROR -> cube.isRotationOfSolved()
        Kind.MINX, Kind.SHAPESHIFT -> error("not a cube")
    }

    override fun toString() = id

    companion object {
        private val classic = (Geometry.MIN_SIZE..Geometry.MAX_SIZE).associateWith {
            Puzzle(it.toString(), "$it×$it", it, Kind.STANDARD)
        }

        fun standard(n: Int): Puzzle = classic.getValue(n)

        /** Outer layers of 1.3/2.5 (R/L), 1.1/2.7 (U/D) and 2.2/1.6 (F/B) half-units around a 3×3 core. */
        val MIRROR = Puzzle(
            "mirror3", "Mirror", 3, Kind.MIRROR,
            floatArrayOf(-3.5f, 2.3f, -3.7f, 2.1f, -2.6f, 3.2f),
        )

        val MINX_FAMILY: List<Puzzle> = listOf(
            Puzzle("kilominx", "Kilominx", 2, Kind.MINX),
            Puzzle("megaminx", "Megaminx", 3, Kind.MINX),
            Puzzle("masterkilominx", "Master Kilominx", 4, Kind.MINX),
            Puzzle("gigaminx", "Gigaminx", 5, Kind.MINX),
            Puzzle("elitekilominx", "Elite Kilominx", 6, Kind.MINX),
            Puzzle("teraminx", "Teraminx", 7, Kind.MINX),
        )

        /** Two by two across, four layers tall. Turning a tall side a quarter turn bends it out of shape. */
        val TOWER = Puzzle("tower224", "Tower", 4, Kind.SHAPESHIFT, dims = intArrayOf(2, 4, 2), title = "Tower 2×2×4")

        val CLASSIC: List<Puzzle> = classic.values.toList()
        val UNIQUE: List<Puzzle> = listOf(MIRROR, TOWER)
        val ALL = CLASSIC + MINX_FAMILY + UNIQUE

        fun byId(id: String): Puzzle? = ALL.firstOrNull { it.id == id }
    }
}

/**
 * One cubie as it sits now: current center [x], [y], [z], home center [hx], [hy], [hz] (doubled
 * units) and [rot], a row-major 3×3 signed permutation taking home directions to current ones.
 */
class Piece(
    val x: Int, val y: Int, val z: Int,
    val hx: Int, val hy: Int, val hz: Int,
    val rot: IntArray,
) {
    fun coord(axis: Int) = when (axis) {
        0 -> x
        1 -> y
        else -> z
    }

    fun rotate(v: IntArray) = IntArray(3) { j -> rot[3 * j] * v[0] + rot[3 * j + 1] * v[1] + rot[3 * j + 2] * v[2] }
}
