package com.geminivision.glasses

import android.content.Context
import android.util.Log
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Wrapper del Meta Wearables DAT SDK.
 *
 * Gestiona la conexión con las Ray-Ban Meta y el streaming de video (JPEG frames).
 * El audio NO pasa por el DAT SDK — se gestiona via Bluetooth SCO en AudioBridge.
 *
 * En modo desarrollo, usa MockDeviceKit para simular las gafas.
 */
class GlassesManager(private val context: Context) {

    private val frameChannel = Channel<ByteArray>(Channel.CONFLATED) // solo último frame

    val videoFrames: Flow<ByteArray> = frameChannel.receiveAsFlow()

    var isConnected: Boolean = false
        private set

    var useMockDevice: Boolean = true // true en desarrollo

    /**
     * Conecta a las gafas (o al mock device) e inicia el stream de cámara.
     */
    fun connect() {
        if (useMockDevice) {
            connectMockDevice()
        } else {
            connectRealDevice()
        }
    }

    private fun connectMockDevice() {
        try {
            // MockDeviceKit.getInstance().apply {
            //     initialize(context)
            //     addMockDevice("Ray-Ban Meta Mock")
            // }
            // TODO: Descomentar con SDK real
            Log.d(TAG, "MockDevice inicializado")
            isConnected = true
            startMockCameraStream()
        } catch (e: Exception) {
            Log.e(TAG, "Error conectando mock device: ${e.message}")
        }
    }

    private fun connectRealDevice() {
        try {
            // val deviceManager = WearableDeviceManager.getInstance(context)
            // deviceManager.getAvailableDevices { devices ->
            //     val glasses = devices.firstOrNull() ?: return@getAvailableDevices
            //     glasses.openSession { session ->
            //         streamSession = session.startCameraStream { frame ->
            //             frameChannel.trySend(frame.data)
            //         }
            //     }
            // }
            // TODO: Implementar con SDK real
            Log.d(TAG, "Conexión real pendiente de SDK")
        } catch (e: Exception) {
            Log.e(TAG, "Error conectando gafas: ${e.message}")
        }
    }

    /**
     * Simula stream de cámara enviando frames vacíos a 1fps.
     * En producción el SDK envía JPEG reales del POV de las gafas.
     */
    private fun startMockCameraStream() {
        Thread {
            Log.d(TAG, "Mock camera stream iniciado (1fps)")
            while (isConnected) {
                // En mock, enviamos un JPEG mínimo placeholder
                // El backend lo recibirá pero Gemini no lo procesará de forma útil
                // Para tests reales, se puede inyectar un JPEG real aquí
                Thread.sleep(1000) // 1fps
            }
        }.start()
    }

    fun disconnect() {
        isConnected = false
        // streamSession?.stop()
        Log.d(TAG, "Gafas desconectadas")
    }

    /**
     * Inyecta un frame JPEG manualmente (útil para testing sin gafas).
     */
    fun injectFrame(jpegData: ByteArray) {
        frameChannel.trySend(jpegData)
    }

    companion object {
        private const val TAG = "GlassesManager"
    }
}
