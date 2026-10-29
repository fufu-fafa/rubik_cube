package com.classic.cube.gl

import android.opengl.GLES20.*
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.classic.cube.core.Cube
import com.classic.cube.core.Face
import com.classic.cube.core.Geometry
import com.classic.cube.core.Minx
import com.classic.cube.core.MinxGeometry
import com.classic.cube.core.Puzzle
import com.classic.cube.core.ShapeShift
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.cos
import kotlin.math.sin

/** Sticker colors by [Face] ordinal, linear 0..1 RGB. */
val StickerColors: Array<FloatArray> = arrayOf(
    rgb(0xC8102E), // right: red
    rgb(0xFF6A13), // left: orange
    rgb(0xF4F4F0), // up: white
    rgb(0xFFD200), // down: yellow
    rgb(0x00A651), // front: green
    rgb(0x0057B8), // back: blue
)

/** Minx face colors by face index. */
val MinxColors: Array<FloatArray> = arrayOf(
    rgb(0xF4F4F0), // white
    rgb(0xC8102E), // red
    rgb(0x00A651), // green
    rgb(0x6B3FA0), // purple
    rgb(0xFFD200), // yellow
    rgb(0x0057B8), // blue
    rgb(0xF5E6A8), // cream
    rgb(0x5BC2E7), // light blue
    rgb(0xF28DB2), // pink
    rgb(0x8DC63F), // lime
    rgb(0xFF6A13), // orange
    rgb(0x8A8D93), // gray
)

/** The plate finish of mirror pieces. */
val MirrorColor: FloatArray = rgb(0xD9DCE3)

private fun rgb(c: Int) = floatArrayOf((c shr 16 and 255) / 255f, (c shr 8 and 255) / 255f, (c and 255) / 255f)

/**
 * Draws the cube as dark plastic cubies with rounded stickers, lit from the upper left in view
 * space so the light stays put as the cube is turned.
 */
class CubeRenderer(private val session: CubeSession) : GLSurfaceView.Renderer {
    /** Background, set from the UI theme. */
    @Volatile var clearColor = floatArrayOf(0.95f, 0.96f, 0.98f)

    /** Called when an animation is still running and another frame is needed. */
    var onAnimating: () -> Unit = {}

    private var program = 0
    private var aPos = 0
    private var aNormal = 0
    private var uMvp = 0
    private var uModelView = 0
    private var uColor = 0
    private var uGloss = 0
    private var uMirror = 0
    private var uClipN = 0
    private var uClip = 0
    private var uClipMode = 0

    private lateinit var body: Mesh
    private lateinit var sticker: Mesh

