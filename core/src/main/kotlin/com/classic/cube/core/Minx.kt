package com.classic.cube.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** One sticker of a minx: a convex polygon on [face], corners in order around the face. */
class MinxSticker(val face: Int, val polygon: List<DoubleArray>) {
    val centroid = DoubleArray(3) { a -> polygon.sumOf { it[a] } / polygon.size }
}

/**
 * A face-turning dodecahedron with [n] layers per face: Kilominx (2), Megaminx (3), Master Kilominx
 * (4), Gigaminx (5), Elite Kilominx (6), Teraminx (7). The solid has inradius 1, face 0 on top.
 *
 * Every face has the same cut planes parallel to it, at [depths] from the center. A turn of face f,
 * layer k moves every sticker whose centroid lies in that slab: layer 0 is beyond the outermost cut,
 * layer 1 the slab under it, and so on. Stickers near the middle of an axis belong to
 * no layer of it (turning them is just turning the whole puzzle and the opposite face).
 *
 * Odd sizes get their stickers by cutting each face with every plane. Even sizes have no centers or
 * middle edges: they turn like the next odd size, but each face is drawn as five kites (one per
 * corner) split into a k×k grid, so the face stays fully covered. Kite edges don't follow the cut
 * planes, so [kites] tells the renderer it can't split the solid along a turning slab.
 */
class MinxGeometry private constructor(val n: Int) {
    val normals: List<DoubleArray>
    val faces: List<List<DoubleArray>>
    /** Cut depths, ascending. */
    val depths: DoubleArray
    val stickers: List<MinxSticker>
    /** Even sizes: stickers are corner kites rather than cut-plane polygons. */
    val kites get() = n % 2 == 0
    private val layerTable: Array<IntArray>
    private val perms = HashMap<Move, IntArray>()

    val layers get() = depths.size

    init {
        normals = buildNormals()
        faces = normals.indices.map { facePolygon(it) }
        val config = CONFIG.getValue(n)
        depths = config.cuts.map { 1 - it * (1 - 1 / sqrt(5.0)) }.sorted().toDoubleArray()
        stickers = if (config.kite == null) cutStickers() else kiteStickers(config.kite)
        layerTable = Array(stickers.size) { i -> IntArray(12) { f -> layerOf(stickers[i].centroid, f) } }
    }

    /** The layer of face [face] that holds sticker [sticker], or -1 if none. */
    fun layer(sticker: Int, face: Int) = layerTable[sticker][face]

    /** Which layer of [face] point [p] is in, or -1. */
    fun layerOf(p: DoubleArray, face: Int): Int {
        val d = dot(p, normals[face])
        for (k in 0 until layers) if (d > depths[layers - 1 - k]) return k
        return -1
    }

    /**
     * Where the solid must be split to show turn (face, layer) moving: the slab of distances along
     * the face normal that holds its stickers.
     */
    fun slab(face: Int, layer: Int): Pair<Double, Double> {
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (i in stickers.indices) if (layerTable[i][face] == layer) for (v in stickers[i].polygon) {
            val d = dot(v, normals[face])
            if (d < lo) lo = d
            if (d > hi) hi = d
        }
        return (lo - 1e-4) to if (layer == 0) 10.0 else hi + 1e-4
    }

    /** perm[i] is where the sticker in slot i ends up. */
    @Synchronized
    fun permutation(m: Move): IntArray = perms.getOrPut(m) {
        val angle = m.turns * 2 * PI / 5
        val axis = normals[m.axis]
        IntArray(stickers.size) { i ->
            if (layerTable[i][m.axis] != m.layer) return@IntArray i
            val c = rotate(stickers[i].centroid, axis, angle)
            val f = rotate(normals[stickers[i].face], axis, angle)
            val face = normals.indices.maxBy { dot(normals[it], f) }
            stickers.indices.filter { stickers[it].face == face }.minBy { dist2(stickers[it].centroid, c) }
        }
    }

    private fun cutStickers(): List<MinxSticker> {
        val out = ArrayList<MinxSticker>()
        for (i in faces.indices) {
            var polys = listOf(faces[i])
            for (j in normals.indices) {
                if (j == i) continue
                for (d in depths) polys = polys.flatMap { split(it, normals[j], d) }
            }
            polys.forEach { out += MinxSticker(i, it) }
        }
        return out
    }

