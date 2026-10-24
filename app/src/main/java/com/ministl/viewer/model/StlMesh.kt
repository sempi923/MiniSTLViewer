package com.ministl.viewer.model

import java.nio.FloatBuffer
import kotlin.math.pow

data class BoundingBox(
    val minX: Float, val maxX: Float,
    val minY: Float, val maxY: Float,
    val minZ: Float, val maxZ: Float
) {
    val widthX: Float get() = (maxX - minX).coerceAtLeast(0f)
    val depthY: Float get() = (maxY - minY).coerceAtLeast(0f)
    val heightZ: Float get() = (maxZ - minZ).coerceAtLeast(0f)

    // Compatibilidad con nomenclatura previa
    val heightY: Float get() = depthY
    val depthZ: Float get() = heightZ

    val centerX: Float get() = (minX + maxX) * 0.5f
    val centerY: Float get() = (minY + maxY) * 0.5f
    val centerZ: Float get() = (minZ + maxZ) * 0.5f

    val maxDimension: Float get() = maxOf(widthX, maxOf(depthY, heightZ)).coerceAtLeast(0.0001f)
}

enum class FilamentMaterial(
    val displayName: String,
    val shortTag: String,
    val densityGPerCm3: Float,
    val ambient: Float,
    val diffuse: Float,
    val specular: Float,
    val shininess: Float,
    val roughness: Float,
    val isCarbonFiber: Boolean = false,
    val defaultPricePerKg: Float = 20.0f
) {
    PLA(
        displayName = "Bambu PLA Basic",
        shortTag = "PLA",
        densityGPerCm3 = 1.24f,
        ambient = 0.25f,
        diffuse = 0.72f,
        specular = 0.38f,
        shininess = 36f,
        roughness = 0.25f,
        defaultPricePerKg = 19.99f
    ),
    PETG(
        displayName = "Bambu PETG HF",
        shortTag = "PETG",
        densityGPerCm3 = 1.27f,
        ambient = 0.23f,
        diffuse = 0.68f,
        specular = 0.55f,
        shininess = 52f,
        roughness = 0.16f,
        defaultPricePerKg = 21.99f
    ),
    ABS(
        displayName = "Bambu ABS",
        shortTag = "ABS",
        densityGPerCm3 = 1.04f,
        ambient = 0.27f,
        diffuse = 0.74f,
        specular = 0.28f,
        shininess = 24f,
        roughness = 0.32f,
        defaultPricePerKg = 22.99f
    ),
    MATTE(
        displayName = "Bambu PLA Matte",
        shortTag = "Matte",
        densityGPerCm3 = 1.24f,
        ambient = 0.32f,
        diffuse = 0.86f,
        specular = 0.04f,
        shininess = 6f,
        roughness = 0.88f,
        defaultPricePerKg = 20.99f
    ),
    SILK(
        displayName = "Bambu PLA Silk",
        shortTag = "Silk",
        densityGPerCm3 = 1.24f,
        ambient = 0.20f,
        diffuse = 0.52f,
        specular = 0.95f,
        shininess = 96f,
        roughness = 0.08f,
        defaultPricePerKg = 24.99f
    ),
    CF(
        displayName = "Bambu PAHT-CF",
        shortTag = "PA-CF",
        densityGPerCm3 = 1.18f,
        ambient = 0.22f,
        diffuse = 0.64f,
        specular = 0.20f,
        shininess = 16f,
        roughness = 0.72f,
        isCarbonFiber = true,
        defaultPricePerKg = 44.99f
    )
}

enum class MeshRenderMode(val label: String) {
    SOLID("Sólido"),
    WIREFRAME("Alambre"),
    SOLID_WIREFRAME("Combinado")
}

enum class ClippingAxis(val label: String) {
    Z("Eje Z (Capas)"),
    X("Eje X (Ancho)"),
    Y("Eje Y (Prof.)")
}

enum class CameraPreset(val label: String, val pitchX: Float, val yawZ: Float) {
    ISO("ISO", -58f, -38f),
    TOP("TOP", 0f, 0f),
    FRONT("FRONT", -90f, 0f),
    RIGHT("RIGHT", -90f, -90f),
    LEFT("LEFT", -90f, 90f),
    BACK("BACK", -90f, 180f)
}

data class ModelTransform(
    val scaleFactor: Float = 1.0f,
    val rotXDeg: Float = 0f,
    val rotYDeg: Float = 0f,
    val rotZDeg: Float = 0f,
    val autoLayOnBed: Boolean = true
)

data class MeshAnalysis(
    val volumeCm3: Float,
    val surfaceAreaCm2: Float,
    val overhangAreaCm2: Float,
    val overhangPercentage: Float
) {
    fun estimateVolumeScaledCm3(scaleFactor: Float): Float {
        return volumeCm3 * scaleFactor.pow(3)
    }

    fun estimateSurfaceScaledCm2(scaleFactor: Float): Float {
        return surfaceAreaCm2 * scaleFactor.pow(2)
    }

    fun estimateWeightGrams(material: FilamentMaterial, scaleFactor: Float = 1.0f): Float {
        return estimateVolumeScaledCm3(scaleFactor) * material.densityGPerCm3
    }

    fun estimateCost(weightGrams: Float, pricePerKg: Float): Float {
        return (weightGrams / 1000f) * pricePerKg
    }
}

data class StlMesh(
    val vertexBuffer: FloatBuffer,
    val normalBuffer: FloatBuffer,
    val triangleCount: Int,
    val boundingBox: BoundingBox,
    val isBinary: Boolean,
    val fileName: String = "Modelo.stl",
    val formatBadge: String = if (isBinary) "BIN" else "ASCII"
)
