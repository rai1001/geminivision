import http from 'http';
import { WebSocketServer, WebSocket } from 'ws';
import { v4 as uuidv4 } from 'uuid';
import { generateToken, validateToken } from './auth.js';
import { GeminiSession } from './geminiSession.js';
import dotenv from 'dotenv';

dotenv.config();

const PORT = Number(process.env.PORT) || 3000;

// --- HTTP Server (health check + token endpoint) ---

const httpServer = http.createServer((req, res) => {
  // CORS headers
  res.setHeader('Access-Control-Allow-Origin', process.env.ALLOWED_ORIGINS || '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type');

  if (req.method === 'OPTIONS') {
    res.writeHead(204);
    res.end();
    return;
  }

  // Health check
  if (req.method === 'GET' && req.url === '/health') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ status: 'ok', timestamp: new Date().toISOString() }));
    return;
  }

  // Token endpoint para MVP (en producción usar auth real)
  if (req.method === 'POST' && req.url === '/api/token') {
    let body = '';
    req.on('data', chunk => body += chunk);
    req.on('end', () => {
      try {
        const { clientId } = JSON.parse(body);
        if (!clientId) {
          res.writeHead(400, { 'Content-Type': 'application/json' });
          res.end(JSON.stringify({ error: 'clientId requerido' }));
          return;
        }
        const token = generateToken(clientId);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ token }));
      } catch {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ error: 'JSON inválido' }));
      }
    });
    return;
  }

  res.writeHead(404);
  res.end('Not found');
});

// --- WebSocket Server ---

const wss = new WebSocketServer({ server: httpServer });

// Heartbeat para detectar conexiones muertas
const HEARTBEAT_INTERVAL = 30_000;

interface ClientState {
  sessionId: string;
  authenticated: boolean;
  geminiSession: GeminiSession | null;
  alive: boolean;
}

const clients = new Map<WebSocket, ClientState>();

wss.on('connection', (ws) => {
  const state: ClientState = {
    sessionId: uuidv4(),
    authenticated: false,
    geminiSession: null,
    alive: true,
  };
  clients.set(ws, state);
  console.log(`[Server] Nueva conexión: ${state.sessionId}`);

  ws.on('pong', () => {
    state.alive = true;
  });

  ws.on('message', (data) => {
    let msg: any;
    try {
      msg = JSON.parse(data.toString());
    } catch {
      sendError(ws, 'PARSE_ERROR', 'Mensaje JSON inválido');
      return;
    }

    handleMessage(ws, state, msg);
  });

  ws.on('close', () => {
    console.log(`[Server] Conexión cerrada: ${state.sessionId}`);
    state.geminiSession?.close();
    clients.delete(ws);
  });

  ws.on('error', (err) => {
    console.error(`[Server] Error en conexión ${state.sessionId}:`, err.message);
  });
});

function handleMessage(ws: WebSocket, state: ClientState, msg: any): void {
  // Auth debe ser el primer mensaje
  if (msg.type === 'auth') {
    const payload = validateToken(msg.token);
    if (!payload) {
      sendError(ws, 'AUTH_FAILED', 'Token inválido');
      ws.close(4001, 'Unauthorized');
      return;
    }
    state.authenticated = true;
    ws.send(JSON.stringify({
      type: 'authenticated',
      sessionId: state.sessionId,
    }));
    console.log(`[Server] Autenticado: ${state.sessionId} (client: ${payload.clientId})`);
    return;
  }

  // Todo lo demás requiere autenticación
  if (!state.authenticated) {
    sendError(ws, 'NOT_AUTHENTICATED', 'Envía auth primero');
    return;
  }

  switch (msg.type) {
    case 'startSession':
      startGeminiSession(ws, state, msg.config);
      break;

    case 'audio':
      if (state.geminiSession) {
        state.geminiSession.sendAudio(msg.payload);
      }
      break;

    case 'video':
      if (state.geminiSession) {
        state.geminiSession.sendFrame(msg.payload);
      }
      break;

    case 'endSession':
      state.geminiSession?.close();
      state.geminiSession = null;
      console.log(`[Server] Sesión terminada: ${state.sessionId}`);
      break;

    default:
      sendError(ws, 'UNKNOWN_TYPE', `Tipo de mensaje desconocido: ${msg.type}`);
  }
}

async function startGeminiSession(ws: WebSocket, state: ClientState, config?: any): Promise<void> {
  // Cerrar sesión anterior si existe
  state.geminiSession?.close();

  const session = new GeminiSession(config);
  state.geminiSession = session;

  // Reenviar eventos de Gemini al cliente Android
  session.on('audio', (base64Pcm: string) => {
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: 'audio', payload: base64Pcm }));
    }
  });

  session.on('transcript', (source: string, text: string) => {
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: 'transcript', source, text }));
    }
  });

  session.on('turnComplete', () => {
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: 'turnComplete' }));
    }
  });

  session.on('error', (code: string, message: string) => {
    sendError(ws, code, message);
  });

  session.on('sessionExpiring', (secondsRemaining: number) => {
    if (ws.readyState === WebSocket.OPEN) {
      ws.send(JSON.stringify({ type: 'sessionExpiring', secondsRemaining }));
    }
  });

  session.on('ready', () => {
    console.log(`[Server] Sesión Gemini lista para: ${state.sessionId}`);
  });

  session.on('closed', () => {
    console.log(`[Server] Sesión Gemini cerrada para: ${state.sessionId}`);
  });

  console.log(`[Server] Iniciando sesión Gemini para: ${state.sessionId}`);
  await session.connect();
}

function sendError(ws: WebSocket, code: string, message: string): void {
  if (ws.readyState === WebSocket.OPEN) {
    ws.send(JSON.stringify({ type: 'error', code, message }));
  }
}

// Heartbeat interval
const heartbeat = setInterval(() => {
  for (const [ws, state] of clients) {
    if (!state.alive) {
      console.log(`[Server] Conexión muerta, terminando: ${state.sessionId}`);
      state.geminiSession?.close();
      ws.terminate();
      clients.delete(ws);
      continue;
    }
    state.alive = false;
    ws.ping();
  }
}, HEARTBEAT_INTERVAL);

wss.on('close', () => {
  clearInterval(heartbeat);
});

// --- Start ---

httpServer.listen(PORT, () => {
  console.log(`[GeminiVision Backend] Escuchando en puerto ${PORT}`);
  console.log(`[GeminiVision Backend] Health check: http://localhost:${PORT}/health`);
  console.log(`[GeminiVision Backend] WebSocket: ws://localhost:${PORT}`);
});
