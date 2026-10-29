package com.classic.cube.gl

import android.opengl.Matrix
import com.classic.cube.core.Cube
import com.classic.cube.core.Minx
import com.classic.cube.core.MinxGeometry
import com.classic.cube.core.Move
import com.classic.cube.core.PuzzleState
import com.classic.cube.core.ShapeShift
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.tan

/**
 * Everything the renderer draws and the touch handler changes: the puzzle, the camera, and the layer
 * currently turning. Touches arrive on the UI thread and frames are drawn on the GL thread, so every
 * public method locks on the session.
 */
class CubeSession(state: PuzzleState) {
    /** Told about every move the player finishes (not scramble or replay moves). */
    var onPlayerMove: ((Move) -> Unit)? = null
    /** Told when the queued (scripted) moves have all played out. */
    var onQueueDrained: (() -> Unit)? = null
    /** Asks the view for another frame. */
    var requestRender: () -> Unit = {}

    var state: PuzzleState = state
        private set

    // Camera: the cube's rotation in view space.
    private val orbit = FloatArray(16)
    private val view = FloatArray(16)
    private val proj = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val invMvp = FloatArray(16)
    private var width = 1
    private var height = 1

    /** The layer being turned, by a finger or a scripted move, about [vec] in model space. */
    private class Turn(val axis: Int, val layer: Int, val vec: FloatArray) {
        var angle = 0f // radians
        var target = 0f
        var speed = 0f // radians per second while settling
        var settling = false
        var player = true
    }

    private var turn: Turn? = null
    private val queue = ArrayDeque<Move>()
    private var queueSpeed = 0f
    private var lastFrameNanos = 0L

    // Touch tracking.
    private enum class Mode { NONE, PENDING, TURN, ORBIT }

    private var mode = Mode.NONE
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastT = 0L
    private var velocity = 0f // radians per second along the turn
    private val hit = FloatArray(3)
    /** Ways the touched sticker can turn: about which axis, and which of its layers. */
    private class Option(val axis: Int, val layer: Int, val vec: FloatArray)

    private var options: List<Option> = emptyList()
    /** Outward normal of the face under the finger. */
    private val hitNormal = FloatArray(3)
    /** On a minx, the face under the finger; turning it about its own axis works like a dial. */
    private var hitMinxFace = -1
    private var dial = false
    private val dialCenter = FloatArray(2)
    private var dialLast = 0f
    private var dialAngle = 0f
    private var dragAxis2d = FloatArray(2) // screen direction that turns the layer positively
    private var pixelsPerRadian = 1f
    var touchSlop = 24f
    var inputEnabled = true

    init {
        resetCamera()
    }

    @Synchronized
    fun setState(s: PuzzleState) {
        queue.clear()
        turn = null
        mode = Mode.NONE
        state = s
        requestRender()
    }

    @Synchronized
    fun resetCamera() {
        Matrix.setIdentityM(orbit, 0)
        Matrix.rotateM(orbit, 0, 28f, 1f, 0f, 0f)
        Matrix.rotateM(orbit, 0, -38f, 0f, 1f, 0f)
        requestRender()
    }

    /** Plays [moves] one after another without counting them as the player's. */
    @Synchronized
    fun play(moves: List<Move>, secondsPerMove: Float) {
        finishTurn()
        queue.addAll(moves)
        queueSpeed = step / secondsPerMove
        if (moves.isEmpty()) onQueueDrained?.invoke()
        requestRender()
    }

    /** A copy of the cube that the GL thread won't change underneath. */
    @Synchronized
    fun snapshot(): PuzzleState = state.copy()

    @Synchronized
    fun isBusy() = queue.isNotEmpty() || turn != null

    // ---- Frame ----

    @Synchronized
    fun setViewport(w: Int, h: Int) {
        width = w.coerceAtLeast(1)
        height = h.coerceAtLeast(1)
        val aspect = width.toFloat() / height
        // Fit the cube to the narrower side.
        val halfFov = 18.5f
        val fovY = if (aspect >= 1f) 2 * halfFov else
            Math.toDegrees(2 * atan(tan(Math.toRadians(halfFov.toDouble())) / aspect)).toFloat()
        Matrix.perspectiveM(proj, 0, fovY, aspect, 1f, 20f)
        Matrix.setLookAtM(view, 0, 0f, 0f, DISTANCE, 0f, 0f, 0f, 0f, 1f, 0f)
    }

