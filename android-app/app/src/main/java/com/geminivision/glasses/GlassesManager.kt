package com.geminivision.glasses

import android.app.Activity
import android.app.Application
import android.util.Log
import com.meta.wearable.dat.camera.StreamSession
import com.meta.wearable.dat.camera.startStreamSession
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.mockdevice.MockDeviceKit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Wrapper del Meta Wearables DAT SDK v0.5.0.
 *
 * Gestiona conexion con Ray-Ban Meta y streaming de video (frames I420).
 * Los frames se convierten a JPEG via [YuvToJpegConverter] antes de enviar al backend.
 * El audio NO pasa por el DAT SDK — se gestiona via Bluetooth SCO en AudioBridge.
 */
class GlassesManager(private val application: Application) {

    private val deviceSelector = AutoDeviceSelector()
    private var streamSession: StreamSession? = null
    private var videoJob: Job? = null
    private var stateJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    // Canal de frames JPEG (ya convertidos de I420)
    private val frameChannel = Channel<ByteArray>(Channel.CONFLATED)
    val videoFrames: Flow<ByteArray> = frameChannel.receiveAsFlow()

    var isConnected: Boolean = false
        private set

    var isStreaming: Boolean = false
        private set

    /**
     * Inicia el registro con Meta (abre dialogo de registro in-app).
     * Debe llamarse desde una Activity.
     */
    fun startRegistration(activity: Activity) {
        Wearables.startRegistration(activity)
    }

    /**
     * Crea un MockDevice para desarrollo sin gafas fisicas.
     */
    suspend fun setupMockDevice() {
        try {
            val mockDeviceKit = MockDeviceKit.getInstance(application)
            val mockDevice = mockDeviceKit.pairRaybanMeta()
            mockDevice.powerOn()
            mockDevice.don()
            Log.d(TAG, "MockDevice creado y encendido")
            isConnected = true
        } catch (e: Exception) {
            Log.e(TAG, "Error creando mock device: ${e.message}")
        }
    }

    /**
     * Solicita permiso de camara al SDK y, si se concede, inicia el stream.
     */
    suspend fun requestCameraAndStartStream() {
        val result = Wearables.checkPermissionStatus(Permission.CAMERA)
        result.onFailure { error, _ ->
            Log.e(TAG, "Error verificando permiso camara: ${error.description}")
            return
        }

        val status = result.getOrNull()
        if (status == PermissionStatus.Granted) {
            startStream()
        } else {
            Log.w(TAG, "Permiso de camara no concedido: $status")
            // El permiso se solicita via Wearables.requestPermission en la UI
        }
    }

    /**
     * Inicia el stream de video desde las gafas.
     * Los frames I420 se convierten a JPEG y se envian al canal.
     */
    fun startStream() {
        videoJob?.cancel()
        stateJob?.cancel()

        val session = Wearables.startStreamSession(
            application,
            deviceSelector,
            StreamConfiguration(videoQuality = VideoQuality.MEDIUM, 2) // 2fps suficiente
        )
        streamSession = session

        // Recoger frames de video
        videoJob = scope.launch {
            session.videoStream.collect { videoFrame ->
                // Convertir I420 a JPEG para enviar a Gemini
                val jpeg = YuvToJpegConverter.convert(
                    videoFrame.buffer,
                    videoFrame.width,
                    videoFrame.height,
                    quality = 70 // balance calidad/tamanio
                )
                if (jpeg != null) {
                    frameChannel.trySend(jpeg)
                }
            }
        }

        // Monitorear estado del stream
        stateJob = scope.launch {
            session.state.collect { state ->
                when (state) {
                    StreamSessionState.STREAMING -> {
                        isStreaming = true
                        isConnected = true
                        Log.d(TAG, "Stream de video activo")
                    }
                    StreamSessionState.STOPPED -> {
                        isStreaming = false
                        Log.d(TAG, "Stream detenido")
                    }
                    else -> {
                        Log.d(TAG, "Estado stream: $state")
                    }
                }
            }
        }

        Log.d(TAG, "Stream session iniciada (2fps, MEDIUM quality)")
    }

    /**
     * Monitorea dispositivos disponibles.
     */
    fun monitorDevices() {
        scope.launch {
            deviceSelector.activeDevice(Wearables.devices).collect { device ->
                isConnected = device != null
                Log.d(TAG, if (device != null) "Dispositivo activo detectado" else "Sin dispositivo")
            }
        }
    }

    fun stopStream() {
        videoJob?.cancel()
        videoJob = null
        stateJob?.cancel()
        stateJob = null
        streamSession?.close()
        streamSession = null
        isStreaming = false
        Log.d(TAG, "Stream detenido y sesion cerrada")
    }

    fun disconnect() {
        stopStream()
        isConnected = false
        Log.d(TAG, "Desconectado")
    }

    companion object {
        private const val TAG = "GlassesManager"
    }
}
