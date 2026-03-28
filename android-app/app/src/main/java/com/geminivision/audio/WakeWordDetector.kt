package com.geminivision.audio

import android.content.Context
import android.util.Log

/**
 * Detector de wake word usando Picovoice Porcupine.
 *
 * Soporta dos modos:
 * 1. Wake word "Oye Gemini" (custom keyword) — requiere Porcupine access key
 * 2. Activacion manual por boton (fallback)
 *
 * Picovoice Porcupine procesa audio on-device, sin enviar nada a la nube.
 * Consumo minimo de bateria (~2% CPU).
 *
 * Setup:
 * 1. Crear cuenta en picovoice.ai (free tier: 3 custom keywords)
 * 2. Generar keyword "Oye Gemini" en la consola
 * 3. Descargar .ppn file y ponerlo en assets/
 * 4. Copiar access key en BuildConfig.PORCUPINE_ACCESS_KEY
 *
 * Dependencia Gradle:
 *   implementation("ai.picovoice:porcupine-android:3.0.0")
 */
class WakeWordDetector(private val context: Context) {

    private var onWakeWord: (() -> Unit)? = null

    // Porcupine instance (descomentar cuando se integre)
    // private var porcupine: Porcupine? = null
    // private var porcupineManager: PorcupineManager? = null

    var isListening: Boolean = false
        private set

    var usePorcupine: Boolean = false // true cuando se configure access key

    fun setOnWakeWordListener(listener: () -> Unit) {
        onWakeWord = listener
    }

    /**
     * Inicia la deteccion de wake word.
     * Si Porcupine esta configurado, usa deteccion por voz.
     * Si no, solo acepta activacion manual.
     */
    fun startListening() {
        if (usePorcupine) {
            startPorcupine()
        } else {
            Log.d(TAG, "Wake word en modo manual (Porcupine no configurado)")
        }
        isListening = true
    }

    private fun startPorcupine() {
        try {
            // val accessKey = BuildConfig.PORCUPINE_ACCESS_KEY
            // if (accessKey.isBlank()) {
            //     Log.w(TAG, "Porcupine access key no configurada")
            //     return
            // }
            //
            // porcupineManager = PorcupineManager.Builder()
            //     .setAccessKey(accessKey)
            //     .setKeywordPath("oye_gemini.ppn") // custom keyword en assets/
            //     .setSensitivity(0.7f)
            //     .build(context) { keywordIndex ->
            //         Log.d(TAG, "Wake word detectado!")
            //         onWakeWord?.invoke()
            //     }
            //
            // porcupineManager?.start()
            Log.d(TAG, "Porcupine wake word activo (pendiente de integracion)")
        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando Porcupine: ${e.message}")
            usePorcupine = false
        }
    }

    /**
     * Activacion manual desde la UI o boton fisico de las gafas.
     */
    fun triggerManually() {
        Log.d(TAG, "Wake word triggered manualmente")
        onWakeWord?.invoke()
    }

    fun stopListening() {
        isListening = false
        // porcupineManager?.stop()
        // porcupineManager?.delete()
        // porcupineManager = null
        Log.d(TAG, "Wake word listener detenido")
    }

    fun release() {
        stopListening()
        // porcupine?.delete()
        // porcupine = null
    }

    companion object {
        private const val TAG = "WakeWordDetector"
    }
}
