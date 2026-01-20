/**
 * Web Audio Service
 *
 * Handles audio recording and playback for the web UI using:
 * - MediaRecorder API for recording with Ogg Opus encoding
 * - HTML5 Audio element for playback
 * - Microphone access with echo cancellation and noise suppression
 *
 * Phase 3 implementation notes:
 * - Uses browser's native MediaRecorder with Ogg Opus (Chrome/Firefox support)
 * - Falls back to WebM Opus or MP4 if Ogg Opus is not available
 * - For consistent cross-browser encoding, future implementation could use Opus WASM
 */

import { Buffer } from 'buffer';

export interface RecordingResult {
  buffer: Buffer;
  mimeType: string;
  duration: number;
  size: number;
}

export type PlaybackState = 'idle' | 'playing' | 'paused' | 'ended';

export interface PlaybackOptions {
  onEnded?: () => void;
  onError?: (error: Error) => void;
  onTimeUpdate?: (currentTime: number) => void;
}

export class WebAudioService {
  private mediaRecorder: MediaRecorder | null = null;
  private recordedChunks: Blob[] = [];
  private startTime: number = 0;
  private stream: MediaStream | null = null;

  // Playback state
  private currentAudio: HTMLAudioElement | null = null;
  private playbackState: PlaybackState = 'idle';
  private playbackCallbacks: PlaybackOptions = {};

  /**
   * Start recording audio from the microphone
   * Requests microphone access and starts MediaRecorder with Ogg Opus if available
   */
  async startRecording(): Promise<void> {
    if (this.mediaRecorder?.state === 'recording') {
      throw new Error('Already recording');
    }

    try {
      // Get microphone access with voice-optimized settings
      this.stream = await navigator.mediaDevices.getUserMedia({
        audio: {
          sampleRate: 16000,
          channelCount: 1,
          echoCancellation: true,
          noiseSuppression: true,
          autoGainControl: true,
        },
      });

      // Create MediaRecorder with best supported MIME type
      const mimeType = this.getSupportedMimeType();
      this.mediaRecorder = new MediaRecorder(this.stream, { mimeType });

      this.recordedChunks = [];
      this.startTime = Date.now();

      this.mediaRecorder.ondataavailable = event => {
        if (event.data.size > 0) {
          this.recordedChunks.push(event.data);
        }
      };

      // Collect data every 100ms for lower latency
      this.mediaRecorder.start(100);
    } catch (error) {
      if (error instanceof DOMException && error.name === 'NotAllowedError') {
        throw new Error(
          'Microphone access denied. Please allow microphone access to record voice messages.',
        );
      }
      throw new Error(
        `Failed to access microphone: ${error instanceof Error ? error.message : 'Unknown error'}`,
      );
    }
  }

  /**
   * Stop recording and return the audio data
   * Returns a Buffer with the audio data for Matrix upload
   */
  async stopRecording(): Promise<RecordingResult> {
    return new Promise((resolve, reject) => {
      if (!this.mediaRecorder || this.mediaRecorder.state !== 'recording') {
        reject(new Error('No recording in progress'));
        return;
      }

      this.mediaRecorder.onstop = async () => {
        try {
          const blob = new Blob(this.recordedChunks, {
            type: this.mediaRecorder?.mimeType || 'audio/webm',
          });

          // Clean up stream
          this.stream?.getTracks().forEach(track => track.stop());
          this.stream = null;

          const arrayBuffer = await blob.arrayBuffer();
          const buffer = Buffer.from(arrayBuffer);
          const duration = (Date.now() - this.startTime) / 1000; // Convert to seconds

          resolve({
            buffer,
            mimeType: blob.type,
            duration,
            size: blob.size,
          });
        } catch (error) {
          reject(
            new Error(
              `Failed to process recording: ${error instanceof Error ? error.message : 'Unknown error'}`,
            ),
          );
        }
      };

      this.mediaRecorder.stop();
    });
  }