    private fun kiteStickers(grid: DoubleArray): List<MinxSticker> {
        val g = doubleArrayOf(0.0, *grid, 1.0)
        val k = g.size - 1
        val out = ArrayList<MinxSticker>()
        for (i in faces.indices) {
            val f = faces[i]
            val c = normals[i]
            for (v in 0 until 5) {
                val corner = f[v]
                val m1 = DoubleArray(3) { (f[v][it] + f[(v + 1) % 5][it]) / 2 }
                val m2 = DoubleArray(3) { (f[v][it] + f[(v + 4) % 5][it]) / 2 }
                // Bilinear over the kite corner → m1 → center → m2.
                fun at(u: Double, w: Double) = DoubleArray(3) { a ->
                    corner[a] * (1 - u) * (1 - w) + m1[a] * u * (1 - w) + c[a] * u * w + m2[a] * (1 - u) * w
                }
                for (a in 0 until k) for (b in 0 until k) {
                    out += MinxSticker(i, listOf(at(g[a], g[b]), at(g[a + 1], g[b]), at(g[a + 1], g[b + 1]), at(g[a], g[b + 1])))
                }
            }
        }
        return out
    }

    /** Face 0 up, five faces around it tilted up, five tilted down, face 11 down. */
    private fun buildNormals(): List<DoubleArray> {
        val el = Math.asin(1 / sqrt(5.0))
        val list = ArrayList<DoubleArray>()
        list += doubleArrayOf(0.0, 1.0, 0.0)
        for (k in 0 until 5) {
            val az = k * 2 * PI / 5
            list += doubleArrayOf(cos(el) * sin(az), sin(el), cos(el) * cos(az))
        }
        for (k in 0 until 5) {
            val az = k * 2 * PI / 5 + PI / 5
            list += doubleArrayOf(cos(el) * sin(az), -sin(el), cos(el) * cos(az))
        }
        list += doubleArrayOf(0.0, -1.0, 0.0)
        return list
    }

    /** The five corners of face [i], counterclockwise seen from outside. */
    private fun facePolygon(i: Int): List<DoubleArray> {
        val n = normals[i]
        val nbrs = normals.indices.filter { it != i && abs(dot(normals[it], n) - 1 / sqrt(5.0)) < 1e-6 }
        val u = normalize(sub(normals[nbrs[0]], scale(n, dot(normals[nbrs[0]], n))))
        val w = cross(n, u)
        val sorted = nbrs.sortedBy { atan2(dot(normals[it], w), dot(normals[it], u)) }
        return (0 until 5).map { k -> solve(n, normals[sorted[k]], normals[sorted[(k + 1) % 5]]) }
    }

    companion object {
        val SIZES = listOf(2, 3, 4, 5, 6, 7)

        private class Config(val cuts: List<Double>, val kite: DoubleArray? = null)

        /**
         * Cuts are given as how far the cut line runs in from each edge of a face, as a fraction of
         * the face's inradius. Even sizes borrow the next odd size's turning and add a kite grid.
         */
        private val CONFIG = mapOf(
            2 to Config(listOf(0.5), kite = doubleArrayOf()),
            3 to Config(listOf(0.38)),
            4 to Config(listOf(0.75, 0.35), kite = doubleArrayOf(0.45)),
            5 to Config(listOf(0.5, 0.24)),
            6 to Config(listOf(0.82, 0.52, 0.25), kite = doubleArrayOf(0.3, 0.65)),
            7 to Config(listOf(0.52, 0.34, 0.16)),
        )

        private val cache = HashMap<Int, MinxGeometry>()

        @Synchronized
        fun of(n: Int): MinxGeometry = cache.getOrPut(n) { MinxGeometry(n) }

        fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        fun sub(a: DoubleArray, b: DoubleArray) = DoubleArray(3) { a[it] - b[it] }
        fun scale(a: DoubleArray, s: Double) = DoubleArray(3) { a[it] * s }
        fun cross(a: DoubleArray, b: DoubleArray) =
            doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
        fun normalize(a: DoubleArray) = scale(a, 1 / sqrt(dot(a, a)))
        private fun dist2(a: DoubleArray, b: DoubleArray) = sub(a, b).let { dot(it, it) }

        /** Rodrigues: [v] turned by [angle] about unit [axis], right-handed. */
        fun rotate(v: DoubleArray, axis: DoubleArray, angle: Double): DoubleArray {
            val c = cos(angle)
            val s = sin(angle)
            val k = cross(axis, v)
            val d = dot(axis, v) * (1 - c)
            return DoubleArray(3) { v[it] * c + k[it] * s + axis[it] * d }
        }

        /** The point on all three planes n·p = 1. */
        private fun solve(a: DoubleArray, b: DoubleArray, c: DoubleArray): DoubleArray {
            val bc = cross(b, c)
            val det = dot(a, bc)
            val ca = cross(c, a)
            val ab = cross(a, b)
            return DoubleArray(3) { (bc[it] + ca[it] + ab[it]) / det }
        }

        /** Splits a convex polygon by the plane n·p = d, dropping slivers. */
        private fun split(poly: List<DoubleArray>, n: DoubleArray, d: Double): List<List<DoubleArray>> {
            val above = ArrayList<DoubleArray>()
            val below = ArrayList<DoubleArray>()
            for (k in poly.indices) {
                val p = poly[k]
                val q = poly[(k + 1) % poly.size]
                val dp = dot(p, n) - d
                val dq = dot(q, n) - d
                if (dp >= -1e-9) above += p
                if (dp <= 1e-9) below += p
                if ((dp > 1e-9 && dq < -1e-9) || (dp < -1e-9 && dq > 1e-9)) {
                    val t = dp / (dp - dq)
                    val x = DoubleArray(3) { p[it] + (q[it] - p[it]) * t }
                    above += x
                    below += x
                }
            }
            return listOf(above, below).filter { area(it) > 1e-7 }
        }

        private fun area(poly: List<DoubleArray>): Double {
            if (poly.size < 3) return 0.0
            var s = doubleArrayOf(0.0, 0.0, 0.0)
            for (k in poly.indices) {
                val c = cross(poly[k], poly[(k + 1) % poly.size])
                s = DoubleArray(3) { s[it] + c[it] }
            }
            return sqrt(dot(s, s)) / 2
        }
    }
}

