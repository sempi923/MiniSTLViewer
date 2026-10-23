package com.ministl.viewer.model

import java.nio.FloatBuffer

data class BoundingBox(
    val minX: Float, val maxX: Float,
    val minY: Float, val maxY: Float,
    val minZ: Float, val maxZ: Float
) {
    val widthX: Float get() = maxX - minX
    val heightY: Float get() = maxY - minY
    val depthZ: Float get() = maxZ - minZ
    val centerX: Float get() = (minX + maxX) * 0.5f
    val centerY: Float get() = (minY + maxY) * 0.5f
    val centerZ: Float get() = (minZ + maxZ) * 0.5f

    val maxDimension: Float get() = maxOf(widthX, maxOf(heightY, depthZ)).coerceAtLeast(0.0001f)
}

data class StlMesh(
    val vertexBuffer: FloatBuffer,
    val normalBuffer: FloatBuffer,
    val triangleCount: Int,
    val boundingBox: BoundingBox,
    val isBinary: Boolean
)
