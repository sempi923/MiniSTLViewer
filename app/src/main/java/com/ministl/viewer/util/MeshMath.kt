package com.ministl.viewer.util

import android.opengl.Matrix
import com.ministl.viewer.model.BoundingBox
import com.ministl.viewer.model.FilamentMaterial
import com.ministl.viewer.model.MeshAnalysis
import com.ministl.viewer.model.ModelTransform
import com.ministl.viewer.model.StlMesh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Extensiones matemáticas de geometría computacional para manufactura aditiva (impresión 3D).
 * Todos los cálculos pesados se ejecutan en Dispatchers.Default sin bloquear el hilo UI ni GL.
 */
object MeshMath {

    /**
     * Construye la matriz de rotación 4x4 (columna-mayor de OpenGL) para los pasos de rotación del modelo.
     */
    fun buildRotationMatrix(rotXDeg: Float, rotYDeg: Float, rotZDeg: Float): FloatArray {
        val mat = FloatArray(16)
        Matrix.setIdentityM(mat, 0)
        if (rotZDeg != 0f) Matrix.rotateM(mat, 0, rotZDeg, 0f, 0f, 1f)
        if (rotYDeg != 0f) Matrix.rotateM(mat, 0, rotYDeg, 0f, 1f, 0f)
        if (rotXDeg != 0f) Matrix.rotateM(mat, 0, rotXDeg, 1f, 0f, 0f)
        return mat
    }

    /**
     * Calcula el BoundingBox transformado tras aplicar rotación y escala uniforme.
     * Dado que la malla está centrada en (0,0,0), transforma las 8 esquinas del BoundingBox local.
     */
    fun computeTransformedBounds( localBbox: BoundingBox, transform: ModelTransform ): BoundingBox {
        val rot = buildRotationMatrix(transform.rotXDeg, transform.rotYDeg, transform.rotZDeg)
        val s = transform.scaleFactor

        val hx = localBbox.widthX * 0.5f * s
        val hy = localBbox.depthY * 0.5f * s
        val hz = localBbox.heightZ * 0.5f * s

        var minX = Float.POSITIVE_INFINITY; var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY; var maxZ = Float.NEGATIVE_INFINITY

        val signs = floatArrayOf(-1f, 1f)
        for (sx in signs) {
            for (sy in signs) {
                for (sz in signs) {
                    val x = sx * hx
                    val y = sy * hy
                    val z = sz * hz

                    val rx = rot[0] * x + rot[4] * y + rot[8] * z
                    val ry = rot[1] * x + rot[5] * y + rot[9] * z
                    val rz = rot[2] * x + rot[6] * y + rot[10] * z

                    minX = min(minX, rx); maxX = max(maxX, rx)
                    minY = min(minY, ry); maxY = max(maxY, ry)
                    minZ = min(minZ, rz); maxZ = max(maxZ, rz)
                }
            }
        }

        // Si auto-colocar sobre la cama está activo, desplazamos Z para que minZ = 0
        val zShift = if (transform.autoLayOnBed) -minZ else 0f
        return BoundingBox(
            minX = minX, maxX = maxX,
            minY = minY, maxY = maxY,
            minZ = minZ + zShift, maxZ = maxZ + zShift
        )
    }

