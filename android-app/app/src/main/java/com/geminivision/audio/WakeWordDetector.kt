package com.geminivision.audio

import android.util.Log

/**
 * Detector de wake word "Oye Gemini".
 *
 * MVP: No hay detección real — se activa manualmente por botón en UI.
 * V2: Integrar Picovoice Porcupine o similar para detección on-device.
 *
 * El flujo actual es:
 * 1. Usuario pulsa botón en la app (o botón físico de las gafas si disponible)
 * 2. Se activa la captura de audio y la sesión
 * 3. Gemini usa VAD automático para detectar cuándo el usuario deja de hablar
 */
class WakeWordDetector {

    var isListening: Boolean = false
        private set

    private var onWakeWord: (() -> Unit)? = null

    fun setOnWakeWordListener(listener: () -> Unit) {
        onWakeWord = listener
    }

    /**
     * MVP: Activación manual desde la UI.
     */
    fun triggerManually() {
        Log.d(TAG, "Wake word triggered manualmente")
        onWakeWord?.invoke()
    }

    fun startListening() {
        isListening = true
        Log.d(TAG, "Wake word listener activo (modo manual MVP)")
    }

    fun stopListening() {
        isListening = false
        Log.d(TAG, "Wake word listener detenido")
    }

    companion object {
        private const val TAG = "WakeWordDetector"
    }
}
