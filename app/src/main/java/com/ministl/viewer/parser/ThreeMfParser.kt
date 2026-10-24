package com.ministl.viewer.parser

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.ministl.viewer.model.BoundingBox
import com.ministl.viewer.model.StlMesh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Parser nativo y ligero para archivos .3MF (3D Manufacturing Format).
 * Descomprime en memoria el contenedor ZIP mediante [ZipInputStream] y extrae
 * las mallas XML (`3D/3dmodel.model` y sub-modelos `.model`) con [XmlPullParser].
 */
object ThreeMfParser {

    private const val MAX_TRIANGLES = 2_000_000

    suspend fun parse(
        contentResolver: ContentResolver,
        uri: Uri,
        fileNameOverride: String? = null,
        onProgress: (Float) -> Unit = {}
    ): StlMesh = withContext(Dispatchers.IO) {
        val fileName = fileNameOverride ?: resolveFileName(contentResolver, uri)
        onProgress(0.05f)

        contentResolver.openInputStream(uri)?.use { rawStream ->
            parseFromStream(BufferedInputStream(rawStream, 64 * 1024), fileName, onProgress)
        } ?: throw IllegalArgumentException("No se pudo abrir el archivo .3MF.")
    }

    suspend fun parseFromStream(
        inputStream: InputStream,
        fileName: String,
        onProgress: (Float) -> Unit = {}
    ): StlMesh = withContext(Dispatchers.IO) {
        val zipStream = ZipInputStream(BufferedInputStream(inputStream, 64 * 1024))

        // Arreglos dinámicos para acumular los triángulos finales de todos los objetos <mesh>
        var outVertices = FloatArray(32_768 * 9)
        var outNormals = FloatArray(32_768 * 9)
        var totalFloats = 0

        var minX = Float.POSITIVE_INFINITY; var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY; var maxZ = Float.NEGATIVE_INFINITY

        var foundModelEntry = false
        var modelEntriesParsed = 0

        val factory = XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = false
        }

        while (true) {
            coroutineContext.ensureActive()
            val entry = zipStream.nextEntry ?: break
            val entryName = entry.name

            // Localizar el archivo principal (usualmente 3D/3dmodel.model) o sub-modelos .model
            val isModelEntry = !entry.isDirectory && (
                entryName.equals("3D/3dmodel.model", ignoreCase = true) ||
                entryName.endsWith(".model", ignoreCase = true)
            )

            if (isModelEntry) {
                foundModelEntry = true
                modelEntriesParsed++
                onProgress((0.10f + modelEntriesParsed * 0.15f).coerceAtMost(0.85f))

                val parser = factory.newPullParser()
                parser.setInput(zipStream, null)

                var unitScale = 1.0f

                // Vértices locales del <mesh> activo (x, y, z por cada vértice)
                var meshVertices = FloatArray(16_384 * 3)
                var meshVertexFloats = 0
                var inMesh = false
                var inVertices = false
                var inTriangles = false

                var eventType = parser.eventType
                var stepCounter = 0

                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (++stepCounter % 4096 == 0) {
                        coroutineContext.ensureActive()
                    }

                    when (eventType) {
                        XmlPullParser.START_TAG -> {
                            val tag = parser.name?.substringAfterLast(':') ?: ""
                            when {
                                tag.equals("model", ignoreCase = true) -> {
                                    val unit = getAttr(parser, "unit")
                                    unitScale = resolveUnitScale(unit)
                                }
                                tag.equals("mesh", ignoreCase = true) -> {
                                    inMesh = true
                                    meshVertexFloats = 0
                                }
                                inMesh && tag.equals("vertices", ignoreCase = true) -> {
                                    inVertices = true
                                }
                                inVertices && tag.equals("vertex", ignoreCase = true) -> {
                                    val vx = (getAttr(parser, "x")?.toFloatOrNull() ?: 0f) * unitScale
                                    val vy = (getAttr(parser, "y")?.toFloatOrNull() ?: 0f) * unitScale
                                    val vz = (getAttr(parser, "z")?.toFloatOrNull() ?: 0f) * unitScale

                                    if (meshVertexFloats + 3 > meshVertices.size) {
                                        meshVertices = meshVertices.copyOf(meshVertices.size * 2)
                                    }
                                    meshVertices[meshVertexFloats] = vx
                                    meshVertices[meshVertexFloats + 1] = vy
                                    meshVertices[meshVertexFloats + 2] = vz
                                    meshVertexFloats += 3
                                }
                                inMesh && tag.equals("triangles", ignoreCase = true) -> {
                                    inTriangles = true
                                }
                                inTriangles && tag.equals("triangle", ignoreCase = true) -> {
                                    val curTriCount = totalFloats / 9
                                    if (curTriCount < MAX_TRIANGLES) {
                                        val v1 = getAttr(parser, "v1")?.toIntOrNull() ?: -1
                                        val v2 = getAttr(parser, "v2")?.toIntOrNull() ?: -1
                                        val v3 = getAttr(parser, "v3")?.toIntOrNull() ?: -1

                                        val maxVertexIndex = meshVertexFloats / 3
                                        if (v1 in 0 until maxVertexIndex &&
                                            v2 in 0 until maxVertexIndex &&
                                            v3 in 0 until maxVertexIndex
                                        ) {
                                            val i1 = v1 * 3
                                            val i2 = v2 * 3
                                            val i3 = v3 * 3

                                            val v1x = meshVertices[i1]; val v1y = meshVertices[i1 + 1]; val v1z = meshVertices[i1 + 2]
                                            val v2x = meshVertices[i2]; val v2y = meshVertices[i2 + 1]; val v2z = meshVertices[i2 + 2]
                                            val v3x = meshVertices[i3]; val v3y = meshVertices[i3 + 1]; val v3z = meshVertices[i3 + 2]

                                            // Calcular normal por cara mediante producto cruzado (v2 - v1) x (v3 - v1)
                                            val ux = v2x - v1x; val uy = v2y - v1y; val uz = v2z - v1z
                                            val wx = v3x - v1x; val wy = v3y - v1y; val wz = v3z - v1z
                                            var nx = uy * wz - uz * wy
                                            var ny = uz * wx - ux * wz
                                            var nz = ux * wy - uy * wx
                                            val len = sqrt(nx * nx + ny * ny + nz * nz)
                                            if (len > 1e-6f) {
                                                nx /= len; ny /= len; nz /= len
                                            }

                                            if (totalFloats + 9 > outVertices.size) {
                                                val newCap = min(outVertices.size * 2, MAX_TRIANGLES * 9)
                                                outVertices = outVertices.copyOf(newCap)
                                                outNormals = outNormals.copyOf(newCap)
                                            }

                                            minX = minOf(minX, v1x, v2x, v3x); maxX = maxOf(maxX, v1x, v2x, v3x)
                                            minY = minOf(minY, v1y, v2y, v3y); maxY = maxOf(maxY, v1y, v2y, v3y)
                                            minZ = minOf(minZ, v1z, v2z, v3z); maxZ = maxOf(maxZ, v1z, v2z, v3z)

                                            outVertices[totalFloats] = v1x
                                            outVertices[totalFloats + 1] = v1y
                                            outVertices[totalFloats + 2] = v1z
                                            outVertices[totalFloats + 3] = v2x
                                            outVertices[totalFloats + 4] = v2y
                                            outVertices[totalFloats + 5] = v2z
                                            outVertices[totalFloats + 6] = v3x
                                            outVertices[totalFloats + 7] = v3y
                                            outVertices[totalFloats + 8] = v3z

                                            for (k in 0 until 9 step 3) {
                                                outNormals[totalFloats + k] = nx
                                                outNormals[totalFloats + k + 1] = ny
                                                outNormals[totalFloats + k + 2] = nz
                                            }

                                            totalFloats += 9
                                        }
                                    }
                                }
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            val tag = parser.name?.substringAfterLast(':') ?: ""
                            when {
                                tag.equals("vertices", ignoreCase = true) -> inVertices = false
                                tag.equals("triangles", ignoreCase = true) -> inTriangles = false
                                tag.equals("mesh", ignoreCase = true) -> {
                                    inMesh = false
                                    inVertices = false
                                    inTriangles = false
                                    meshVertexFloats = 0
                                }
                            }
                        }
                    }
                    eventType = parser.next()
                }
            }
            zipStream.closeEntry()
        }

