# GeminiVision — Context para Claude Code

## Que es este proyecto
App Android que conecta Ray-Ban Meta Gen 2 con Gemini Live API.
Reemplaza Meta AI con Gemini usando speech-to-speech nativo.
Sin STT->LLM->TTS. Audio PCM directo. Latencia ~300-500ms.

## Stack
- Android app: Kotlin + Coroutines + OkHttp WebSocket + Jetpack Compose
- Meta SDK: mwdat v0.5.0 (camera via Bluetooth, mic via BT SCO estandar)
- Backend: Node.js 20+ + TypeScript + ws library
- AI: Gemini Live API (speech-to-speech nativo)
- Deploy: Railway.app (WebSocket nativo)

## Arquitectura
```
Ray-Ban Meta -> (BT) -> Android App -> (WebSocket) -> Backend Node.js -> (WebSocket) -> Gemini Live API
                                                                                           |
Ray-Ban Meta <- (BT Audio) <- Android App <- (WebSocket) <- Backend Node.js <- (WebSocket) <- Audio
```

## Audio specs
- Input al backend: PCM 16-bit, 16kHz, mono, little-endian, base64
- Output de Gemini: PCM raw, 24kHz (player debe manejar esta tasa)
- Chunks recomendados: ~100ms = 3200 bytes (16kHz/16-bit/mono)
- Video: JPEG frames, 1fps suficiente para Gemini

## Modelo Gemini
- Model string: verificar disponibilidad (spec dice gemini-3.1-flash-live-preview)
- Endpoint: wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent
- response_modalities: ['AUDIO'] — nunca TEXT para este caso
- VAD automatico activado
- Barge-in soportado

## Limites de sesion
- Audio-only: ~15 minutos
- Audio+Video: posiblemente ~2 minutos
- Session resumption es CRITICO — implementar desde el inicio
- Guardar sessionHandle y reconectar transparentemente

## Cosas importantes
- Meta AI app DEBE estar instalada (bridge BT obligatorio)
- El microfono de las gafas NO viene por el DAT SDK — se accede via Bluetooth SCO estandar
- GlassesService es FOREGROUND SERVICE (pantalla apagada OK)
- MockDeviceKit disponible para desarrollar sin gafas fisicas
- GITHUB_TOKEN en local.properties para descargar el SDK de Meta
- API key de Gemini NUNCA en el cliente Android, solo en backend
- JWT efimero para auth cliente->backend

## Fase 1 MVP
Asistente visual universal sin function calling.
Solo audio in + video in -> audio out.
Backend Node.js como proxy seguro.

## Fase 2
Function calling con Supabase CulinaryOS:
- get_fifo_stock(ingredient)
- validate_delivery_note(ocr_text, supplier)
- get_allergens(dish_name)

## Comandos
- Backend dev: `cd backend && npm run dev`
- Backend build: `cd backend && npm run build`
- Backend test: `cd backend && npm test`
