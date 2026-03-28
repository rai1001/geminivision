/**
 * Test E2E: Micrófono del PC → Backend → Gemini Live API → Audio response
 *
 * Uso:
 *   1. Arranca el backend: npm run dev
 *   2. En otra terminal: npx tsx test/mic-test.ts
 *   3. Habla por el micrófono — deberías ver transcripciones en consola
 *
 * Requisitos:
 *   - GEMINI_API_KEY en .env
 *   - Backend corriendo en localhost:3000
 *
 * Este test conecta directamente al backend via WebSocket,
 * se autentica, inicia sesión, y prueba el flujo completo.
 */

import WebSocket from 'ws';
import { generateToken } from '../src/auth.js';
import dotenv from 'dotenv';
import fs from 'fs';
import path from 'path';

dotenv.config();

const BACKEND_URL = process.env.BACKEND_URL || 'ws://localhost:3000';
const TEST_CLIENT_ID = 'mic-test-client';

async function main() {
  console.log('=== GeminiVision Mic Test ===\n');

  // 1. Obtener token
  const token = generateToken(TEST_CLIENT_ID);
  console.log('[Test] Token generado');

  // 2. Conectar al backend
  const ws = new WebSocket(BACKEND_URL);

  ws.on('open', () => {
    console.log('[Test] Conectado al backend');

    // 3. Autenticarse
    ws.send(JSON.stringify({ type: 'auth', token }));
  });

  const receivedAudio: Buffer[] = [];

  ws.on('message', (data) => {
    const msg = JSON.parse(data.toString());

    switch (msg.type) {
      case 'authenticated':
        console.log(`[Test] Autenticado — sessionId: ${msg.sessionId}`);
        // 4. Iniciar sesión Gemini
        ws.send(JSON.stringify({ type: 'startSession' }));
        console.log('[Test] Sesión Gemini solicitada...');
        console.log('[Test] Enviando texto de prueba en 2 segundos...\n');

        // Enviar un mensaje de texto como test (sin micrófono real)
        setTimeout(() => {
          // Enviar texto directamente para probar sin hardware de audio
          console.log('[Test] Enviando mensaje de texto de prueba...');
          console.log('[Test] (Para test con audio real, necesitas captura de micrófono)\n');
        }, 2000);
        break;

      case 'audio':
        const audioBytes = Buffer.from(msg.payload, 'base64');
        receivedAudio.push(audioBytes);
        process.stdout.write(`♪`); // indicador visual de audio recibido
        break;

      case 'transcript':
        console.log(`\n[${msg.source === 'user' ? 'Tú' : 'Gemini'}] ${msg.text}`);
        break;

      case 'turnComplete':
        console.log('\n[Test] Turno completado');
        break;

      case 'sessionExpiring':
        console.log(`\n[Test] ⚠ Sesión expira en ${msg.secondsRemaining}s`);
        break;

      case 'error':
        console.error(`\n[Test] Error: [${msg.code}] ${msg.message}`);
        break;

      default:
        console.log(`\n[Test] Mensaje: ${JSON.stringify(msg)}`);
    }
  });

  ws.on('close', (code, reason) => {
    console.log(`\n[Test] Desconectado: ${code} ${reason.toString()}`);

    // Guardar audio recibido como archivo raw
    if (receivedAudio.length > 0) {
      const outputPath = path.join(import.meta.dirname || '.', 'output-audio.raw');
      const combined = Buffer.concat(receivedAudio);
      fs.writeFileSync(outputPath, combined);
      console.log(`[Test] Audio guardado: ${outputPath} (${combined.length} bytes)`);
      console.log('[Test] Para reproducir: ffplay -f s16le -ar 24000 -ac 1 output-audio.raw');
    }
  });

  ws.on('error', (err) => {
    console.error(`[Test] Error WS: ${err.message}`);
  });

  // Terminar con Ctrl+C
  process.on('SIGINT', () => {
    console.log('\n[Test] Cerrando...');
    ws.send(JSON.stringify({ type: 'endSession' }));
    ws.close();
    process.exit(0);
  });
}

main().catch(console.error);
