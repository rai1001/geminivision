import WebSocket from 'ws';

const MIN_FRAME_INTERVAL_MS = 1000; // 1fps max

export class VideoHandler {
  private lastFrameTime = 0;

  /**
   * Envía un frame JPEG a Gemini con rate limiting (max 1fps).
   * Descarta frames que llegan antes de MIN_FRAME_INTERVAL_MS.
   * Retorna true si el frame fue enviado, false si fue descartado.
   */
  sendFrame(geminiWs: WebSocket, base64Jpeg: string): boolean {
    if (geminiWs.readyState !== WebSocket.OPEN) return false;

    const now = Date.now();
    if (now - this.lastFrameTime < MIN_FRAME_INTERVAL_MS) {
      return false; // rate limited
    }

    this.lastFrameTime = now;

    geminiWs.send(JSON.stringify({
      realtimeInput: {
        mediaChunks: [{
          mimeType: 'image/jpeg',
          data: base64Jpeg,
        }],
      },
    }));

    return true;
  }

  reset(): void {
    this.lastFrameTime = 0;
  }
}
