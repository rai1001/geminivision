package com.geminivision.service

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.geminivision.BuildConfig
import com.geminivision.GeminiVisionApp
import com.geminivision.R
import com.geminivision.audio.AudioBridge
import com.geminivision.glasses.GlassesManager
import com.geminivision.model.ClientMessage
import com.geminivision.model.ServerMessage
import com.geminivision.network.WebSocketClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Foreground Service que mantiene la conexión con las gafas y Gemini activa.
 * Funciona con pantalla apagada gracias al foreground service type.
 *
 * Orquesta: WebSocketClient ↔ AudioBridge ↔ GlassesManager
 */
class GlassesService : Service() {

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var wsClient: WebSocketClient
    private lateinit var audioBridge: AudioBridge
    private lateinit var glassesManager: GlassesManager

    // Estado observable desde la UI
    private val _state = MutableStateFlow(ServiceState())
    val state: StateFlow<ServiceState> = _state

    private val _transcripts = MutableStateFlow<List<TranscriptEntry>>(emptyList())
    val transcripts: StateFlow<List<TranscriptEntry>> = _transcripts

    inner class LocalBinder : Binder() {
        fun getService(): GlassesService = this@GlassesService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    /**
     * Inicia la conexión completa: WebSocket → Auth → Gemini → Audio + Video
     */
    fun startSession(token: String) {
        Log.d(TAG, "Iniciando sesión completa...")

        // 1. WebSocket al backend
        wsClient = WebSocketClient(BuildConfig.BACKEND_URL)
        audioBridge = AudioBridge(this)
        glassesManager = GlassesManager(this)

        // 2. Escuchar mensajes del backend
        serviceScope.launch {
            wsClient.messages.collect { msg ->
                handleServerMessage(msg)
            }
        }

        // 3. Conectar WebSocket
        wsClient.connect()
        updateState { copy(wsConnected = true) }

        // 4. Autenticarse (después de connect, el listener onOpen se encargará)
        // Pequeño delay para esperar onOpen
        serviceScope.launch(Dispatchers.IO) {
            Thread.sleep(500)
            wsClient.send(ClientMessage.Auth(token))
        }
    }

    private fun handleServerMessage(msg: ServerMessage) {
        when (msg) {
            is ServerMessage.Authenticated -> {
                Log.d(TAG, "Autenticado: ${msg.sessionId}")
                updateState { copy(authenticated = true, sessionId = msg.sessionId) }

                // Iniciar sesión Gemini
                wsClient.send(ClientMessage.StartSession())

                // Iniciar captura de audio
                startAudioCapture()

                // Conectar gafas (mock o real)
                connectGlasses()
            }

            is ServerMessage.AudioResponse -> {
                // Decodificar y reproducir en altavoces gafas
                val pcmData = Base64.decode(msg.payload, Base64.NO_WRAP)
                audioBridge.playAudio(pcmData)
            }

            is ServerMessage.Transcript -> {
                Log.d(TAG, "[${msg.source}] ${msg.text}")
                addTranscript(msg.source, msg.text)
            }

            is ServerMessage.TurnComplete -> {
                Log.d(TAG, "Turno completado")
            }

            is ServerMessage.SessionExpiring -> {
                Log.w(TAG, "Sesión expira en ${msg.secondsRemaining}s")
                updateState { copy(sessionExpiring = true) }
            }

            is ServerMessage.Error -> {
                Log.e(TAG, "Error: [${msg.code}] ${msg.message}")
                updateState { copy(lastError = "${msg.code}: ${msg.message}") }
            }

            is ServerMessage.Unknown -> {
                Log.w(TAG, "Mensaje desconocido: ${msg.raw}")
            }
        }
    }

    private fun startAudioCapture() {
        audioBridge.startCapture { chunk ->
            // Enviar audio al backend como base64
            val base64 = Base64.encodeToString(chunk, Base64.NO_WRAP)
            wsClient.send(ClientMessage.Audio(base64))
        }
        updateState { copy(audioActive = true) }
        Log.d(TAG, "Captura de audio iniciada")
    }

    private fun connectGlasses() {
        glassesManager.connect()
        updateState { copy(glassesConnected = glassesManager.isConnected) }

        // Reenviar frames de video al backend
        serviceScope.launch {
            glassesManager.videoFrames.collect { jpegData ->
                val base64 = Base64.encodeToString(jpegData, Base64.NO_WRAP)
                wsClient.send(ClientMessage.Video(base64))
            }
        }
    }

    fun stopSession() {
        Log.d(TAG, "Deteniendo sesión...")

        audioBridge.stop()
        glassesManager.disconnect()

        if (wsClient.isConnected) {
            wsClient.send(ClientMessage.EndSession)
            wsClient.disconnect()
        }

        updateState {
            ServiceState() // reset
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(
        this,
        GeminiVisionApp.NOTIFICATION_CHANNEL_ID
    )
        .setContentTitle("GeminiVision activo")
        .setContentText("Conectado a las gafas")
        .setSmallIcon(R.drawable.ic_glasses)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setOngoing(true)
        .build()

    private fun updateState(update: ServiceState.() -> ServiceState) {
        _state.value = _state.value.update()
    }

    private fun addTranscript(source: String, text: String) {
        val current = _transcripts.value.toMutableList()
        current.add(TranscriptEntry(source, text))
        if (current.size > 50) current.removeAt(0) // limitar historial
        _transcripts.value = current
    }

    override fun onDestroy() {
        super.onDestroy()
        stopSession()
        audioBridge.release()
    }

    companion object {
        private const val TAG = "GlassesService"
        private const val NOTIFICATION_ID = 1
    }
}

data class ServiceState(
    val wsConnected: Boolean = false,
    val authenticated: Boolean = false,
    val sessionId: String? = null,
    val glassesConnected: Boolean = false,
    val audioActive: Boolean = false,
    val sessionExpiring: Boolean = false,
    val lastError: String? = null
)

data class TranscriptEntry(
    val source: String, // "user" o "model"
    val text: String
)
