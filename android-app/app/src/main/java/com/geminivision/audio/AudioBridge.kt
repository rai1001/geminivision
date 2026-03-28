package com.geminivision.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Audio bidireccional via Bluetooth SCO con engines separados.
 *
 * Usa dos engines independientes para evitar feedback loops:
 * - AudioCaptureEngine: mic gafas (BT SCO) -> PCM 16kHz
 * - AudioPlaybackEngine: respuesta Gemini -> altavoces gafas, PCM 24kHz
 *
 * TurboMeta pattern: separar capture/playback previene que el audio
 * de respuesta se re-capture por el microfono.
 */
class AudioBridge(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val captureEngine = AudioCaptureEngine()
    private val playbackEngine = AudioPlaybackEngine()

    val isCapturing: Boolean get() = captureEngine.isActive

    /**
     * Configura audio session para Bluetooth y activa SCO.
     */
    fun setupBluetoothAudio() {
        @Suppress("DEPRECATION")
        audioManager.startBluetoothSco()
        audioManager.isBluetoothScoOn = true
        @Suppress("DEPRECATION")
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        Log.d(TAG, "Bluetooth SCO activado")
    }

    /**
     * Inicia captura de audio del microfono de las gafas.
     */
    fun startCapture(onAudioChunk: (ByteArray) -> Unit) {
        setupBluetoothAudio()
        captureEngine.start(onAudioChunk)
    }

    /**
     * Reproduce audio PCM 24kHz en los altavoces de las gafas.
     * Opera en un engine separado del capture para evitar feedback.
     */
    fun playAudio(pcmData: ByteArray) {
        playbackEngine.play(pcmData)
    }

    /**
     * Pausa la captura temporalmente (ej: mientras Gemini habla).
     * Util para evitar que el mic capture la respuesta.
     */
    fun pauseCapture() {
        captureEngine.pause()
    }

    fun resumeCapture() {
        captureEngine.resume()
    }

    fun stop() {
        captureEngine.stop()
        playbackEngine.stop()

        @Suppress("DEPRECATION")
        audioManager.stopBluetoothSco()
        audioManager.isBluetoothScoOn = false
        audioManager.mode = AudioManager.MODE_NORMAL
        Log.d(TAG, "Audio bridge detenido")
    }

    fun release() {
        stop()
        playbackEngine.release()
    }

    companion object {
        private const val TAG = "AudioBridge"
    }
}

/**
 * Engine de captura de audio — aislado del playback.
 */
private class AudioCaptureEngine {

    private val sampleRate = 16_000
    private val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
    ).coerceAtLeast(3200)

    private var recorder: AudioRecord? = null
    private var job: Job? = null
    var isActive: Boolean = false
        private set
    private var isPaused: Boolean = false

    @SuppressLint("MissingPermission")
    fun start(onChunk: (ByteArray) -> Unit) {
        recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            sampleRate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, bufferSize
        )

        if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e("AudioCapture", "AudioRecord no inicializado")
            return
        }

        recorder?.startRecording()
        isActive = true
        isPaused = false
        Log.d("AudioCapture", "Captura iniciada (${sampleRate}Hz)")

        job = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(3200) // ~100ms
            while (isActive && isActive) {
                val read = recorder?.read(buffer, 0, buffer.size) ?: -1
                if (read > 0 && !isPaused) {
                    onChunk(buffer.copyOf(read))
                }
            }
        }
    }

    fun pause() { isPaused = true }
    fun resume() { isPaused = false }

    fun stop() {
        isActive = false
        job?.cancel()
        job = null
        recorder?.apply {
            if (recordingState == AudioRecord.RECORDSTATE_RECORDING) stop()
            release()
        }
        recorder = null
    }
}

/**
 * Engine de playback de audio — aislado del capture.
 * Buffer inteligente: espera minimo 2 chunks antes de reproducir
 * para evitar cortes (patron TurboMeta).
 */
private class AudioPlaybackEngine {

    private val sampleRate = 24_000
    private val player = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(
            AudioTrack.getMinBufferSize(
                sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
            ) * 2
        )
        .build()

    private val buffer = mutableListOf<ByteArray>()
    private var chunksReceived = 0
    private val minChunksBeforePlay = 2 // buffering inteligente

    fun play(pcmData: ByteArray) {
        chunksReceived++

        if (chunksReceived <= minChunksBeforePlay) {
            // Bufferear primeros chunks para reproduccion mas fluida
            buffer.add(pcmData)
            if (chunksReceived == minChunksBeforePlay) {
                if (player.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    player.play()
                }
                // Escribir chunks buffereados
                for (chunk in buffer) {
                    player.write(chunk, 0, chunk.size)
                }
                buffer.clear()
            }
            return
        }

        if (player.playState != AudioTrack.PLAYSTATE_PLAYING) {
            player.play()
        }
        player.write(pcmData, 0, pcmData.size)
    }

    fun stop() {
        chunksReceived = 0
        buffer.clear()
        if (player.playState == AudioTrack.PLAYSTATE_PLAYING) {
            player.stop()
        }
        player.flush()
    }

    fun release() {
        stop()
        player.release()
    }
}