    /**
     * Advances the animation and fills in what to draw. Returns true while something is still moving.
     */
    @Synchronized
    fun frame(nowNanos: Long, out: FrameState): Boolean {
        val dt = if (lastFrameNanos == 0L) 0f else ((nowNanos - lastFrameNanos) / 1e9f).coerceAtMost(0.05f)
        lastFrameNanos = nowNanos

        if (turn == null && mode != Mode.TURN) startNextQueued()
        turn?.let { t ->
            if (t.settling) {
                val step = t.speed * dt
                val d = t.target - t.angle
                if (abs(d) <= step) {
                    t.angle = t.target
                    completeTurn(t)
                } else {
                    t.angle += step * sign(d)
                }
            }
        }
        if (turn == null && mode != Mode.TURN) startNextQueued()

        updateMatrices()
        out.state = state
        System.arraycopy(view, 0, out.view, 0, 16)
        System.arraycopy(proj, 0, out.proj, 0, 16)
        System.arraycopy(model, 0, out.model, 0, 16)
        val t = turn
        out.turning = t != null
        if (t != null) {
            out.turnAxis = t.axis
            out.turnLayer = t.layer
            t.vec.copyInto(out.turnVec)
            out.turnDegrees = Math.toDegrees(t.angle.toDouble()).toFloat()
        }
        val moving = turn != null || queue.isNotEmpty()
        if (!moving) lastFrameNanos = 0L
        return moving
    }

    private fun startNextQueued() {
        val m = queue.removeFirstOrNull() ?: return
        turn = Turn(m.axis, m.layer, axisVector(m.axis)).apply {
            target = m.turns * step
            speed = queueSpeed * if (abs(m.turns) == 2) 1.5f else 1f
            settling = true
            player = false
        }
    }

    private fun completeTurn(t: Turn) {
        turn = null
        val steps = (t.target / step).roundToInt()
        val move = Move.of(t.axis, t.layer, steps, state.puzzle.turnOrder)
        if (move != null) {
            state.apply(move)
            if (t.player) onPlayerMove?.invoke(move)
        }
        if (!t.player && queue.isEmpty()) onQueueDrained?.invoke()
    }

    /** Snaps any turn in flight to its end so a new one can start. */
    private fun finishTurn() {
        val t = turn ?: return
        if (!t.settling) t.target = quarterTarget(t.axis, t.angle, 0f)
        t.angle = t.target
        completeTurn(t)
    }

    /** Radians in one step of a turn: a quarter on cubes, a fifth on minxes. */
    private val step get() = (2 * PI / state.puzzle.turnOrder).toFloat()

    /** The unit vector a move's [axis] turns about. */
    private fun axisVector(axis: Int): FloatArray =
        if (state.puzzle.isMinx) state.puzzle.minx.normals[axis].let { n -> FloatArray(3) { n[it].toFloat() } }
        else FloatArray(3) { if (it == axis) 1f else 0f }

