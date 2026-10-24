package com.ministl.viewer.parser

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.ministl.viewer.model.StlMesh
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream

/**
 * Cargador unificado de modelos 3D (.STL y .3MF).
 * Detecta automáticamente el formato mediante la extensión del archivo o
 * la firma mágica ZIP (`PK\x03\x04`) y delega al parser correspondiente.
 */
object ModelLoader {

    suspend fun load(
        context: Context,
        uri: Uri,
        onProgress: (Float) -> Unit = {}
    ): StlMesh = withContext(Dispatchers.IO) {
        val contentResolver = context.contentResolver
        val fileName = resolveFileName(contentResolver, uri)
        val has3mfExtension = fileName.endsWith(".3mf", ignoreCase = true)

        if (has3mfExtension) {
            return@withContext ThreeMfParser.parse(context, uri, fileName, onProgress)
        }

        contentResolver.openInputStream(uri)?.use { rawStream ->
            val buffered = BufferedInputStream(rawStream, 64 * 1024)
            buffered.mark(16)
            val magic = ByteArray(4)
            val read = buffered.read(magic)
            buffered.reset()

            val isZipMagic = read == 4 &&
                magic[0] == 0x50.toByte() && // 'P'
                magic[1] == 0x4B.toByte() && // 'K'
                magic[2] == 0x03.toByte() && // '\x03'
                magic[3] == 0x04.toByte()    // '\x04'

            if (isZipMagic) {
                ThreeMfParser.parseFromStream(context, buffered, fileName, onProgress)
            } else {
                StlParser.parse(context, uri, onProgress)
            }
        } ?: throw IllegalArgumentException("No se pudo abrir el archivo 3D seleccionado.")
    }

    fun resolveFileName(contentResolver: ContentResolver, uri: Uri): String {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) cursor.getString(idx) else null
                } else null
            } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Modelo_3D.stl"
        } catch (_: Exception) {
            "Modelo_3D.stl"
        }
    }
}
