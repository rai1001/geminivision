import WebSocket from 'ws';
import { EventEmitter } from 'events';
import { sendAudioToGemini, extractAudioFromGemini } from './audioHandler.js';
import { VideoHandler } from './videoHandler.js';
import { handleFunctionCall } from './functionCalling.js';
import { getMode, type AssistantMode } from './modes.js';

export interface SessionConfig {
  systemInstruction?: string;
  voice?: string;
  useVideo?: boolean;
  mode?: string; // ID del modo (standard, desktop, translation, kitchen, etc.)
  customPrompt?: string; // para modo 'custom'
}

// Gemini Live API endpoint
const GEMINI_WS_BASE =
  'wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent';

// Session limits
const AUDIO_ONLY_SESSION_MS = 14 * 60 * 1000;  // 14 min (margen sobre 15)
const VIDEO_SESSION_MS = 90 * 1000;              // 90s (margen sobre 2 min)
const SESSION_WARNING_RATIO = 0.7;               // avisar al 70%

const DEFAULT_VOICE = 'Puck';
const DEFAULT_SYSTEM_INSTRUCTION = `Eres un asistente visual que ve a través de las gafas del usuario.
Estás siempre presente pero respondes solo cuando te hablan.
Respuestas concisas y directas — máximo 2-3 frases salvo que pidan más detalle.
Si ves texto en otro idioma, tradúcelo directamente sin avisar.
Si ves un error de código, explica la causa y la solución.
Si ves un documento físico, describe lo relevante.
Idioma de respuesta: el mismo que use el usuario.`;

export interface GeminiSessionEvents {
  audio: (base64Pcm: string) => void;
  transcript: (source: 'user' | 'model', text: string) => void;
  turnComplete: () => void;
  error: (code: string, message: string) => void;
  sessionExpiring: (secondsRemaining: number) => void;
  ready: () => void;
  closed: () => void;
}

export class GeminiSession extends EventEmitter {
  private geminiWs: WebSocket | null = null;
  private videoHandler = new VideoHandler();
  private videoEnabled = false;
  private sessionHandle: string | null = null;
  private sessionTimer: ReturnType<typeof setTimeout> | null = null;
  private warningTimer: ReturnType<typeof setTimeout> | null = null;
  private audioBuffer: string[] = [];
  private isReconnecting = false;
  private isReady = false;
  private config: SessionConfig;
  private activeMode: AssistantMode;

  constructor(config?: SessionConfig) {
    super();
    this.config = config || {};
    this.activeMode = getMode(this.config.mode || 'standard');
    // Para modo custom, usar el prompt del usuario
    if (this.activeMode.id === 'custom' && this.config.customPrompt) {
      this.activeMode = { ...this.activeMode, systemPrompt: this.config.customPrompt };
    }
    console.log(`[GeminiSession] Modo: ${this.activeMode.name}`);
  }

  async connect(): Promise<void> {
    const apiKey = process.env.GEMINI_API_KEY;
    if (!apiKey) {
      this.emit('error', 'CONFIG_ERROR', 'GEMINI_API_KEY no configurada');
      return;
    }

    const url = `${GEMINI_WS_BASE}?key=${apiKey}`;
    this.geminiWs = new WebSocket(url);

    this.geminiWs.on('open', () => {
      console.log('[GeminiSession] WebSocket conectado, enviando setup...');
      this.sendSetup();
    });

    this.geminiWs.on('message', (data) => {
      this.handleGeminiMessage(data);
    });

    this.geminiWs.on('error', (err) => {
      console.error('[GeminiSession] WebSocket error:', err.message);
      this.emit('error', 'WS_ERROR', err.message);
    });

    this.geminiWs.on('close', (code, reason) => {
      console.log(`[GeminiSession] WebSocket cerrado: ${code} ${reason.toString()}`);
      if (!this.isReconnecting) {
        this.emit('closed');
      }
    });
  }

  private sendSetup(): void {
    if (!this.geminiWs || this.geminiWs.readyState !== WebSocket.OPEN) return;

    const voice = this.config.voice || this.activeMode.voice || DEFAULT_VOICE;
    const prompt = this.config.systemInstruction || this.activeMode.systemPrompt || DEFAULT_SYSTEM_INSTRUCTION;

    const setupMessage: any = {
      setup: {
        model: 'models/gemini-3.1-flash-live-preview',
        generationConfig: {
          responseModalities: ['AUDIO'],
          speechConfig: {
            voiceConfig: {
              prebuiltVoiceConfig: {
                voiceName: voice,
              },
            },
          },
        },
        systemInstruction: {
          parts: [{
            text: prompt,
          }],
        },
        tools: [], // Fase 2: function declarations aquí
      },
    };

    // Session resumption si tenemos handle previo
    if (this.sessionHandle) {
      setupMessage.setup.sessionResumption = {
        handle: this.sessionHandle,
      };
      console.log('[GeminiSession] Reconectando con sessionHandle...');
    }

    this.geminiWs.send(JSON.stringify(setupMessage));
  }

