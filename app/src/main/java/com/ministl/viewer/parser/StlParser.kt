package com.ministl.viewer.parser

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.ministl.viewer.model.BoundingBox
import com.ministl.viewer.model.StlMesh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object StlParser {

    private const val MAX_TRIANGLES = 2_000_000

    suspend fun parse(
        contentResolver: ContentResolver,
        uri: Uri,
        onProgress: (Float) -> Unit = {}
    ): StlMesh = withContext(Dispatchers.IO) {
        val fileSize = contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        val fileName = resolveFileName(contentResolver, uri)

        onProgress(0.02f)

        contentResolver.openInputStream(uri)?.use { rawStream ->
            val stream = BufferedInputStream(rawStream, 64 * 1024)
            stream.mark(1024)

            val headerBytes = ByteArray(84)
            val bytesRead = readFully(stream, headerBytes, 84)
            stream.reset()

            val isBinary = isBinaryStl(headerBytes, bytesRead, fileSize)
            val mesh = if (isBinary) {
                parseBinary(stream, fileName, onProgress)
            } else {
                parseAscii(stream, fileSize, fileName, onProgress)
            }
            onProgress(1.0f)
            mesh
        } ?: throw IllegalArgumentException("No se pudo abrir el archivo STL.")
    }

    private fun resolveFileName(contentResolver: ContentResolver, uri: Uri): String {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Modelo.stl"
        } catch (_: Exception) {
            "Modelo.stl"
        }
    }

    /**
     * Verifica si el archivo es binario comprobando la relación matemática entre el número de
     * triángulos del encabezado (bytes 80..83) y el tamaño del archivo, o presencia de bytes no-ASCII.
     */
    private fun isBinaryStl(header: ByteArray, bytesRead: Int, fileSize: Long): Boolean {
        if (bytesRead < 84) return false

        val bb = ByteBuffer.wrap(header, 80, 4).order(ByteOrder.LITTLE_ENDIAN)
        val triCount = bb.int.toLong() and 0xFFFFFFFFL
        val expectedSize = 84L + (triCount * 50L)

        if (fileSize > 0 && expectedSize == fileSize) {
            return true
        }

        val startsWithSolid = String(header, 0, 5, Charsets.US_ASCII).equals("solid", ignoreCase = true)
        if (!startsWithSolid) return true

        // Si empieza con "solid", verificamos si hay bytes nulos en la cabecera
        for (i in 0 until bytesRead) {
            if (header[i] == 0.toByte()) return true
        }
        return false
    }

    private suspend fun parseBinary(
        stream: InputStream,
        fileName: String,
        onProgress: (Float) -> Unit
    ): StlMesh {
        val header = ByteArray(84)
        if (readFully(stream, header, 84) < 84) {
            throw IllegalArgumentException("Archivo STL binario corrupto o incompleto.")
        }

        val rawTriCount = ByteBuffer.wrap(header, 80, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val triCount = rawTriCount.coerceIn(1, MAX_TRIANGLES)

        val floatCount = triCount * 9 // 3 vértices * 3 coordenadas (x,y,z)
        val vertexBuffer = allocateDirectFloatBuffer(floatCount)
        val normalBuffer = allocateDirectFloatBuffer(floatCount)

        var minX = Float.POSITIVE_INFINITY; var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY; var maxZ = Float.NEGATIVE_INFINITY

        // Buffer de lectura por lotes (2048 triángulos = 102,400 bytes por bloque)
        val batchTriangles = 2048
        val chunkBytes = ByteArray(batchTriangles * 50)
        val chunkBuffer = ByteBuffer.wrap(chunkBytes).order(ByteOrder.LITTLE_ENDIAN)

        var trianglesRead = 0
        while (trianglesRead < triCount) {
            coroutineContext.ensureActive()

            val trisToRead = min(batchTriangles, triCount - trianglesRead)
            val bytesNeeded = trisToRead * 50
            val actualRead = readFully(stream, chunkBytes, bytesNeeded)
            val actualTris = actualRead / 50
            if (actualTris == 0) break

            chunkBuffer.position(0)
            for (i in 0 until actualTris) {
                var nx = chunkBuffer.float
                var ny = chunkBuffer.float
                var nz = chunkBuffer.float

                val v1x = chunkBuffer.float; val v1y = chunkBuffer.float; val v1z = chunkBuffer.float
                val v2x = chunkBuffer.float; val v2y = chunkBuffer.float; val v2z = chunkBuffer.float
                val v3x = chunkBuffer.float; val v3y = chunkBuffer.float; val v3z = chunkBuffer.float
                chunkBuffer.short // Attribute byte count (2 bytes)

                // Si el STL no trae normal calculada (0,0,0), la calculamos con producto cruzado
                val lenSq = nx * nx + ny * ny + nz * nz
                if (lenSq < 1e-6f) {
                    val ux = v2x - v1x; val uy = v2y - v1y; val uz = v2z - v1z
                    val vx = v3x - v1x; val vy = v3y - v1y; val vz = v3z - v1z
                    nx = uy * vz - uz * vy
                    ny = uz * vx - ux * vz
                    nz = ux * vy - uy * vx
                    val len = sqrt(nx * nx + ny * ny + nz * nz)
                    if (len > 1e-6f) {
                        nx /= len; ny /= len; nz /= len
                    }
                } else {
                    val invLen = 1f / sqrt(lenSq)
                    nx *= invLen; ny *= invLen; nz *= invLen
                }

                // Actualizar Bounding Box
                minX = minOf(minX, v1x, v2x, v3x); maxX = maxOf(maxX, v1x, v2x, v3x)
                minY = minOf(minY, v1y, v2y, v3y); maxY = maxOf(maxY, v1y, v2y, v3y)
                minZ = minOf(minZ, v1z, v2z, v3z); maxZ = maxOf(maxZ, v1z, v2z, v3z)

                vertexBuffer.put(v1x).put(v1y).put(v1z)
                vertexBuffer.put(v2x).put(v2y).put(v2z)
                vertexBuffer.put(v3x).put(v3y).put(v3z)

                normalBuffer.put(nx).put(ny).put(nz)
                normalBuffer.put(nx).put(ny).put(nz)
                normalBuffer.put(nx).put(ny).put(nz)
            }
            trianglesRead += actualTris
            onProgress((trianglesRead.toFloat() / triCount.toFloat()).coerceIn(0.05f, 0.95f))
        }

        val rawBbox = BoundingBox(minX, maxX, minY, maxY, minZ, maxZ)
        centerMeshInPlace(vertexBuffer, trianglesRead * 9, rawBbox)

        val centeredBbox = BoundingBox(
            minX = -rawBbox.widthX * 0.5f, maxX = rawBbox.widthX * 0.5f,
            minY = -rawBbox.depthY * 0.5f, maxY = rawBbox.depthY * 0.5f,
            minZ = -rawBbox.heightZ * 0.5f, maxZ = rawBbox.heightZ * 0.5f
        )

        vertexBuffer.position(0)
        normalBuffer.position(0)

        return StlMesh(
            vertexBuffer = vertexBuffer,
            normalBuffer = normalBuffer,
            triangleCount = trianglesRead,
            boundingBox = centeredBbox,
            isBinary = true,
            fileName = fileName
        )
    }

    private suspend fun parseAscii(
        stream: InputStream,
        fileSize: Long,
        fileName: String,
        onProgress: (Float) -> Unit
    ): StlMesh {
        val reader = BufferedReader(InputStreamReader(stream, Charsets.US_ASCII), 64 * 1024)
        val capacityFloats = 30_000 * 9
        var vertices = FloatArray(capacityFloats)
        var normals = FloatArray(capacityFloats)

        var floatIndex = 0
        var curNx = 0f; var curNy = 0f; var curNz = 0f

        var minX = Float.POSITIVE_INFINITY; var maxX = Float.NEGATIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY; var maxY = Float.NEGATIVE_INFINITY
        var minZ = Float.POSITIVE_INFINITY; var maxZ = Float.NEGATIVE_INFINITY

        var lineCount = 0
        var bytesApprox = 0L
        while (true) {
            val rawLine = reader.readLine() ?: break
            bytesApprox += rawLine.length + 1
            if (++lineCount % 4096 == 0) {
                coroutineContext.ensureActive()
                if (fileSize > 0) {
                    onProgress((bytesApprox.toFloat() / fileSize.toFloat()).coerceIn(0.05f, 0.92f))
                }
            }

            val line = rawLine.trim()
            when {
                line.startsWith("facet normal", ignoreCase = true) -> {
                    val parts = line.split(Regex("\\s+"))
                    if (parts.size >= 5) {
                        curNx = parts[2].toFloatOrNull() ?: 0f
                        curNy = parts[3].toFloatOrNull() ?: 0f
                        curNz = parts[4].toFloatOrNull() ?: 0f
                    }
                }
                line.startsWith("vertex", ignoreCase = true) -> {
                    if (floatIndex / 9 < MAX_TRIANGLES) {
                        val parts = line.split(Regex("\\s+"))
                        if (parts.size >= 4) {
                            val vx = parts[1].toFloatOrNull() ?: 0f
                            val vy = parts[2].toFloatOrNull() ?: 0f
                            val vz = parts[3].toFloatOrNull() ?: 0f

                            if (floatIndex + 3 > vertices.size) {
                                val newSize = min(vertices.size * 2, MAX_TRIANGLES * 9)
                                vertices = vertices.copyOf(newSize)
                                normals = normals.copyOf(newSize)
                            }

                            minX = min(minX, vx); maxX = max(maxX, vx)
                            minY = min(minY, vy); maxY = max(maxY, vy)
                            minZ = min(minZ, vz); maxZ = max(maxZ, vz)

                            vertices[floatIndex] = vx
                            vertices[floatIndex + 1] = vy
                            vertices[floatIndex + 2] = vz

                            normals[floatIndex] = curNx
                            normals[floatIndex + 1] = curNy
                            normals[floatIndex + 2] = curNz

                            floatIndex += 3

                            // Al completar los 3 vértices del triángulo, verificar si la normal era (0,0,0)
                            if (floatIndex % 9 == 0) {
                                val base = floatIndex - 9
                                var nx = normals[base]; var ny = normals[base + 1]; var nz = normals[base + 2]
                                if (nx * nx + ny * ny + nz * nz < 1e-6f) {
                                    val ux = vertices[base + 3] - vertices[base]
                                    val uy = vertices[base + 4] - vertices[base + 1]
                                    val uz = vertices[base + 5] - vertices[base + 2]
                                    val wx = vertices[base + 6] - vertices[base]
                                    val wy = vertices[base + 7] - vertices[base + 1]
                                    val wz = vertices[base + 8] - vertices[base + 2]
                                    nx = uy * wz - uz * wy
                                    ny = uz * wx - ux * wz
                                    nz = ux * wy - uy * wx
                                    val len = sqrt(nx * nx + ny * ny + nz * nz)
                                    if (len > 1e-6f) { nx /= len; ny /= len; nz /= len }
                                    for (k in 0 until 9 step 3) {
                                        normals[base + k] = nx
                                        normals[base + k + 1] = ny
                                        normals[base + k + 2] = nz
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        val triCount = floatIndex / 9
        if (triCount == 0) throw IllegalArgumentException("El archivo STL no contiene triángulos válidos.")

        val rawBbox = BoundingBox(minX, maxX, minY, maxY, minZ, maxZ)
        val cx = rawBbox.centerX; val cy = rawBbox.centerY; val cz = rawBbox.centerZ

        for (i in 0 until floatIndex step 3) {
            vertices[i] -= cx
            vertices[i + 1] -= cy
            vertices[i + 2] -= cz
        }

        val centeredBbox = BoundingBox(
            minX = -rawBbox.widthX * 0.5f, maxX = rawBbox.widthX * 0.5f,
            minY = -rawBbox.depthY * 0.5f, maxY = rawBbox.depthY * 0.5f,
            minZ = -rawBbox.heightZ * 0.5f, maxZ = rawBbox.heightZ * 0.5f
        )

        val vertexBuffer = allocateDirectFloatBuffer(floatIndex).apply {
            put(vertices, 0, floatIndex)
            position(0)
        }
        val normalBuffer = allocateDirectFloatBuffer(floatIndex).apply {
            put(normals, 0, floatIndex)
            position(0)
        }

        return StlMesh(
            vertexBuffer = vertexBuffer,
            normalBuffer = normalBuffer,
            triangleCount = triCount,
            boundingBox = centeredBbox,
            isBinary = false,
            fileName = fileName
        )
    }

    private fun centerMeshInPlace(buffer: FloatBuffer, totalFloats: Int, bbox: BoundingBox) {
        val cx = bbox.centerX
        val cy = bbox.centerY
        val cz = bbox.centerZ
        for (i in 0 until totalFloats step 3) {
            buffer.put(i, buffer.get(i) - cx)
            buffer.put(i + 1, buffer.get(i + 1) - cy)
            buffer.put(i + 2, buffer.get(i + 2) - cz)
        }
    }

    private fun allocateDirectFloatBuffer(floatCount: Int): FloatBuffer {
        return ByteBuffer.allocateDirect(floatCount * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
    }

    private fun readFully(stream: InputStream, buffer: ByteArray, length: Int): Int {
        var total = 0
        while (total < length) {
            val read = stream.read(buffer, total, length - total)
            if (read == -1) break
            total += read
        }
        return total
    }
}
