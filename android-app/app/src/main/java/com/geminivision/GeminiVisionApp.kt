package com.geminivision

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class GeminiVisionApp : Application() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID = "glasses_service"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initWearablesSdk()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "GeminiVision Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantiene la conexión con las gafas activa"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun initWearablesSdk() {
        try {
            com.meta.wearable.dat.core.Wearables.initialize(this)
            android.util.Log.d("GeminiVision", "Meta Wearables SDK inicializado")
        } catch (e: Exception) {
            android.util.Log.e("GeminiVision", "Error inicializando SDK: ${e.message}")
        }
    }
}