  private handleGeminiMessage(rawData: WebSocket.RawData): void {
    let msg: any;
    try {
      msg = JSON.parse(rawData.toString());
    } catch {
      console.error('[GeminiSession] Error parseando mensaje de Gemini');
      return;
    }

    // Setup complete
    if (msg.setupComplete) {
      console.log('[GeminiSession] Setup completado — sesión lista');
      this.isReady = true;
      this.startSessionTimer();
      this.emit('ready');

      // Reenviar audio buffereado durante reconexión
      if (this.audioBuffer.length > 0) {
        console.log(`[GeminiSession] Reenviando ${this.audioBuffer.length} chunks buffereados`);
        for (const chunk of this.audioBuffer) {
          this.sendAudio(chunk);
        }
        this.audioBuffer = [];
      }
      this.isReconnecting = false;
      return;
    }

    // Guardar session handle para resumption
    if (msg.sessionResumptionUpdate?.newHandle) {
      this.sessionHandle = msg.sessionResumptionUpdate.newHandle;
    }

    // Audio de respuesta del modelo
    const audio = extractAudioFromGemini(msg);
    if (audio) {
      this.emit('audio', audio);
    }

    // Turn complete
    if (msg.serverContent?.turnComplete) {
      this.emit('turnComplete');
    }

    // Transcripciones
    if (msg.serverContent?.outputTranscription?.text) {
      this.emit('transcript', 'model', msg.serverContent.outputTranscription.text);
    }
    if (msg.serverContent?.inputTranscription?.text) {
      this.emit('transcript', 'user', msg.serverContent.inputTranscription.text);
    }

    // Interrupted (barge-in)
    if (msg.serverContent?.interrupted) {
      console.log('[GeminiSession] Barge-in detectado');
    }

    // Function calling
    if (msg.toolCall?.functionCalls) {
      this.handleToolCalls(msg.toolCall.functionCalls);
    }
  }

  private async handleToolCalls(calls: Array<{ id: string; name: string; args: any }>): Promise<void> {
    const responses = await Promise.all(
      calls.map(call => handleFunctionCall({
        id: call.id,
        name: call.name,
        args: call.args || {},
      }))
    );

    if (this.geminiWs?.readyState === WebSocket.OPEN) {
      this.geminiWs.send(JSON.stringify({
        toolResponse: {
          functionResponses: responses.map(r => ({
            id: r.id,
            response: r.response,
          })),
        },
      }));
    }
  }

  private startSessionTimer(): void {
    this.clearTimers();

    const sessionDuration = this.videoEnabled
      ? VIDEO_SESSION_MS
      : AUDIO_ONLY_SESSION_MS;

    const warningAt = sessionDuration * SESSION_WARNING_RATIO;

    // Warning al 70%
    this.warningTimer = setTimeout(() => {
      const remaining = Math.round((sessionDuration - warningAt) / 1000);
      console.log(`[GeminiSession] Sesión expira en ${remaining}s`);
      this.emit('sessionExpiring', remaining);
    }, warningAt);

    // Reconexión automática al expirar
    this.sessionTimer = setTimeout(() => {
      console.log('[GeminiSession] Sesión expirada, reconectando...');
      this.reconnect();
    }, sessionDuration);
  }

  private clearTimers(): void {
    if (this.sessionTimer) clearTimeout(this.sessionTimer);
    if (this.warningTimer) clearTimeout(this.warningTimer);
    this.sessionTimer = null;
    this.warningTimer = null;
  }

  private async reconnect(): Promise<void> {
    if (!this.sessionHandle) {
      this.emit('error', 'NO_HANDLE', 'No hay sessionHandle para reconexión');
      return;
    }

    this.isReconnecting = true;
    this.isReady = false;

    // Cerrar conexión actual
    if (this.geminiWs) {
      this.geminiWs.removeAllListeners();
      this.geminiWs.close();
    }

    this.videoHandler.reset();
    this.clearTimers();

    console.log('[GeminiSession] Reconectando con session resumption...');
    await this.connect();
  }

  sendAudio(base64Pcm: string): void {
    // Bufferear si estamos en medio de reconexión
    if (this.isReconnecting || !this.isReady) {
      this.audioBuffer.push(base64Pcm);
      if (this.audioBuffer.length > 50) {
        this.audioBuffer.shift(); // descartar chunks viejos
      }
      return;
    }

    if (this.geminiWs) {
      sendAudioToGemini(this.geminiWs, base64Pcm);
    }
  }

  sendFrame(base64Jpeg: string): void {
    if (this.isReconnecting || !this.isReady || !this.geminiWs) return;

    // Primera vez que se envía video → activar timer corto
    if (!this.videoEnabled) {
      this.videoEnabled = true;
      this.startSessionTimer(); // reiniciar con timer de video (90s)
      console.log('[GeminiSession] Video activado — timer de sesión reducido');
    }

    this.videoHandler.sendFrame(this.geminiWs, base64Jpeg);
  }

  close(): void {
    this.clearTimers();
    this.isReady = false;
    this.isReconnecting = false;
    this.audioBuffer = [];

    if (this.geminiWs) {
      this.geminiWs.removeAllListeners();
      if (this.geminiWs.readyState === WebSocket.OPEN) {
        this.geminiWs.close();
      }
      this.geminiWs = null;
    }

    this.emit('closed');
  }

  get ready(): boolean {
    return this.isReady;
  }
}