    /**
     * Analiza las propiedades físicas del modelo STL:
     * 1. Volumen exacto en cm³ por integración de tetraedros con signo: V_i = (v1 · (v2 × v3)) / 6
     * 2. Área superficial total en cm²: A_i = 0.5 * ||(v2 - v1) × (v3 - v1)||
     * 3. Área y porcentaje de voladizos (overhangs) para caras cuya normal apunta hacia la cama
     *    con un ángulo mayor al umbral respecto a la vertical (> 45°).
     */
    suspend fun analyzeMesh(
        mesh: StlMesh,
        transform: ModelTransform = ModelTransform(),
        overhangAngleDeg: Float = 45f
    ): MeshAnalysis = withContext(Dispatchers.Default) {
        val vBuf = mesh.vertexBuffer.duplicate().apply { position(0) }
        val triCount = mesh.triangleCount
        val rot = buildRotationMatrix(transform.rotXDeg, transform.rotYDeg, transform.rotZDeg)

        // Límite coseno respecto al vector hacia abajo (0, 0, -1)
        // Una cara con inclinación > 45° desde la vertical tiene normal.z < -cos(45°) = -0.7071
        val overhangCosLimit = cos(Math.toRadians(overhangAngleDeg.toDouble())).toFloat()

        // Calcular minZ rotado sin escala para ignorar la primera capa apoyada en la cama
        val unscaledBounds = computeTransformedBounds(
            mesh.boundingBox,
            transform.copy(scaleFactor = 1f, autoLayOnBed = false)
        )
        val bedFloorThreshold = unscaledBounds.minZ + 0.25f

        var signedVolumeMm3 = 0.0
        var totalAreaMm2 = 0.0
        var overhangAreaMm2 = 0.0

        for (i in 0 until triCount) {
            if (i % 8192 == 0) coroutineContext.ensureActive()

            val base = i * 9
            val v1x = vBuf.get(base);     val v1y = vBuf.get(base + 1); val v1z = vBuf.get(base + 2)
            val v2x = vBuf.get(base + 3); val v2y = vBuf.get(base + 4); val v2z = vBuf.get(base + 5)
            val v3x = vBuf.get(base + 6); val v3y = vBuf.get(base + 7); val v3z = vBuf.get(base + 8)

            // 1. Volumen del tetraedro con signo respecto al origen: (v1 · (v2 × v3)) / 6
            val crossX = v2y * v3z - v2z * v3y
            val crossY = v2z * v3x - v2x * v3z
            val crossZ = v2x * v3y - v2y * v3x
            signedVolumeMm3 += (v1x * crossX + v1y * crossY + v1z * crossZ) / 6.0

            // 2. Área del triángulo: 0.5 * ||(v2 - v1) × (v3 - v1)||
            val ux = v2x - v1x; val uy = v2y - v1y; val uz = v2z - v1z
            val vx = v3x - v1x; val vy = v3y - v1y; val vz = v3z - v1z
            val nx = uy * vz - uz * vy
            val ny = uz * vx - ux * vz
            val nz = ux * vy - uy * vx
            val doubleArea = sqrt((nx * nx + ny * ny + nz * nz).toDouble())
            val triAreaMm2 = doubleArea * 0.5
            totalAreaMm2 += triAreaMm2

            // 3. Evaluación de voladizo en espacio mundo (tras rotación del modelo)
            if (doubleArea > 1e-7) {
                val invLen = (1.0 / doubleArea).toFloat()
                val unitNx = nx * invLen
                val unitNy = ny * invLen
                val unitNz = nz * invLen

                // Componente Z de la normal rotada: fila Z de la matriz de rotación
                val worldNz = rot[2] * unitNx + rot[6] * unitNy + rot[10] * unitNz

                // Altura Z promedio del triángulo rotado
                val avgX = (v1x + v2x + v3x) / 3f
                val avgY = (v1y + v2y + v3y) / 3f
                val avgZ = (v1z + v2z + v3z) / 3f
                val worldZ = rot[2] * avgX + rot[6] * avgY + rot[10] * avgZ

                if (worldNz < -overhangCosLimit && worldZ > bedFloorThreshold) {
                    overhangAreaMm2 += triAreaMm2
                }
            }
        }

        // Conversión: 1 cm³ = 1000 mm³, 1 cm² = 100 mm²
        val volumeCm3 = (abs(signedVolumeMm3) / 1000.0).toFloat()
        val surfaceAreaCm2 = (totalAreaMm2 / 100.0).toFloat()
        val overhangAreaCm2 = (overhangAreaMm2 / 100.0).toFloat()
        val overhangPct = if (totalAreaMm2 > 1e-5) {
            ((overhangAreaMm2 / totalAreaMm2) * 100.0).toFloat().coerceIn(0f, 100f)
        } else 0f

        MeshAnalysis(
            volumeCm3 = volumeCm3,
            surfaceAreaCm2 = surfaceAreaCm2,
            overhangAreaCm2 = overhangAreaCm2,
            overhangPercentage = overhangPct
        )
    }

