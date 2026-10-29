package com.classic.cube.gl

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.view.ViewConfiguration
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay
import kotlin.math.atan2

/** The GL surface the cube is drawn on. Renders only while something moves. */
@SuppressLint("ViewConstructor")
class CubeView(context: Context, val session: CubeSession) : GLSurfaceView(context) {
    val renderer = CubeRenderer(session)

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(MultisampleChooser())
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
        renderer.onAnimating = { requestRender() }
        session.requestRender = { requestRender() }
        session.touchSlop = ViewConfiguration.get(context).scaledTouchSlop * 1.2f
    }

    override fun onDetachedFromWindow() {
        session.requestRender = {}
        super.onDetachedFromWindow()
    }

    /** Angle of the line between the first two fingers, or NaN until it is measured again. */
    private var twistAngle = Float.NaN

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> session.down(e.x, e.y, e.eventTime)
            MotionEvent.ACTION_POINTER_DOWN -> {
                session.secondPointer(centroidX(e), centroidY(e))
                twistAngle = Float.NaN
            }
            MotionEvent.ACTION_MOVE ->
                if (e.pointerCount > 1) {
                    // Moving the fingers together orbits; twisting them spins the cube in the screen plane.
                    session.move(centroidX(e), centroidY(e), e.eventTime)
                    val a = Math.toDegrees(atan2((e.getY(1) - e.getY(0)).toDouble(), (e.getX(1) - e.getX(0)).toDouble())).toFloat()
                    if (!twistAngle.isNaN()) {
                        val d = ((a - twistAngle + 540f) % 360f) - 180f
                        // Screen y points down, so a clockwise twist is a negative turn about the view axis.
                        session.twist(-d)
                    }
                    twistAngle = a
                } else {
                    session.move(e.x, e.y, e.eventTime)
                }
            MotionEvent.ACTION_POINTER_UP -> {
                // Keep orbiting from wherever the remaining fingers are.
                val left = (0 until e.pointerCount).filter { it != e.actionIndex }
                session.secondPointer(left.map { e.getX(it) }.average().toFloat(), left.map { e.getY(it) }.average().toFloat())
                twistAngle = Float.NaN
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> session.up()
        }
        return true
    }

    private fun centroidX(e: MotionEvent) = (0 until e.pointerCount).map { e.getX(it) }.average().toFloat()
    private fun centroidY(e: MotionEvent) = (0 until e.pointerCount).map { e.getY(it) }.average().toFloat()

    /** 4x MSAA when the device has it, plain RGB565+depth otherwise. */
    private class MultisampleChooser : EGLConfigChooser {
        override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
            val attempts = listOf(
                intArrayOf(
                    EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                    EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, 4, EGL10.EGL_NONE,
                ),
                intArrayOf(
                    EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL10.EGL_NONE,
                ),
                intArrayOf(EGL10.EGL_DEPTH_SIZE, 16, EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL10.EGL_NONE),
            )
            for (spec in attempts) {
                val count = IntArray(1)
                val configs = arrayOfNulls<EGLConfig>(1)
                if (egl.eglChooseConfig(display, spec, configs, 1, count) && count[0] > 0) return configs[0]!!
            }
            error("No usable EGL config")
        }

        private companion object {
            const val EGL_OPENGL_ES2_BIT = 4
        }
    }
}
