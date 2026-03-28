import WebSocket from 'ws';

/**
 * Envía un chunk de audio PCM al WebSocket de Gemini.
 * Input: base64 PCM 16-bit, 16kHz, mono, little-endian
 */
export function sendAudioToGemini(geminiWs: WebSocket, base64Pcm: string): void {
  if (geminiWs.readyState !== WebSocket.OPEN) return;

  geminiWs.send(JSON.stringify({
    realtimeInput: {
      mediaChunks: [{
        mimeType: 'audio/pcm;rate=16000',
        data: base64Pcm,
      }],
    },
  }));
}

/**
 * Extrae audio PCM base64 de una respuesta de Gemini.
 * Output de Gemini: PCM raw, 24kHz
 * Retorna null si no hay audio en este mensaje.
 */
export function extractAudioFromGemini(response: any): string | null {
  const parts = response?.serverContent?.modelTurn?.parts;
  if (!parts) return null;

  for (const part of parts) {
    if (part.inlineData?.mimeType?.startsWith('audio/')) {
      return part.inlineData.data; // base64 PCM 24kHz
    }
  }

  return null;
}