  /**
   * Cancel the current recording without saving
   */
  cancelRecording(): void {
    if (this.mediaRecorder && this.mediaRecorder.state === 'recording') {
      this.mediaRecorder.onstop = () => {
        // Clean up without resolving the promise
        this.stream?.getTracks().forEach(track => track.stop());
        this.stream = null;
        this.recordedChunks = [];
      };
      this.mediaRecorder.stop();
    } else {
      // If not recording, just clean up
      this.stream?.getTracks().forEach(track => track.stop());
      this.stream = null;
      this.recordedChunks = [];
    }
  }

  /**
   * Get the best supported MIME type for audio recording
   * Prioritizes Ogg Opus for Matrix compatibility, falls back to WebM or MP4
   */
  private getSupportedMimeType(): string {
    const types = [
      'audio/ogg;codecs=opus', // Preferred: Ogg Opus (Matrix standard)
      'audio/webm;codecs=opus', // WebM Opus (Chrome/Firefox)
      'audio/ogg', // Generic Ogg
      'audio/webm', // Generic WebM
      'audio/mp4', // MP4 with AAC (Safari fallback)
    ];

    for (const type of types) {
      if (MediaRecorder.isTypeSupported(type)) {
        return type;
      }
    }

    return 'audio/webm'; // Ultimate fallback
  }

  /**
   * Check if recording is currently in progress
   */
  isRecording(): boolean {
    return this.mediaRecorder?.state === 'recording';
  }

  /**
   * Play audio from a URL
   * Returns a promise that resolves when playback starts
   */
  async playAudio(url: string, options: PlaybackOptions = {}): Promise<void> {
    // Stop any existing playback
    this.stopAudio();

    // Store callbacks for this playback session
    this.playbackCallbacks = options;

    const audio = new Audio(url);

    // Set up event listeners
    audio.addEventListener('ended', () => {
      this.playbackState = 'ended';
      this.playbackCallbacks.onEnded?.();
    });

    audio.addEventListener('error', e => {
      this.playbackState = 'idle';
      const error = new Error(`Audio playback failed: ${e}`);
      this.playbackCallbacks.onError?.(error);
    });

    audio.addEventListener('timeupdate', () => {
      this.playbackCallbacks.onTimeUpdate?.(audio.currentTime);
    });

    this.currentAudio = audio;
    this.playbackState = 'playing';

    try {
      await audio.play();
    } catch (error) {
      this.playbackState = 'idle';
      throw new Error(
        `Failed to play audio: ${error instanceof Error ? error.message : 'Unknown error'}`,
      );
    }
  }

  /**
   * Pause the currently playing audio
   */
  pauseAudio(): void {
    if (this.currentAudio && this.playbackState === 'playing') {
      this.currentAudio.pause();
      this.playbackState = 'paused';
    }
  }

  /**
   * Resume paused audio
   */
  resumeAudio(): void {
    if (this.currentAudio && this.playbackState === 'paused') {
      this.currentAudio
        .play()
        .then(() => {
          this.playbackState = 'playing';
        })
        .catch(error => {
          console.error('[WebAudioService] Failed to resume audio:', error);
        });
    }
  }

  /**
   * Stop audio playback and reset state
   */
  stopAudio(): void {
    if (this.currentAudio) {
      this.currentAudio.pause();
      this.currentAudio.currentTime = 0;
      this.currentAudio = null;
    }
    this.playbackState = 'idle';
    this.playbackCallbacks = {};
  }

  /**
   * Get the current playback state
   */
  getPlaybackState(): PlaybackState {
    return this.playbackState;
  }

  /**
   * Get the current playback time in seconds
   */
  getCurrentTime(): number {
    return this.currentAudio?.currentTime ?? 0;
  }

  /**
   * Get the total duration of the currently loaded audio in seconds
   */
  getDuration(): number {
    return this.currentAudio?.duration ?? 0;
  }

  /**
   * Seek to a specific time in the audio (in seconds)
   */
  seekTo(time: number): void {
    if (this.currentAudio) {
      this.currentAudio.currentTime = Math.max(
        0,
        Math.min(time, this.currentAudio.duration),
      );
    }
  }

  /**
   * Set the volume for playback (0.0 to 1.0)
   */
  setVolume(volume: number): void {
    if (this.currentAudio) {
      this.currentAudio.volume = Math.max(0, Math.min(1, volume));
    }
  }
}

// Singleton instance
export const webAudioService = new WebAudioService();
