package com.geminivision.network

import android.util.Log
import com.geminivision.model.ClientMessage
import com.geminivision.model.ServerMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit

class WebSocketClient(private val backendUrl: String) {

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS) // sin timeout para WebSocket
        .pingInterval(30, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private val messageChannel = Channel<ServerMessage>(Channel.BUFFERED)
    private var reconnectAttempt = 0
    private val maxReconnectAttempts = 5

    val messages: Flow<ServerMessage> = messageChannel.receiveAsFlow()

    var isConnected: Boolean = false
        private set

    fun connect() {
        val request = Request.Builder()
            .url(backendUrl)
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket conectado a $backendUrl")
                isConnected = true
                reconnectAttempt = 0
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val msg = ServerMessage.fromJson(text)
                    messageChannel.trySend(msg)
                } catch (e: Exception) {
                    Log.e(TAG, "Error parseando mensaje: ${e.message}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket cerrando: $code $reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "WebSocket cerrado: $code $reason")
                isConnected = false
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket error: ${t.message}")
                isConnected = false
                messageChannel.trySend(
                    ServerMessage.Error("WS_FAILURE", t.message ?: "Error desconocido")
                )
                attemptReconnect()
            }
        })
    }

    fun send(message: ClientMessage) {
        val json = message.toJson()
        val sent = webSocket?.send(json) ?: false
        if (!sent) {
            Log.w(TAG, "No se pudo enviar mensaje: ${message::class.simpleName}")
        }
    }

    fun disconnect() {
        reconnectAttempt = maxReconnectAttempts // evitar reconexión
        webSocket?.close(1000, "Client disconnect")
        webSocket = null
        isConnected = false
    }

    private fun attemptReconnect() {
        if (reconnectAttempt >= maxReconnectAttempts) {
            Log.w(TAG, "Máximo de intentos de reconexión alcanzado")
            return
        }

        reconnectAttempt++
        val delayMs = minOf(1000L * (1 shl reconnectAttempt), 30_000L) // backoff exponencial, max 30s
        Log.d(TAG, "Reconectando en ${delayMs}ms (intento $reconnectAttempt)")

        Thread {
            Thread.sleep(delayMs)
            if (!isConnected) {
                connect()
            }
        }.start()
    }

    companion object {
        private const val TAG = "WebSocketClient"
    }
}