    /**
     * Genera un modelo 3D de calibración técnico (Puente de Prueba de Voladizos + Torre)
     * para permitir probar inmediatamente todos los shaders, cortes Z y cálculos métricos.
     */
    fun generateCalibrationArchMesh(): StlMesh {
        val triangles = ArrayList<FloatArray>()

        fun addTri(
            x1: Float, y1: Float, z1: Float,
            x2: Float, y2: Float, z2: Float,
            x3: Float, y3: Float, z3: Float
        ) {
            val ux = x2 - x1; val uy = y2 - y1; val uz = z2 - z1
            val vx = x3 - x1; val vy = y3 - y1; val vz = z3 - z1
            var nx = uy * vz - uz * vy
            var ny = uz * vx - ux * vz
            var nz = ux * vy - uy * vx
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            if (len > 1e-6f) { nx /= len; ny /= len; nz /= len }
            triangles.add(
                floatArrayOf(
                    x1, y1, z1, x2, y2, z2, x3, y3, z3,
                    nx, ny, nz
                )
            )
        }

        fun addQuad(
            x1: Float, y1: Float, z1: Float,
            x2: Float, y2: Float, z2: Float,
            x3: Float, y3: Float, z3: Float,
            x4: Float, y4: Float, z4: Float
        ) {
            addTri(x1, y1, z1, x2, y2, z2, x3, y3, z3)
            addTri(x1, y1, z1, x3, y3, z3, x4, y4, z4)
        }

        fun addBox(minX: Float, maxX: Float, minY: Float, maxY: Float, minZ: Float, maxZ: Float) {
            // Front (Y = minY)
            addQuad(minX, minY, minZ, maxX, minY, minZ, maxX, minY, maxZ, minX, minY, maxZ)
            // Back (Y = maxY)
            addQuad(maxX, maxY, minZ, minX, maxY, minZ, minX, maxY, maxZ, maxX, maxY, maxZ)
            // Right (X = maxX)
            addQuad(maxX, minY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, maxX, minY, maxZ)
            // Left (X = minX)
            addQuad(minX, maxY, minZ, minX, minY, minZ, minX, minY, maxZ, minX, maxY, maxZ)
            // Top (Z = maxZ)
            addQuad(minX, minY, maxZ, maxX, minY, maxZ, maxX, maxY, maxZ, minX, maxY, maxZ)
            // Bottom (Z = minZ)
            addQuad(minX, maxY, minZ, maxX, maxY, minZ, maxX, minY, minZ, minX, minY, minZ)
        }

        // 1. Base de calibración (40 x 24 x 4 mm)
        addBox(-20f, 20f, -12f, 12f, -15f, -11f)

        // 2. Pilares laterales (izquierdo y derecho)
        addBox(-20f, -10f, -10f, 10f, -11f, 7f)
        addBox(10f, 20f, -10f, 10f, -11f, 7f)

        // 3. Rampa de voladizo progresiva (> 45° para activar el Overhang Inspector)
        // Izquierda hacia el centro
        addQuad(-10f, -10f, -2f, 0f, -10f, 7f, 0f, 10f, 7f, -10f, 10f, -2f) // Cara inferior en voladizo (~48°)
        addQuad(-10f, 10f, 7f, 0f, 10f, 7f, 0f, -10f, 7f, -10f, -10f, 7f)   // Tapa superior
        addTri(-10f, -10f, -2f, -10f, -10f, 7f, 0f, -10f, 7f)                 // Frente
        addTri(-10f, 10f, -2f, 0f, 10f, 7f, -10f, 10f, 7f)                    // Dorso

        // Derecha hacia el centro (voladizo pronunciado ~58°)
        addQuad(0f, -10f, 7f, 10f, -10f, 1f, 10f, 10f, 1f, 0f, 10f, 7f)      // Cara inferior en voladizo (> 55°)
        addQuad(0f, 10f, 7f, 10f, 10f, 7f, 10f, -10f, 7f, 0f, -10f, 7f)      // Tapa superior
        addTri(10f, -10f, 1f, 0f, -10f, 7f, 10f, -10f, 7f)                    // Frente
        addTri(10f, 10f, 1f, 10f, 10f, 7f, 0f, 10f, 7f)                       // Dorso

        // 4. Puente superior (Bridge test) y corona cilíndrica octogonal
        addBox(-20f, 20f, -10f, 10f, 7f, 11f)

        val segments = 16
        val radius = 7.5f
        val zBot = 11f
        val zTop = 15f
        for (i in 0 until segments) {
            val a1 = (2.0 * Math.PI * i / segments).toFloat()
            val a2 = (2.0 * Math.PI * (i + 1) / segments).toFloat()
            val x1 = cos(a1) * radius; val y1 = sin(a1) * radius
            val x2 = cos(a2) * radius; val y2 = sin(a2) * radius
            addQuad(x1, y1, zBot, x2, y2, zBot, x2, y2, zTop, x1, y1, zTop)
            addTri(0f, 0f, zTop, x1, y1, zTop, x2, y2, zTop)
        }

        val triCount = triangles.size
        val floatCount = triCount * 9
        val vBuffer = ByteBuffer.allocateDirect(floatCount * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val nBuffer = ByteBuffer.allocateDirect(floatCount * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

        var minX = Float.POSITIVE_INFINITY; var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY; var maxZ = Float.NEGATIVE_INFINITY

        for (t in triangles) {
            for (v in 0 until 3) {
                val vx = t[v * 3]
                val vy = t[v * 3 + 1]
                val vz = t[v * 3 + 2]
                minX = min(minX, vx); maxX = max(maxX, vx)
                minY = min(minY, vy); maxY = max(maxY, vy)
                minZ = min(minZ, vz); maxZ = max(maxZ, vz)
                vBuffer.put(vx).put(vy).put(vz)
                nBuffer.put(t[9]).put(t[10]).put(t[11])
            }
        }
        vBuffer.position(0)
        nBuffer.position(0)

        return StlMesh(
            vertexBuffer = vBuffer,
            normalBuffer = nBuffer,
            triangleCount = triCount,
            boundingBox = BoundingBox(minX, maxX, minY, maxY, minZ, maxZ),
            isBinary = true,
            fileName = "Rabbit3D_Overhang_Calibration.stl"
        )
    }
}

// Extensiones directas sobre StlMesh para uso fluido desde el ViewModel y la UI
suspend fun StlMesh.calculatePhysicalProperties(
    transform: ModelTransform = ModelTransform(),
    overhangAngleDeg: Float = 45f
): MeshAnalysis = MeshMath.analyzeMesh(this, transform, overhangAngleDeg)

fun StlMesh.estimateWeightGrams(
    volumeCm3: Float,
    material: FilamentMaterial,
    scaleFactor: Float = 1.0f
): Float = volumeCm3 * scaleFactor.pow(3) * material.densityGPerCm3

fun StlMesh.estimateFilamentCost(
    weightGrams: Float,
    pricePerKg: Float
): Float = (weightGrams / 1000f) * pricePerKg