    private val frame = FrameState()
    private val vp = FloatArray(16)
    private val layerRot = FloatArray(16)
    private val base = FloatArray(16)
    private val m = FloatArray(16)
    private val mv = FloatArray(16)
    private val mvp = FloatArray(16)
    private val tmp = FloatArray(16)
    private val faceRot = Array(6) { FloatArray(16) }
    private val faceAxes = Array(6) { IntArray(2) }
    /** Rounded plates for mirror pieces, keyed by size in hundredths. */
    private val plates = HashMap<Long, Mesh>()
    private val minxMeshes = HashMap<Int, MinxMeshes>()
    private val slabs = HashMap<Long, Pair<Double, Double>>()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = buildProgram(VERTEX, FRAGMENT)
        aPos = glGetAttribLocation(program, "aPos")
        aNormal = glGetAttribLocation(program, "aNormal")
        uMvp = glGetUniformLocation(program, "uMvp")
        uModelView = glGetUniformLocation(program, "uModelView")
        uColor = glGetUniformLocation(program, "uColor")
        uGloss = glGetUniformLocation(program, "uGloss")
        uMirror = glGetUniformLocation(program, "uMirror")
        uClipN = glGetUniformLocation(program, "uClipN")
        uClip = glGetUniformLocation(program, "uClip")
        uClipMode = glGetUniformLocation(program, "uClipMode")
        plates.clear()
        minxMeshes.clear()
        body = Mesh.box()
        sticker = Mesh.roundedSquare(half = 0.84f, radius = 0.2f)
        glEnable(GL_DEPTH_TEST)
        glEnable(GL_CULL_FACE)
        for (f in Face.entries) {
            faceRotation(f, faceRot[f.ordinal])
            // Which world axes the sticker mesh's own x and y end up along.
            val v = FloatArray(4)
            Matrix.multiplyMV(v, 0, faceRot[f.ordinal], 0, floatArrayOf(1f, 0f, 0f, 0f), 0)
            faceAxes[f.ordinal][0] = (0..2).maxBy { abs(v[it]) }
            Matrix.multiplyMV(v, 0, faceRot[f.ordinal], 0, floatArrayOf(0f, 1f, 0f, 0f), 0)
            faceAxes[f.ordinal][1] = (0..2).maxBy { abs(v[it]) }
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        glViewport(0, 0, width, height)
        session.setViewport(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val animating = session.frame(System.nanoTime(), frame)
        val c = clearColor
        glClearColor(c[0], c[1], c[2], 1f)
        glClear(GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT)
        val state = frame.state ?: return

        glUseProgram(program)
        Matrix.multiplyMM(vp, 0, frame.proj, 0, frame.view, 0)
        Matrix.setIdentityM(layerRot, 0)
        if (frame.turning) {
            val v = frame.turnVec
            Matrix.rotateM(layerRot, 0, frame.turnDegrees, v[0], v[1], v[2])
        }
        glUniform1f(uClipMode, 0f)
        if (state is Minx) {
            drawMinx(state)
            if (animating) onAnimating()
            return
        }
        if (state is ShapeShift) {
            drawShapeShift(state)
            if (animating) onAnimating()
            return
        }
        val cube = state as Cube

        val geometry = cube.geometry
        val n = cube.n
        val turnCoord = if (frame.turning) geometry.layerCoord(frame.turnLayer) else Int.MIN_VALUE

        if (cube.puzzle.kind == Puzzle.Kind.MIRROR) {
            drawShaped(cube, turnCoord)
            if (animating) onAnimating()
            return
        }
        glUniform1f(uMirror, 0f)

        // Cubie bodies. Inner ones only show through the gap of a turning layer.
        body.bind(aPos, aNormal)
        glUniform3f(uColor, 0.07f, 0.07f, 0.08f)
        glUniform1f(uGloss, 0.5f)
        val coords = Geometry.coords(n)
        for (x in coords) for (y in coords) for (z in coords) {
            val p = intArrayOf(x, y, z)
            val surface = p.any { abs(it) == n - 1 }
            val near = frame.turning && kotlin.math.abs(p[frame.turnAxis] - turnCoord) <= 2
            if (!surface && !near) continue
            placeCubie(p, p[frame.turnAxis.coerceAtLeast(0)] == turnCoord && frame.turning)
            Matrix.scaleM(m, 0, 0.985f, 0.985f, 0.985f)
            draw(body)
        }

        // Stickers.
        sticker.bind(aPos, aNormal)
        glUniform1f(uGloss, 1f)
        for (i in geometry.slots.indices) {
            val s = geometry.slots[i]
            val p = intArrayOf(s.x, s.y, s.z)
            placeCubie(p, frame.turning && s.coord(frame.turnAxis) == turnCoord)
            Matrix.multiplyMM(tmp, 0, m, 0, faceRot[s.face.ordinal], 0)
            Matrix.translateM(tmp, 0, 0f, 0f, 1.004f)
            System.arraycopy(tmp, 0, m, 0, 16)
            val col = StickerColors[cube.color(i).ordinal]
            glUniform3f(uColor, col[0], col[1], col[2])
            draw(sticker)
        }

        if (animating) onAnimating()
    }

    /**
     * Draws a shape-shifting cuboid: every cubie a dark unit box wherever it has turned to, with its
     * stickers turned along with it.
     */
    private fun drawShapeShift(shift: ShapeShift) {
        glUniform1f(uMirror, 0f)
        val turnCoord = if (frame.turning) shift.layerCoord(frame.turnLayer) else Int.MIN_VALUE
        val moving = BooleanArray(shift.count) { frame.turning && shift.position(it)[frame.turnAxis] == turnCoord }

        body.bind(aPos, aNormal)
        glUniform3f(uColor, 0.07f, 0.07f, 0.08f)
        glUniform1f(uGloss, 0.5f)
        for (i in 0 until shift.count) {
            placeCubie(shift.position(i), moving[i])
            Matrix.scaleM(m, 0, 0.985f, 0.985f, 0.985f)
            draw(body)
        }

        sticker.bind(aPos, aNormal)
        glUniform1f(uGloss, 1f)
        for (i in 0 until shift.count) {
            val p = shift.position(i)
            val r = shift.rotation(i)
            for (home in shift.stickers(i)) {
                val n = ShapeShift.apply(r, intArrayOf(home.nx, home.ny, home.nz))
                placeCubie(p, moving[i])
                Matrix.multiplyMM(tmp, 0, m, 0, faceRot[Face.of(n[0], n[1], n[2]).ordinal], 0)
                Matrix.translateM(tmp, 0, 0f, 0f, 1.004f)
                System.arraycopy(tmp, 0, m, 0, 16)
                val col = StickerColors[home.ordinal]
                glUniform3f(uColor, col[0], col[1], col[2])
                draw(sticker)
            }
        }
    }

    /**
     * Draws a puzzle whose pieces have their own shapes: each piece is its box in dark plastic with a
     * chrome plate on every outer face.
     */
    private fun drawShaped(cube: Cube, turnCoord: Int) {
        val puzzle = cube.puzzle
        val geometry = cube.geometry
        val pieces = cube.pieces()
        val boxes = pieces.map { puzzle.box(it) }
        val turning = BooleanArray(pieces.size) { frame.turning && pieces[it].coord(frame.turnAxis) == turnCoord }

        body.bind(aPos, aNormal)
        glUniform1f(uMirror, 0f)
        glUniform3f(uColor, 0.07f, 0.07f, 0.08f)
        glUniform1f(uGloss, 0.5f)
        // The core, so the gap of a turning layer isn't see-through.
        placeBox(floatArrayOf(-1f, 1f, -1f, 1f, -1f, 1f), false, 0.02f)
        draw(body)
        for (i in pieces.indices) {
            placeBox(boxes[i], turning[i], 0.03f)
            draw(body)
        }

        glUniform1f(uMirror, 1f)
        glUniform1f(uGloss, 1f)
        val col = MirrorColor
        glUniform3f(uColor, col[0], col[1], col[2])
        for (i in pieces.indices) {
            val b = boxes[i]
            for (slot in geometry.cubies[i]) {
                val f = geometry.slots[slot].face
                val ax = faceAxes[f.ordinal]
                val w = b[2 * ax[0] + 1] - b[2 * ax[0]]
                val h = b[2 * ax[1] + 1] - b[2 * ax[1]]
                val mesh = plate(w, h)
                mesh.bind(aPos, aNormal)
                val normal = intArrayOf(f.nx, f.ny, f.nz)
                val c = FloatArray(3) { a ->
                    if (normal[a] > 0) b[2 * a + 1] else if (normal[a] < 0) b[2 * a] else (b[2 * a] + b[2 * a + 1]) / 2
                }
                if (turning[i]) Matrix.multiplyMM(base, 0, frame.model, 0, layerRot, 0) else System.arraycopy(frame.model, 0, base, 0, 16)
                Matrix.translateM(tmp, 0, base, 0, c[0], c[1], c[2])
                Matrix.multiplyMM(m, 0, tmp, 0, faceRot[f.ordinal], 0)
                Matrix.translateM(m, 0, 0f, 0f, 0.004f)
                draw(mesh)
            }
        }
    }

    private fun plate(w: Float, h: Float): Mesh {
        val key = (w * 100).roundToLong() * 100_000 + (h * 100).roundToLong()
        return plates.getOrPut(key) {
            val margin = 0.12f
            Mesh.roundedRect(w / 2 - margin, h / 2 - margin, radius = 0.2f)
        }
    }

    /**
     * Draws a minx: one dark dodecahedron and a polygon per sticker. While a layer turns the solid is
     * drawn twice, clipped to the turning slab and to everything else; faces are drawn from both
     * sides so the cut shows dark inside.
     */
    private fun drawMinx(minx: Minx) {
        val g = minx.geometry
        val meshes = minxMeshes.getOrPut(g.n) { MinxMeshes.of(g) }
        val moving = BooleanArray(g.stickers.size) { frame.turning && g.layer(it, frame.turnAxis) == frame.turnLayer }
        System.arraycopy(frame.model, 0, base, 0, 16)
        val turned = FloatArray(16).also { Matrix.multiplyMM(it, 0, frame.model, 0, layerRot, 0) }

        glUniform1f(uMirror, 0f)
        glUniform3f(uColor, 0.07f, 0.07f, 0.08f)
        glUniform1f(uGloss, 0.5f)
        meshes.body.bind(aPos, aNormal)
        glDisable(GL_CULL_FACE)
        if (frame.turning && !g.kites) {
            val (lo, hi) = slabs.getOrPut(g.n * 10_000L + frame.turnAxis * 100L + frame.turnLayer) { g.slab(frame.turnAxis, frame.turnLayer) }
            glUniform3f(uClipN, frame.turnVec[0], frame.turnVec[1], frame.turnVec[2])
            glUniform2f(uClip, lo.toFloat(), hi.toFloat())
            glUniform1f(uClipMode, -1f)
            System.arraycopy(base, 0, m, 0, 16)
            draw(meshes.body)
            glUniform1f(uClipMode, 1f)
            System.arraycopy(turned, 0, m, 0, 16)
            draw(meshes.body)
            glUniform1f(uClipMode, 0f)
        } else if (g.kites) {
            // A backdrop only: turning kites bent over an edge dip just inside the solid, and it
            // mustn't cover them. Stickers facing away are culled, so nothing needs its depth.
            glEnable(GL_CULL_FACE)
            glDepthMask(false)
            System.arraycopy(base, 0, m, 0, 16)
            draw(meshes.body)
            glDepthMask(true)
        } else {
            System.arraycopy(base, 0, m, 0, 16)
            draw(meshes.body)
        }
        glEnable(GL_CULL_FACE)

        glUniform1f(uGloss, 1f)
        meshes.stickers.bind(aPos, aNormal)
        // Kites can't follow the turning layer with a rigid split, so they turn on the CPU below.
        for (pass in 0..(if (g.kites) 0 else 1)) {
            System.arraycopy(if (pass == 0) base else turned, 0, m, 0, 16)
            setMatrices()
            for (i in g.stickers.indices) {
                if (moving[i] != (pass == 1)) continue
                val col = MinxColors[minx.color(i)]
                glUniform3f(uColor, col[0], col[1], col[2])
                glDrawArrays(GL_TRIANGLE_FAN, meshes.first[i], meshes.count[i])
            }
        }
        if (g.kites && frame.turning) drawSlidingKites(minx, meshes, moving)
    }

    /**
     * Turns the moving kites about the turn axis, then pushes any corner that would sink into the
     * solid back out onto its surface, so they slide over it (and over their neighbours) instead of
     * vanishing inside.
     */
    private fun drawSlidingKites(minx: Minx, meshes: MinxMeshes, moving: BooleanArray) {
        val g = minx.geometry
        val axis = frame.turnVec
        val angle = Math.toRadians(frame.turnDegrees.toDouble())
        val c = cos(angle).toFloat()
        val sn = sin(angle).toFloat()
        val buf = meshes.scratch
        buf.clear()
        val src = meshes.data
        val normals = g.normals
        for (i in g.stickers.indices) {
            if (!moving[i]) continue
            for (v in meshes.first[i] until meshes.first[i] + meshes.count[i]) {
                for (part in 0..1) {
                    val o = v * 6 + part * 3
                    val x = src[o]; val y = src[o + 1]; val z = src[o + 2]
                    // Rodrigues.
                    val kx = axis[1] * z - axis[2] * y
                    val ky = axis[2] * x - axis[0] * z
                    val kz = axis[0] * y - axis[1] * x
                    val d = (axis[0] * x + axis[1] * y + axis[2] * z) * (1 - c)
                    var rx = x * c + kx * sn + axis[0] * d
                    var ry = y * c + ky * sn + axis[1] * d
                    var rz = z * c + kz * sn + axis[2] * d
                    if (part == 0) {
                        var support = 0f
                        for (nrm in normals) {
                            val t = (rx * nrm[0] + ry * nrm[1] + rz * nrm[2]).toFloat()
                            if (t > support) support = t
                        }
                        // On the surface the support is 1 (plus the sticker's lift); keep it at least that, a hair higher.
                        val k = maxOf(1f, 1.003f / support) * 1.004f
                        rx *= k; ry *= k; rz *= k
                    }
                    buf.put(rx); buf.put(ry); buf.put(rz)
                }
            }
        }
        buf.position(0)
        glVertexAttribPointer(aPos, 3, GL_FLOAT, false, 24, buf)
        buf.position(3)
        glVertexAttribPointer(aNormal, 3, GL_FLOAT, false, 24, buf)
        System.arraycopy(base, 0, m, 0, 16)
        setMatrices()
        var first = 0
        for (i in g.stickers.indices) {
            if (!moving[i]) continue
            val col = MinxColors[minx.color(i)]
            glUniform3f(uColor, col[0], col[1], col[2])
            glDrawArrays(GL_TRIANGLE_FAN, first, meshes.count[i])
            first += meshes.count[i]
        }
    }

    /** Sets [m] to draw the unit [body] mesh as box [b], shrunk by [gap] on every side. */
    private fun placeBox(b: FloatArray, inTurn: Boolean, gap: Float) {
        if (inTurn) Matrix.multiplyMM(base, 0, frame.model, 0, layerRot, 0) else System.arraycopy(frame.model, 0, base, 0, 16)
        Matrix.translateM(m, 0, base, 0, (b[0] + b[1]) / 2, (b[2] + b[3]) / 2, (b[4] + b[5]) / 2)
        Matrix.scaleM(m, 0, (b[1] - b[0]) / 2 - gap, (b[3] - b[2]) / 2 - gap, (b[5] - b[4]) / 2 - gap)
    }

    /** Sets [m] to the model matrix of the cubie at doubled coords [p]. */
    private fun placeCubie(p: IntArray, inTurn: Boolean) {
        if (inTurn) Matrix.multiplyMM(base, 0, frame.model, 0, layerRot, 0) else System.arraycopy(frame.model, 0, base, 0, 16)
        Matrix.translateM(m, 0, base, 0, p[0].toFloat(), p[1].toFloat(), p[2].toFloat())
    }

    private fun draw(mesh: Mesh) {
        setMatrices()
        glDrawArrays(mesh.mode, 0, mesh.count)
    }

    private fun setMatrices() {
        Matrix.multiplyMM(mv, 0, frame.view, 0, m, 0)
        Matrix.multiplyMM(mvp, 0, frame.proj, 0, mv, 0)
        glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        glUniformMatrix4fv(uModelView, 1, false, mv, 0)
    }

    /** The dodecahedron and every sticker, inset and lifted off its face, in one buffer. */
    private class MinxMeshes(
        val body: Mesh,
        val stickers: Mesh,
        val first: IntArray,
        val count: IntArray,
        /** The sticker vertices (position, normal) for turning kites on the CPU. */
        val data: FloatArray,
    ) {
        /** Room for every sticker's turned vertices. */
        val scratch: FloatBuffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

        companion object {
            fun of(g: MinxGeometry): MinxMeshes {
                val bodyData = ArrayList<Float>()
                for (f in g.faces.indices) {
                    val poly = g.faces[f]
                    val n = g.normals[f]
                    for (k in 1 until poly.size - 1) for (v in listOf(poly[0], poly[k], poly[k + 1])) {
                        bodyData += listOf(v[0].toFloat(), v[1].toFloat(), v[2].toFloat(), n[0].toFloat(), n[1].toFloat(), n[2].toFloat())
                    }
                }
                val data = ArrayList<Float>()
                val first = IntArray(g.stickers.size)
                val count = IntArray(g.stickers.size)
                val margin = 0.055 / g.n
                for ((i, st) in g.stickers.withIndex()) {
                    val n = g.normals[st.face]
                    val poly = inset(st.polygon, n, st.centroid, margin)
                    // Wind counterclockwise seen from outside so culling keeps it.
                    val e1 = MinxGeometry.sub(poly[1], poly[0])
                    val e2 = MinxGeometry.sub(poly[2], poly[0])
                    val ordered = if (MinxGeometry.dot(MinxGeometry.cross(e1, e2), n) < 0) poly.reversed() else poly
                    first[i] = data.size / 6
                    count[i] = ordered.size
                    for (v in ordered) {
                        for (a in 0..2) data += (v[a] + n[a] * 0.003).toFloat()
                        for (a in 0..2) data += n[a].toFloat()
                    }
                }
                val array = data.toFloatArray()
                return MinxMeshes(Mesh.of(bodyData.toFloatArray(), GL_TRIANGLES), Mesh.of(array, GL_TRIANGLE_FAN), first, count, array)
            }

            /** Moves every edge of a convex polygon [m] inward, leaving a gap between stickers. */
            private fun inset(poly: List<DoubleArray>, n: DoubleArray, c: DoubleArray, m: Double): List<DoubleArray> {
                val pts = poly.filterIndexed { k, p ->
                    MinxGeometry.sub(p, poly[(k + 1) % poly.size]).let { MinxGeometry.dot(it, it) } > 1e-14
                }
                val k = pts.size
                val inward = List(k) { e ->
                    val a = pts[e]
                    val dir = MinxGeometry.normalize(MinxGeometry.sub(pts[(e + 1) % k], a))
                    val nrm = MinxGeometry.cross(n, dir)
                    if (MinxGeometry.dot(nrm, MinxGeometry.sub(c, a)) < 0) MinxGeometry.scale(nrm, -1.0) else nrm
                }
                return List(k) { v ->
                    val n1 = inward[(v + k - 1) % k]
                    val n2 = inward[v]
                    val s = m / (1 + MinxGeometry.dot(n1, n2))
                    DoubleArray(3) { pts[v][it] + (n1[it] + n2[it]) * s }
                }
            }
        }
    }

    /** Turns the +z sticker mesh to face [f]. */
    private fun faceRotation(f: Face, out: FloatArray) {
        Matrix.setIdentityM(out, 0)
        when (f) {
            Face.FRONT -> {}
            Face.BACK -> Matrix.rotateM(out, 0, 180f, 0f, 1f, 0f)
            Face.RIGHT -> Matrix.rotateM(out, 0, 90f, 0f, 1f, 0f)
            Face.LEFT -> Matrix.rotateM(out, 0, -90f, 0f, 1f, 0f)
            Face.UP -> Matrix.rotateM(out, 0, -90f, 1f, 0f, 0f)
            Face.DOWN -> Matrix.rotateM(out, 0, 90f, 1f, 0f, 0f)
        }
    }

    private class Mesh(val buffer: FloatBuffer, val count: Int, val mode: Int) {
        fun bind(aPos: Int, aNormal: Int) {
            buffer.position(0)
            glVertexAttribPointer(aPos, 3, GL_FLOAT, false, 24, buffer)
            glEnableVertexAttribArray(aPos)
            buffer.position(3)
            glVertexAttribPointer(aNormal, 3, GL_FLOAT, false, 24, buffer)
            glEnableVertexAttribArray(aNormal)
        }

        companion object {
            fun of(data: FloatArray, mode: Int): Mesh {
                val fb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
                fb.put(data).position(0)
                return Mesh(fb, data.size / 6, mode)
            }

            /** A [-1, 1] cube, 36 vertices with flat normals. */
            fun box(): Mesh {
                val out = ArrayList<Float>()
                for (f in Face.entries) {
                    val r = FloatArray(16)
                    Matrix.setIdentityM(r, 0)
                    when (f) {
                        Face.FRONT -> {}
                        Face.BACK -> Matrix.rotateM(r, 0, 180f, 0f, 1f, 0f)
                        Face.RIGHT -> Matrix.rotateM(r, 0, 90f, 0f, 1f, 0f)
                        Face.LEFT -> Matrix.rotateM(r, 0, -90f, 0f, 1f, 0f)
                        Face.UP -> Matrix.rotateM(r, 0, -90f, 1f, 0f, 0f)
                        Face.DOWN -> Matrix.rotateM(r, 0, 90f, 1f, 0f, 0f)
                    }
                    val quad = listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to -1f, 1f to 1f, -1f to 1f)
                    for ((qx, qy) in quad) {
                        val v = FloatArray(4)
                        Matrix.multiplyMV(v, 0, r, 0, floatArrayOf(qx, qy, 1f, 1f), 0)
                        out += listOf(v[0], v[1], v[2], f.nx.toFloat(), f.ny.toFloat(), f.nz.toFloat())
                    }
                }
                return of(out.toFloatArray(), GL_TRIANGLES)
            }

            fun roundedSquare(half: Float, radius: Float) = roundedRect(half, half, radius)

            /** A rounded rectangle in the z = 0 plane facing +z, as a triangle fan. */
            fun roundedRect(halfW: Float, halfH: Float, radius: Float, segments: Int = 6): Mesh {
                val out = arrayListOf(0f, 0f, 0f, 0f, 0f, 1f)
                val r = minOf(radius, halfW, halfH)
                val iw = halfW - r
                val ih = halfH - r
                val corners = listOf(iw to ih, -iw to ih, -iw to -ih, iw to -ih)
                corners.forEachIndexed { ci, (cx, cy) ->
                    for (k in 0..segments) {
                        val a = (ci * PI / 2 + k * (PI / 2) / segments).toFloat()
                        out += listOf(cx + r * cos(a), cy + r * sin(a), 0f, 0f, 0f, 1f)
                    }
                }
                out += out.subList(6, 12).toList() // close the fan
                return of(out.toFloatArray(), GL_TRIANGLE_FAN)
            }
        }
    }