/** Sticker positions of a minx, like [Cube]: homes[i] is the slot the sticker now in slot i started in. */
class Minx private constructor(override val puzzle: Puzzle, private var homes: IntArray) : PuzzleState {
    val geometry = MinxGeometry.of(puzzle.n)

    /** The face color of the sticker in [slot], 0..11. */
    fun color(slot: Int): Int = geometry.stickers[homes[slot]].face

    override fun apply(m: Move) {
        val perm = geometry.permutation(m)
        val next = IntArray(homes.size)
        for (i in homes.indices) next[perm[i]] = homes[i]
        homes = next
    }

    /** Every face shows one color. */
    override fun isSolved(): Boolean {
        val first = IntArray(12) { -1 }
        for (i in homes.indices) {
            val f = geometry.stickers[i].face
            val c = color(i)
            if (first[f] == -1) first[f] = c else if (first[f] != c) return false
        }
        return true
    }

    override fun copy() = Minx(puzzle, homes.copyOf())

    override fun encode(): String = buildString(homes.size * 2) {
        for (h in homes) {
            append((h / 36).digitToChar(36))
            append((h % 36).digitToChar(36))
        }
    }

    override fun equals(other: Any?) = other is Minx && other.puzzle == puzzle && other.homes.contentEquals(homes)

    override fun hashCode() = 31 * puzzle.hashCode() + homes.contentHashCode()

    companion object {
        fun solved(puzzle: Puzzle) = Minx(puzzle, IntArray(MinxGeometry.of(puzzle.n).stickers.size) { it })

        fun decode(puzzle: Puzzle, s: String): Minx? {
            val size = MinxGeometry.of(puzzle.n).stickers.size
            if (s.length != 2 * size) return null
            val homes = IntArray(size) {
                val hi = s[2 * it].digitToIntOrNull(36) ?: return null
                val lo = s[2 * it + 1].digitToIntOrNull(36) ?: return null
                hi * 36 + lo
            }
            if (homes.any { it !in 0 until size } || homes.toSet().size != size) return null
            return Minx(puzzle, homes)
        }

        fun scrambleLength(n: Int) = when (n) {
            2 -> 30
            3 -> 70
            else -> 30 * n
        }

        /** Random turns of random layers, never the same face twice in a row. */
        fun scramble(n: Int, random: Random = Random.Default, length: Int = scrambleLength(n)): List<Move> {
            val layers = MinxGeometry.of(n).layers
            val moves = ArrayList<Move>(length)
            var last = -1
            while (moves.size < length) {
                val face = random.nextInt(12)
                if (face == last) continue
                last = face
                moves += Move(face, random.nextInt(layers), listOf(1, -1, 2, -2).random(random))
            }
            return moves
        }
    }
}
