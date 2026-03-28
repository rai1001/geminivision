package com.geminivision.model

import org.json.JSONObject

// --- Mensajes Cliente → Backend ---

sealed class ClientMessage {
    abstract fun toJson(): String

    data class Auth(val token: String) : ClientMessage() {
        override fun toJson() = JSONObject().apply {
            put("type", "auth")
            put("token", token)
        }.toString()
    }

    data class Audio(val payload: String) : ClientMessage() {
        override fun toJson() = JSONObject().apply {
            put("type", "audio")
            put("payload", payload)
        }.toString()
    }

    data class Video(val payload: String) : ClientMessage() {
        override fun toJson() = JSONObject().apply {
            put("type", "video")
            put("payload", payload)
        }.toString()
    }

    data class StartSession(
        val systemInstruction: String? = null,
        val voice: String? = null,
        val useVideo: Boolean? = null
    ) : ClientMessage() {
        override fun toJson() = JSONObject().apply {
            put("type", "startSession")
            if (systemInstruction != null || voice != null || useVideo != null) {
                put("config", JSONObject().apply {
                    systemInstruction?.let { put("systemInstruction", it) }
                    voice?.let { put("voice", it) }
                    useVideo?.let { put("useVideo", it) }
                })
            }
        }.toString()
    }

    object EndSession : ClientMessage() {
        override fun toJson() = """{"type":"endSession"}"""
    }
}

// --- Mensajes Backend → Cliente ---

sealed class ServerMessage {
    data class Authenticated(val sessionId: String) : ServerMessage()
    data class AudioResponse(val payload: String) : ServerMessage()
    data class Transcript(val source: String, val text: String) : ServerMessage()
    object TurnComplete : ServerMessage()
    data class Error(val code: String, val message: String) : ServerMessage()
    data class SessionExpiring(val secondsRemaining: Int) : ServerMessage()
    data class Unknown(val raw: String) : ServerMessage()

    companion object {
        fun fromJson(json: String): ServerMessage {
            val obj = JSONObject(json)
            return when (obj.getString("type")) {
                "authenticated" -> Authenticated(obj.getString("sessionId"))
                "audio" -> AudioResponse(obj.getString("payload"))
                "transcript" -> Transcript(
                    obj.getString("source"),
                    obj.getString("text")
                )
                "turnComplete" -> TurnComplete
                "error" -> Error(
                    obj.getString("code"),
                    obj.getString("message")
                )
                "sessionExpiring" -> SessionExpiring(obj.getInt("secondsRemaining"))
                else -> Unknown(json)
            }
        }
    }
}