    private fun buildProgram(vs: String, fs: String): Int {
        fun shader(type: Int, src: String) = glCreateShader(type).also {
            glShaderSource(it, src)
            glCompileShader(it)
            val ok = IntArray(1)
            glGetShaderiv(it, GL_COMPILE_STATUS, ok, 0)
            check(ok[0] != 0) { glGetShaderInfoLog(it) }
        }
        return glCreateProgram().also {
            glAttachShader(it, shader(GL_VERTEX_SHADER, vs))
            glAttachShader(it, shader(GL_FRAGMENT_SHADER, fs))
            glLinkProgram(it)
            val ok = IntArray(1)
            glGetProgramiv(it, GL_LINK_STATUS, ok, 0)
            check(ok[0] != 0) { glGetProgramInfoLog(it) }
        }
    }

    private companion object {
        const val VERTEX = """
            uniform mat4 uMvp;
            uniform mat4 uModelView;
            attribute vec3 aPos;
            attribute vec3 aNormal;
            varying vec3 vNormal;
            varying vec3 vPos;
            varying vec3 vLocal;
            void main() {
                vLocal = aPos;
                vNormal = mat3(uModelView) * aNormal;
                vPos = (uModelView * vec4(aPos, 1.0)).xyz;
                gl_Position = uMvp * vec4(aPos, 1.0);
            }
        """

        const val FRAGMENT = """
            precision mediump float;
            uniform vec3 uColor;
            uniform float uGloss;
            uniform float uMirror;
            uniform vec3 uClipN;
            uniform vec2 uClip;
            uniform float uClipMode;
            varying vec3 vNormal;
            varying vec3 vPos;
            varying vec3 vLocal;
            void main() {
                // Keep only the turning slab (mode 1) or only the rest (mode -1). The slab is
                // symmetric about the turn axis, so the unturned position tells.
                if (uClipMode != 0.0) {
                    float d = dot(vLocal, uClipN);
                    bool inside = d > uClip.x && d < uClip.y;
                    if (inside != (uClipMode > 0.0)) discard;
                }
                if (!gl_FrontFacing) {
                    gl_FragColor = vec4(uColor * 0.5, 1.0);
                    return;
                }
                vec3 n = normalize(vNormal);
                vec3 l = normalize(vec3(-0.45, 0.7, 0.8));
                vec3 v = normalize(-vPos);
                float diff = max(dot(n, l), 0.0);
                float fill = max(dot(n, normalize(vec3(0.6, -0.2, 0.7))), 0.0);
                float spec = pow(max(dot(n, normalize(l + v)), 0.0), 48.0);
                vec3 c = uColor * (0.42 + 0.5 * diff + 0.18 * fill) + vec3(spec * 0.35 * uGloss);
                // Chrome: reflect a bright sky over a dark floor, with a hot band at the horizon.
                vec3 r = reflect(-v, n);
                float sky = smoothstep(-0.7, 0.7, r.y);
                vec3 env = mix(vec3(0.4, 0.42, 0.47), vec3(1.05), sky) + vec3(0.3 * exp(-r.y * r.y * 30.0));
                env *= 0.86 + 0.14 * r.x;
                vec3 chrome = env * uColor + vec3(spec * 0.8);
                gl_FragColor = vec4(mix(c, chrome, uMirror * 0.85), 1.0);
            }
        """
    }
}