    private fun updateMatrices() {
        val p = state.puzzle
        val s = 1f / p.viewRadius
        val m = scaleM(s)
        Matrix.translateM(m, 0, -p.center(0), -p.center(1), -p.center(2))
        Matrix.multiplyMM(model, 0, orbit, 0, m, 0)
        val vp = FloatArray(16)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0)
        Matrix.invertM(invMvp, 0, mvp, 0)
    }

    private fun scaleM(s: Float) = FloatArray(16).also {
        Matrix.setIdentityM(it, 0)
        Matrix.scaleM(it, 0, s, s, s)
    }

    // ---- Touch ----

    @Synchronized
    fun down(x: Float, y: Float, timeMillis: Long) {
        downX = x; downY = y; lastX = x; lastY = y; lastT = timeMillis
        velocity = 0f
        updateMatrices()
        mode = if (inputEnabled && queue.isEmpty() && pick(x, y)) Mode.PENDING else Mode.ORBIT
        if (mode == Mode.PENDING) finishTurn()
    }

    /** A second finger always orbits. */
    @Synchronized
    fun secondPointer(x: Float, y: Float) {
        cancelDrag()
        mode = Mode.ORBIT
        lastX = x; lastY = y
    }

    @Synchronized
    fun move(x: Float, y: Float, timeMillis: Long) {
        when (mode) {
            Mode.PENDING -> if (hypot(x - downX, y - downY) > touchSlop) {
                startDrag(x - downX, y - downY)
                if (mode == Mode.TURN) dragTurn(x, y, timeMillis)
            }
            Mode.TURN -> dragTurn(x, y, timeMillis)
            Mode.ORBIT -> {
                val k = 180f / minOf(width, height) * 1.3f
                val r = FloatArray(16)
                Matrix.setRotateM(r, 0, (x - lastX) * k, 0f, 1f, 0f)
                val r2 = FloatArray(16)
                Matrix.setRotateM(r2, 0, (y - lastY) * k, 1f, 0f, 0f)
                val tmp = FloatArray(16)
                Matrix.multiplyMM(tmp, 0, r2, 0, r, 0)
                val next = FloatArray(16)
                Matrix.multiplyMM(next, 0, tmp, 0, orbit, 0)
                System.arraycopy(next, 0, orbit, 0, 16)
            }
            Mode.NONE -> {}
        }
        lastX = x; lastY = y; lastT = timeMillis
        requestRender()
    }

    private fun dragTurn(x: Float, y: Float, timeMillis: Long) {
        val t = turn ?: return
        val angle = if (dial) {
            // Screen y points down, so a counterclockwise turn (seen from outside) lowers atan2.
            val a = atan2(y - dialCenter[1], x - dialCenter[0])
            dialAngle -= wrap(a - dialLast)
            dialLast = a
            dialAngle
        } else {
            val along = (x - downX) * dragAxis2d[0] + (y - downY) * dragAxis2d[1]
            along / pixelsPerRadian
        }
        val dtS = (timeMillis - lastT).coerceAtLeast(1) / 1000f
        velocity = 0.6f * velocity + 0.4f * (angle - t.angle) / dtS
        t.angle = angle
    }

    /** Spins the cube about the line of sight, as when two fingers twist. Positive is counterclockwise. */
    @Synchronized
    fun twist(degrees: Float) {
        if (mode != Mode.ORBIT) return
        val r = FloatArray(16)
        Matrix.setRotateM(r, 0, degrees, 0f, 0f, 1f)
        val next = FloatArray(16)
        Matrix.multiplyMM(next, 0, r, 0, orbit, 0)
        System.arraycopy(next, 0, orbit, 0, 16)
        requestRender()
    }

    @Synchronized
    fun up() {
        if (mode == Mode.TURN) {
            turn?.let { t ->
                t.target = quarterTarget(t.axis, t.angle, velocity)
                t.speed = (PI * 3).toFloat()
                t.settling = true
            }
        }
        mode = Mode.NONE
        requestRender()
    }

    private fun cancelDrag() {
        if (mode == Mode.TURN) turn?.let { it.target = 0f; it.speed = (PI * 3).toFloat(); it.settling = true }
        mode = Mode.NONE
    }

    /**
     * The quarter turn to settle on: the nearest one, except that a short flick still makes a
     * quarter turn in its direction.
     */
    private fun quarterTarget(axis: Int, angle: Float, velocity: Float): Float {
        val q = step
        val quarters = angle / q
        var target = round(quarters)
        if (target == 0f && abs(quarters) > 0.12f && abs(velocity) > FLICK && sign(velocity) == sign(quarters)) {
            target = sign(quarters)
        }
        return target.coerceIn(-2f, 2f) * q
    }

    /** Finds what is under the finger and fills [hit] and [options]. */
    private fun pick(x: Float, y: Float): Boolean {
        val near = unproject(x, y, -1f)
        val far = unproject(x, y, 1f)
        val dir = FloatArray(3) { far[it] - near[it] }
        hitMinxFace = -1
        options = when (val st = state) {
            is Cube -> pickCube(st, near, dir)
            is Minx -> pickMinx(st, near, dir)
            is ShapeShift -> pickShift(st, near, dir)
            else -> emptyList()
        }
        return options.isNotEmpty()
    }

    /** Ray against every piece's box, in doubled cube units. A cube face can turn about its two in-plane axes. */
    private fun pickCube(cube: Cube, near: FloatArray, dir: FloatArray): List<Option> {
        val pieces = cube.pieces()
        val (i, hitAxis) = pickBox(pieces.map { cube.puzzle.box(it) }, near, dir) ?: return emptyList()
        return (0..2).filter { it != hitAxis }.map { a ->
            Option(a, cube.geometry.layerOf(pieces[i].coord(a)), axisVector(a))
        }
    }

    /** Like [pickCube], with every cubie a unit box wherever it has turned to. */
    private fun pickShift(shift: ShapeShift, near: FloatArray, dir: FloatArray): List<Option> {
        val boxes = (0 until shift.count).map { i ->
            val p = shift.position(i)
            FloatArray(6) { k -> p[k / 2] + if (k % 2 == 0) -1f else 1f }
        }
        val (i, hitAxis) = pickBox(boxes, near, dir) ?: return emptyList()
        val p = shift.position(i)
        return (0..2).filter { it != hitAxis }.map { a -> Option(a, shift.layerOf(p[a]), axisVector(a)) }
    }

    /**
     * The nearest box the ray enters, as its index and the axis of the face it enters through.
     * Fills [hit] and [hitNormal].
     */
    private fun pickBox(boxes: List<FloatArray>, near: FloatArray, dir: FloatArray): Pair<Int, Int>? {
        var best = Float.MAX_VALUE
        var found: Pair<Int, Int>? = null
        for ((index, b) in boxes.withIndex()) {
            // Slab test: the ray is inside the box between the latest entry and the earliest exit.
            var tIn = -Float.MAX_VALUE
            var tOut = Float.MAX_VALUE
            var axisIn = -1
            for (axis in 0..2) {
                if (abs(dir[axis]) < 1e-9f) {
                    if (near[axis] < b[2 * axis] || near[axis] > b[2 * axis + 1]) { tIn = Float.MAX_VALUE; break }
                    continue
                }
                val t0 = (b[2 * axis] - near[axis]) / dir[axis]
                val t1 = (b[2 * axis + 1] - near[axis]) / dir[axis]
                val enter = minOf(t0, t1)
                if (enter > tIn) {
                    tIn = enter
                    axisIn = axis
                }
                tOut = minOf(tOut, maxOf(t0, t1))
            }
            if (axisIn < 0 || tIn > tOut || tIn < 0 || tIn >= best) continue
            best = tIn
            found = index to axisIn
            for (i in 0..2) hit[i] = near[i] + dir[i] * tIn
        }
        val axis = found?.second ?: return null
        for (i in 0..2) hitNormal[i] = if (i == axis) sign(-dir[i]) else 0f
        return found
    }

    /**
     * Ray against the dodecahedron (inradius 1), then the sticker under the hit. It can turn about
     * any face axis that has it in a layer, its own face included.
     */
    private fun pickMinx(minx: Minx, near: FloatArray, dir: FloatArray): List<Option> {
        val g = minx.geometry
        var tIn = -Float.MAX_VALUE
        var tOut = Float.MAX_VALUE
        var face = -1
        for (f in g.normals.indices) {
            val n = g.normals[f]
            val dn = (dir[0] * n[0] + dir[1] * n[1] + dir[2] * n[2]).toFloat()
            val pn = (near[0] * n[0] + near[1] * n[1] + near[2] * n[2]).toFloat()
            if (abs(dn) < 1e-9f) {
                if (pn > 1f) return emptyList()
                continue
            }
            val t = (1f - pn) / dn
            if (dn < 0) {
                if (t > tIn) { tIn = t; face = f }
            } else {
                tOut = minOf(tOut, t)
            }
        }
        if (face < 0 || tIn > tOut || tIn < 0) return emptyList()
        hitMinxFace = face
        for (i in 0..2) hit[i] = near[i] + dir[i] * tIn
        for (i in 0..2) hitNormal[i] = g.normals[face][i].toFloat()
        val p = DoubleArray(3) { hit[it].toDouble() }
        val sticker = g.stickers.indices.filter { g.stickers[it].face == face }.minByOrNull { i ->
            if (contains(g.stickers[i].polygon, g.normals[face], p)) -1.0
            else MinxGeometry.sub(g.stickers[i].centroid, p).let { MinxGeometry.dot(it, it) }
        } ?: return emptyList()
        return g.normals.indices.mapNotNull { f ->
            val layer = g.layer(sticker, f)
            if (layer < 0) null else Option(f, layer, axisVector(f))
        }
    }

    /** Whether [p] (on the face plane) is inside convex [poly], whichever way it winds. */
    private fun contains(poly: List<DoubleArray>, n: DoubleArray, p: DoubleArray): Boolean {
        var sign = 0
        for (k in poly.indices) {
            val a = poly[k]
            val b = poly[(k + 1) % poly.size]
            val s = MinxGeometry.dot(MinxGeometry.cross(MinxGeometry.sub(b, a), MinxGeometry.sub(p, a)), n)
            val sg = if (s > 1e-12) 1 else if (s < -1e-12) -1 else 0
            if (sg == 0) continue
            if (sign == 0) sign = sg else if (sg != sign) return false
        }
        return true
    }

    /**
     * Chooses the turn whose motion at the touched point best matches the first stretch of the drag,
     * then has the layer follow the finger.
     */
    private fun startDrag(dx: Float, dy: Float) {
        val dragLen = hypot(dx, dy)
        var bestScore = -1f
        var choice: Option? = null
        var screenVel = FloatArray(2)
        val eps = 0.02f * state.puzzle.viewRadius
        val a = project(hit)
        for (o in options) {
            // Under a turn about o.vec the touched point moves along vec × hit. Only the part along
            // the face counts: far from the axis it mostly lifts off the face, which a drag can't do.
            val v = floatArrayOf(
                o.vec[1] * hit[2] - o.vec[2] * hit[1],
                o.vec[2] * hit[0] - o.vec[0] * hit[2],
                o.vec[0] * hit[1] - o.vec[1] * hit[0],
            )
            val out = v[0] * hitNormal[0] + v[1] * hitNormal[1] + v[2] * hitNormal[2]
            for (i in 0..2) v[i] -= out * hitNormal[i]
            val b = project(FloatArray(3) { hit[it] + v[it] * eps })
            val sx = (b[0] - a[0]) / eps
            val sy = (b[1] - a[1]) / eps
            val len = hypot(sx, sy)
            if (len < 1e-3f) continue
            val score = abs(dx * sx + dy * sy) / (len * dragLen)
            if (score > bestScore) {
                bestScore = score
                choice = o
                screenVel = floatArrayOf(sx, sy)
            }
        }
        var o = choice ?: run { mode = Mode.ORBIT; return }
        // Spinning a minx face: follow the angle swept around its center, whichever way the drag goes.
        val own = options.firstOrNull { it.axis == hitMinxFace }
        dial = own != null && (o === own || bestScore < 0.5f)
        if (dial && own != null) {
            o = own
            val n = state.puzzle.minx.normals[own.axis]
            val c = project(FloatArray(3) { n[it].toFloat() })
            c.copyInto(dialCenter)
            // Count the sweep from where the finger went down.
            dialLast = atan2(downY - c[1], downX - c[0])
            dialAngle = 0f
            turn = Turn(o.axis, o.layer, o.vec)
            mode = Mode.TURN
            return
        }
        val len = hypot(screenVel[0], screenVel[1])
        dragAxis2d = floatArrayOf(screenVel[0] / len, screenVel[1] / len)
        // Keep the sticker under the finger, but don't let turns near the axis get twitchy.
        pixelsPerRadian = maxOf(len * 1.1f, minOf(width, height) * 0.22f)
        turn = Turn(o.axis, o.layer, o.vec)
        mode = Mode.TURN
    }

    /** [a] folded into -π..π. */
    private fun wrap(a: Float): Float {
        var r = a
        while (r > PI) r -= (2 * PI).toFloat()
        while (r < -PI) r += (2 * PI).toFloat()
        return r
    }

    private fun unproject(x: Float, y: Float, z: Float): FloatArray {
        val ndc = floatArrayOf(2 * x / width - 1, 1 - 2 * y / height, z, 1f)
        val out = FloatArray(4)
        Matrix.multiplyMV(out, 0, invMvp, 0, ndc, 0)
        return floatArrayOf(out[0] / out[3], out[1] / out[3], out[2] / out[3])
    }

    private fun project(p: FloatArray): FloatArray {
        val out = FloatArray(4)
        Matrix.multiplyMV(out, 0, mvp, 0, floatArrayOf(p[0], p[1], p[2], 1f), 0)
        return floatArrayOf((out[0] / out[3] + 1) * width / 2, (1 - out[1] / out[3]) * height / 2)
    }

    companion object {
        const val DISTANCE = 6.2f
        /** Radians per second that count as a flick. */
        const val FLICK = 2.5f
    }
}

/** A copy of what one frame needs, so drawing happens outside the lock. */
class FrameState {
    var state: PuzzleState? = null
    val view = FloatArray(16)
    val proj = FloatArray(16)
    val model = FloatArray(16)
    var turning = false
    var turnAxis = 0
    var turnLayer = 0
    val turnVec = FloatArray(3)
    var turnDegrees = 0f
}
