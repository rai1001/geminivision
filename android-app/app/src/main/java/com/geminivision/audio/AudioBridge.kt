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
 * Audio bidireccional via Bluetooth SCO.
 *
 * INPUT:  Micrófono de las gafas (via BT SCO) → PCM 16-bit, 16kHz, mono
 * OUTPUT: Respuesta de Gemini → PCM 24kHz → altavoces de las gafas (via BT audio)
 *
 * El micrófono de las gafas NO se accede via el DAT SDK.
 * Se accede como dispositivo Bluetooth estándar via AudioRecord + SCO.
 */
class AudioBridge(private val context: Context) {

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // Input: captura desde mic gafas (BT SCO)
    private val inputSampleRate = 16_000
    private val inputBufferSize = AudioRecord.getMinBufferSize(
        inputSampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    ).coerceAtLeast(3200) // mínimo ~100ms de buffer

    private var recorder: AudioRecord? = null
    private var captureJob: Job? = null

    // Output: playback en altavoces gafas
    private val outputSampleRate = 24_000
    private val player = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setSampleRate(outputSampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(
            AudioTrack.getMinBufferSize(
                outputSampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ) * 2
        )
        .build()

    var isCapturing: Boolean = false
        private set

    /**
     * Activa Bluetooth SCO y empieza a capturar audio.
     * Cada chunk de ~100ms (3200 bytes) se envía al callback.
     */
    @SuppressLint("MissingPermission")
    fun startCapture(onAudioChunk: (ByteArray) -> Unit) {
        // Activar canal SCO para mic de las gafas
        @Suppress("DEPRECATION")
        audioManager.startBluetoothSco()
        audioManager.isBluetoothScoOn = true

        // Forzar audio por Bluetooth
        @Suppress("DEPRECATION")
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

        recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            inputSampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            inputBufferSize
        )

        if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord no se pudo inicializar")
            return
        }

        recorder?.startRecording()
        isCapturing = true
        Log.d(TAG, "Captura de audio iniciada (${inputSampleRate}Hz, buffer=$inputBufferSize)")

        captureJob = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(3200) // ~100ms a 16kHz/16-bit/mono
            while (isActive && isCapturing) {
                val read = recorder?.read(buffer, 0, buffer.size) ?: -1
                if (read > 0) {
                    onAudioChunk(buffer.copyOf(read))
                }
            }
        }
    }

    /**
     * Reproduce audio PCM 24kHz recibido de Gemini en los altavoces de las gafas.
     */
    fun playAudio(pcmData: ByteArray) {
        if (player.playState != AudioTrack.PLAYSTATE_PLAYING) {
            player.play()
        }
        player.write(pcmData, 0, pcmData.size)
    }

    /**
     * Detiene la captura y limpia recursos.
     */
    fun stop() {
        isCapturing = false
        captureJob?.cancel()
        captureJob = null

        recorder?.apply {
            if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                stop()
            }
            release()
        }
        recorder = null

        player.apply {
            if (playState == AudioTrack.PLAYSTATE_PLAYING) {
                stop()
            }
            flush()
        }

        // Desactivar SCO
        @Suppress("DEPRECATION")
        audioManager.stopBluetoothSco()
        audioManager.isBluetoothScoOn = false
        audioManager.mode = AudioManager.MODE_NORMAL

        Log.d(TAG, "Audio bridge detenido")
    }

    fun release() {
        stop()
        player.release()
    }

    companion object {
        private const val TAG = "AudioBridge"
    }
}
