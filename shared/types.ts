// ============================================
// GeminiVision — Protocolo WebSocket compartido
// ============================================

// --- Mensajes Cliente → Backend ---

export interface AuthMessage {
  type: 'auth';
  token: string;
}

export interface AudioMessage {
  type: 'audio';
  payload: string; // base64 PCM 16-bit, 16kHz, mono
}

export interface VideoMessage {
  type: 'video';
  payload: string; // base64 JPEG frame
}

export interface StartSessionMessage {
  type: 'startSession';
  config?: SessionConfig;
}

export interface EndSessionMessage {
  type: 'endSession';
}

export type ClientMessage =
  | AuthMessage
  | AudioMessage
  | VideoMessage
  | StartSessionMessage
  | EndSessionMessage;

// --- Mensajes Backend → Cliente ---

export interface AuthenticatedMessage {
  type: 'authenticated';
  sessionId: string;
}

export interface AudioResponseMessage {
  type: 'audio';
  payload: string; // base64 PCM 24kHz
}

export interface TranscriptMessage {
  type: 'transcript';
  source: 'user' | 'model';
  text: string;
}

export interface TurnCompleteMessage {
  type: 'turnComplete';
}

export interface ErrorMessage {
  type: 'error';
  code: string;
  message: string;
}

export interface SessionExpiringMessage {
  type: 'sessionExpiring';
  secondsRemaining: number;
}

export type ServerMessage =
  | AuthenticatedMessage
  | AudioResponseMessage
  | TranscriptMessage
  | TurnCompleteMessage
  | ErrorMessage
  | SessionExpiringMessage;

// --- Configuración ---

export interface SessionConfig {
  systemInstruction?: string;
  voice?: string;
  useVideo?: boolean;
}

// --- Gemini API types (subset relevante) ---

export interface GeminiSetupMessage {
  setup: {
    model: string;
    generationConfig: {
      responseModalities: string[];
      speechConfig?: {
        voiceConfig: {
          prebuiltVoiceConfig: { voiceName: string };
        };
      };
    };
    systemInstruction?: {
      parts: Array<{ text: string }>;
    };
    tools?: Array<{
      functionDeclarations: GeminiFunctionDeclaration[];
    }>;
  };
}

export interface GeminiFunctionDeclaration {
  name: string;
  description: string;
  parameters: {
    type: string;
    properties: Record<string, { type: string; description: string }>;
    required?: string[];
  };
}

export interface GeminiRealtimeInput {
  realtimeInput: {
    mediaChunks: Array<{
      mimeType: string;
      data: string; // base64
    }>;
  };
}

export interface GeminiFunctionCall {
  id: string;
  name: string;
  args: Record<string, unknown>;
}

export interface GeminiFunctionResponse {
  functionResponses: Array<{
    id: string;
    response: Record<string, unknown>;
  }>;
}
