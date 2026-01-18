/**
 * Web Audio Service Stub
 *
 * This is a placeholder for the full audio implementation coming in Phase 3.
 * Phase 3 will implement:
 * - AudioWorklet processor for recording in separate thread
 * - Opus WebAssembly encoder for consistent cross-browser encoding
 * - Ogg container muxing
 * - Web Audio API for playback
 *
 * For now, this stub allows the UI to function with mock recording feedback.
 */

export interface RecordingResult {
  buffer: ArrayBuffer;
  mimeType: string;
  duration: number;
  size: number;
}

export class WebAudioService {
  private mediaRecorder: MediaRecorder | null = null;
  private audioContext: AudioContext | null = null;
  private recordedChunks: Blob[] = [];
  private startTime: number = 0;
  private stream: MediaStream | null = null;

  /**
   * Start recording audio from the microphone
   * TODO: Phase 3 - Implement AudioWorklet-based recording
   */
  async startRecording(): Promise<void> {
    try {
      // Get microphone access
      this.stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          sampleRate: 16000,
          channelCount: 1,
          echoCancellation: true,
          noiseSuppression: true,
        },
      });

      // Create MediaRecorder (fallback, will be replaced with AudioWorklet in Phase 3)
      const mimeType = this.getSupportedMimeType();
      this.mediaRecorder = new MediaRecorder(this.stream, { mimeType });

      this.recordedChunks = [];
      this.startTime = Date.now();

      this.mediaRecorder.ondataavailable = (event) => {
        if (event.data.size > 0) {
          this.recordedChunks.push(event.data);
        }
      };

      this.mediaRecorder.start(100); // Collect data every 100ms
    } catch (error) {
      console.error('[WebAudioService] Failed to start recording:', error);
      throw new Error('Failed to access microphone');
    }
  }

  /**
   * Stop recording and return the audio data
   * TODO: Phase 3 - Return Opus-encoded Ogg container
   */
  async stopRecording(): Promise<RecordingResult> {
    return new Promise((resolve, reject) => {
      if (!this.mediaRecorder) {
        reject(new Error('No recording in progress'));
        return;
      }

      this.mediaRecorder.onstop = () => {
        const blob = new Blob(this.recordedChunks, {
          type: this.mediaRecorder?.mimeType || 'audio/webm',
        });

        // Clean up stream
        this.stream?.getTracks().forEach(track => track.stop());
        this.stream = null;

        blob.arrayBuffer().then(buffer => {
          const duration = Date.now() - this.startTime;
          resolve({
            buffer,
            mimeType: blob.type,
            duration: duration / 1000, // Convert to seconds
            size: blob.size,
          });
        });
      };

      this.mediaRecorder.stop();
    });
  }

  /**
   * Cancel the current recording
   */
  cancelRecording(): void {
    if (this.mediaRecorder && this.mediaRecorder.state === 'recording') {
      this.mediaRecorder.stop();
    }
    this.recordedChunks = [];
    this.stream?.getTracks().forEach(track => track.stop());
    this.stream = null;
  }

  /**
   * Get the best supported MIME type for audio recording
   * TODO: Phase 3 - Will always return 'audio/ogg;codecs=opus' after Opus WASM integration
   */
  private getSupportedMimeType(): string {
    const types = [
      'audio/ogg;codecs=opus',
      'audio/webm;codecs=opus',
      'audio/webm',
      'audio/mp4',
      'audio/mpeg',
    ];

    for (const type of types) {
      if (MediaRecorder.isTypeSupported(type)) {
        return type;
      }
    }

    return 'audio/webm'; // Fallback
  }

  /**
   * Play audio from a URL
   * TODO: Phase 3 - Implement with Web Audio API for better control
   */
  async playAudio(url: string): Promise<void> {
    const audio = new Audio(url);
    await audio.play();
  }

  /**
   * Stop audio playback
   * TODO: Phase 3 - Implement with Web Audio API
   */
  stopAudio(): void {
    // Will be implemented in Phase 3
  }
}

// Singleton instance
export const webAudioService = new WebAudioService();
