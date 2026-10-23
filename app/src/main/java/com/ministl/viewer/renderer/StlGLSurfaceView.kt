package com.ministl.viewer.renderer

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import android.view.ScaleGestureDetector

class StlGLSurfaceView(context: Context) : GLSurfaceView(context) {

    val renderer: StlRenderer
    private val scaleDetector: ScaleGestureDetector

    private var prevX = 0f
    private var prevY = 0f

    init {
        setEGLContextClientVersion(2)
        renderer = StlRenderer()
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY

        scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                if (factor > 0f) {
                    renderer.cameraDistance = (renderer.cameraDistance / factor).coerceIn(0.5f, 25000f)
                    requestRender()
                }
                return true
            }
        })
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        val pointerCount = event.pointerCount

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val (cx, cy) = getFocusPoint(event)
                prevX = cx
                prevY = cy
            }
            MotionEvent.ACTION_MOVE -> {
                val (cx, cy) = getFocusPoint(event)
                val dx = cx - prevX
                val dy = cy - prevY

                if (pointerCount == 1 && !scaleDetector.isInProgress) {
                    // 1 dedo: Rotación orbital
                    renderer.rotY += dx * 0.45f
                    renderer.rotX = (renderer.rotX + dy * 0.45f).coerceIn(-90f, 90f)
                    requestRender()
                } else if (pointerCount >= 2) {
                    // 2 dedos: Desplazamiento (Pan) proporcional a la distancia de la cámara
                    val panSpeed = renderer.cameraDistance * 0.0012f
                    renderer.panX += dx * panSpeed
                    renderer.panY -= dy * panSpeed
                    requestRender()
                }

                prevX = cx
                prevY = cy
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remainingIndex = if (event.actionIndex == 0) 1 else 0
                prevX = event.getX(remainingIndex)
                prevY = event.getY(remainingIndex)
            }
        }
        return true
    }

    private fun getFocusPoint(event: MotionEvent): Pair<Float, Float> {
        var sumX = 0f
        var sumY = 0f
        val count = event.pointerCount
        for (i in 0 until count) {
            sumX += event.getX(i)
            sumY += event.getY(i)
        }
        return Pair(sumX / count, sumY / count)
    }
}
