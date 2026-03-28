package com.geminivision

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.geminivision.service.GlassesService
import com.geminivision.service.ServiceState
import com.geminivision.service.TranscriptEntry
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private var glassesService: GlassesService? = null
    private var isBound = false

    private val serviceState = MutableStateFlow(ServiceState())
    private val transcripts = MutableStateFlow<List<TranscriptEntry>>(emptyList())

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as GlassesService.LocalBinder
            glassesService = binder.getService()
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            glassesService = null
            isBound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            startGlassesService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                val state by serviceState.collectAsState()
                val msgs by transcripts.collectAsState()

                GeminiVisionScreen(
                    state = state,
                    transcripts = msgs,
                    onConnect = { requestPermissionsAndStart() },
                    onDisconnect = { stopGlassesService() }
                )
            }
        }
    }

    private fun requestPermissionsAndStart() {
        val required = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.POST_NOTIFICATIONS
        )

        val needed = required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isEmpty()) {
            startGlassesService()
        } else {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    private fun startGlassesService() {
        val intent = Intent(this, GlassesService::class.java)
        startForegroundService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)

        // Obtener token del backend y arrancar sesión
        // MVP: token hardcodeado — en producción usar endpoint /api/token
        Thread {
            try {
                val token = fetchToken()
                glassesService?.startSession(token)

                // Observar estado del servicio
                glassesService?.let { service ->
                    // Conectar flows
                    Thread {
                        while (isBound) {
                            serviceState.value = service.state.value
                            transcripts.value = service.transcripts.value
                            Thread.sleep(100)
                        }
                    }.start()
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Error: ${e.message}")
            }
        }.start()
    }

    private fun fetchToken(): String {
        val url = BuildConfig.BACKEND_URL.replace("ws://", "http://").replace("wss://", "https://")
        val tokenUrl = "$url/api/token" // no existe en WS URL, construir HTTP
        // MVP: generar token simple
        // En producción, hacer POST a /api/token con clientId
        val connection = java.net.URL(
            "${url.replace("ws://", "http://").trimEnd('/')}/api/token"
                .replace("ws://", "http://")
        ).openConnection() as java.net.HttpURLConnection

        // Simplificación MVP — usar un token de desarrollo
        return "dev-token-placeholder"
    }

    private fun stopGlassesService() {
        glassesService?.stopSession()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
        stopService(Intent(this, GlassesService::class.java))
        serviceState.value = ServiceState()
        transcripts.value = emptyList()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
        }
    }
}

// --- Compose UI ---

@Composable
fun GeminiVisionScreen(
    state: ServiceState,
    transcripts: List<TranscriptEntry>,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit
) {
    val isActive = state.wsConnected && state.authenticated

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF121212)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            // Titulo
            Text(
                text = "GeminiVision",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Ray-Ban Meta + Gemini Live",
                fontSize = 14.sp,
                color = Color(0xFF888888)
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Estado de conexion
            StatusIndicators(state)

            Spacer(modifier = Modifier.height(24.dp))

            // Error
            state.lastError?.let { error ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF3D1111)),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = error,
                        color = Color(0xFFFF6B6B),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Transcripciones
            if (transcripts.isNotEmpty()) {
                TranscriptList(
                    transcripts = transcripts,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                )
            } else {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isActive) "Escuchando..." else "Pulsa Conectar para empezar",
                        color = Color(0xFF666666),
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // Boton principal
            Spacer(modifier = Modifier.height(24.dp))

            Button(
                onClick = { if (isActive) onDisconnect() else onConnect() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isActive) Color(0xFFE53935) else Color(0xFF4285F4)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    text = if (isActive) "Desconectar" else "Conectar",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun StatusIndicators(state: ServiceState) {
    Row(
        horizontalArrangement = Arrangement.SpaceEvenly,
        modifier = Modifier.fillMaxWidth()
    ) {
        StatusDot("WebSocket", state.wsConnected)
        StatusDot("Auth", state.authenticated)
        StatusDot("Gafas", state.glassesConnected)
        StatusDot("Audio", state.audioActive)
    }
}

@Composable
fun StatusDot(label: String, active: Boolean) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(if (active) Color(0xFF4CAF50) else Color(0xFF444444))
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = label,
            fontSize = 11.sp,
            color = Color(0xFF999999)
        )
    }
}

@Composable
fun TranscriptList(transcripts: List<TranscriptEntry>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()

    LaunchedEffect(transcripts.size) {
        if (transcripts.isNotEmpty()) {
            listState.animateScrollToItem(transcripts.size - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(transcripts) { entry ->
            val isUser = entry.source == "user"
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isUser) Color(0xFF1A237E) else Color(0xFF1B5E20)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = if (isUser) "Tu" else "Gemini",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isUser) Color(0xFF90CAF9) else Color(0xFFA5D6A7)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = entry.text,
                        fontSize = 14.sp,
                        color = Color.White
                    )
                }
            }
        }
    }
}