        if (!foundModelEntry) {
            throw IllegalArgumentException("El contenedor .3MF no incluye un archivo de modelo 3D (3D/3dmodel.model).")
        }

        val triCount = totalFloats / 9
        if (triCount == 0) {
            throw IllegalArgumentException("El archivo .3MF no contiene mallas (<mesh>) con triángulos válidos.")
        }

        onProgress(0.92f)

        // Calcular BoundingBox y centrar la malla en el origen (0, 0, 0)
        val rawBbox = BoundingBox(minX, maxX, minY, maxY, minZ, maxZ)
        val cx = rawBbox.centerX
        val cy = rawBbox.centerY
        val cz = rawBbox.centerZ

        for (i in 0 until totalFloats step 3) {
            outVertices[i] -= cx
            outVertices[i + 1] -= cy
            outVertices[i + 2] -= cz
        }

        val centeredBbox = BoundingBox(
            minX = -rawBbox.widthX * 0.5f, maxX = rawBbox.widthX * 0.5f,
            minY = -rawBbox.depthY * 0.5f, maxY = rawBbox.depthY * 0.5f,
            minZ = -rawBbox.heightZ * 0.5f, maxZ = rawBbox.heightZ * 0.5f
        )

        val vertexBuffer = allocateDirectFloatBuffer(totalFloats).apply {
            put(outVertices, 0, totalFloats)
            position(0)
        }
        val normalBuffer = allocateDirectFloatBuffer(totalFloats).apply {
            put(outNormals, 0, totalFloats)
            position(0)
        }

        onProgress(1.0f)

        StlMesh(
            vertexBuffer = vertexBuffer,
            normalBuffer = normalBuffer,
            triangleCount = triCount,
            boundingBox = centeredBbox,
            isBinary = true,
            fileName = fileName,
            formatBadge = "3MF"
        )
    }

    private fun getAttr(parser: XmlPullParser, attrName: String): String? {
        parser.getAttributeValue(null, attrName)?.let { return it }
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeName(i).equals(attrName, ignoreCase = true)) {
                return parser.getAttributeValue(i)
            }
        }
        return null
    }

    private fun resolveUnitScale(unit: String?): Float {
        return when (unit?.lowercase()) {
            "micron" -> 0.001f
            "millimeter", null, "" -> 1.0f
            "centimeter" -> 10.0f
            "inch" -> 25.4f
            "foot" -> 304.8f
            "meter" -> 1000.0f
            else -> 1.0f
        }
    }

    private fun allocateDirectFloatBuffer(floatCount: Int): FloatBuffer {
        return ByteBuffer.allocateDirect(floatCount * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
    }

    private fun resolveFileName(contentResolver: ContentResolver, uri: Uri): String {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Modelo.3mf"
        } catch (_: Exception) {
            "Modelo.3mf"
        }
    }
}
