package com.geminivision.glasses

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Convierte frames I420 (YUV) del DAT SDK a JPEG para enviar a Gemini.
 *
 * El DAT SDK entrega frames en formato I420 (YUV420 planar).
 * Gemini Live API espera JPEG en base64.
 * Esta clase hace la conversion I420 -> NV21 -> JPEG.
 */
object YuvToJpegConverter {

    private const val TAG = "YuvToJpegConverter"

    /**
     * Convierte un frame I420 a JPEG.
     *
     * @param buffer ByteBuffer con datos I420 del VideoFrame del SDK
     * @param width Ancho del frame
     * @param height Alto del frame
     * @param quality Calidad JPEG (0-100). 70 es buen balance para streaming.
     * @return ByteArray con JPEG, o null si falla la conversion
     */
    fun convert(buffer: ByteBuffer, width: Int, height: Int, quality: Int = 70): ByteArray? {
        return try {
            val i420Data = ByteArray(buffer.remaining())
            buffer.get(i420Data)
            buffer.rewind()

            // I420 (YUV420 planar) -> NV21 (YUV420 semi-planar)
            // Android YuvImage solo soporta NV21
            val nv21 = i420ToNv21(i420Data, width, height)

            // NV21 -> JPEG
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            val outputStream = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), quality, outputStream)

            outputStream.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Error convirtiendo I420 a JPEG: ${e.message}")
            null
        }
    }

    /**
     * Convierte I420 (planar: Y + U + V) a NV21 (semi-planar: Y + VU interleaved).
     *
     * I420 layout: [YYYY...][UU...][VV...]
     * NV21 layout: [YYYY...][VUVU...]
     */
    private fun i420ToNv21(i420: ByteArray, width: Int, height: Int): ByteArray {
        val frameSize = width * height
        val chromaSize = frameSize / 4
        val nv21 = ByteArray(frameSize + frameSize / 2)

        // Copiar plano Y directamente
        System.arraycopy(i420, 0, nv21, 0, frameSize)

        // Intercalar V y U para formato NV21 (VU, VU, VU...)
        val uOffset = frameSize
        val vOffset = frameSize + chromaSize
        var nv21Offset = frameSize

        for (i in 0 until chromaSize) {
            nv21[nv21Offset++] = i420[vOffset + i] // V
            nv21[nv21Offset++] = i420[uOffset + i] // U
        }

        return nv21
    }
}
