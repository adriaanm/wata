/**
 * Web Audio Service
 *
 * Handles audio recording and playback for the web UI using:
 * - MediaRecorder API for recording with Ogg Opus encoding
 * - Web Audio API + @shared/lib/audio-codec for Ogg Opus playback
 * - Microphone access with echo cancellation and noise suppression
 *
 * Playback uses decodeOggOpus from @shared/lib/audio-codec which:
 * - Decodes Ogg Opus to PCM using @evan/wasm opus (WASM)
 * - Works across all browsers including Safari
 */

import { Buffer } from 'buffer';
import { decodeOggOpus } from '@shared/lib/audio-codec';

export interface RecordingResult {
  data: Uint8Array;
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

  // Playback state using Web Audio API
  private audioContext: AudioContext | null = null;
  private audioBuffer: AudioBuffer | null = null;
  private sourceNode: AudioBufferSourceNode | null = null;
  private gainNode: GainNode | null = null;
  private playbackState: PlaybackState = 'idle';
  private playbackCallbacks: PlaybackOptions = {};
  private playbackStartTime: number = 0;
  private playbackOffset: number = 0; // Current position in seconds
  private playbackDuration: number = 0; // Total duration in seconds
  private timeUpdateInterval: number | null = null;

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
   * Returns a Uint8Array with the audio data for Matrix upload
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
          const data = new Uint8Array(arrayBuffer);
          const duration = (Date.now() - this.startTime) / 1000; // Convert to seconds

          resolve({
            data,
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
   * Get or create AudioContext
   */
  private getAudioContext(): AudioContext {
    if (!this.audioContext) {
      this.audioContext = new AudioContext({ sampleRate: 16000 });
    }
    // Resume context if suspended (browser autoplay policy)
    if (this.audioContext.state === 'suspended') {
      this.audioContext.resume();
    }
    return this.audioContext;
  }

  /**
   * Stop time update interval
   */
  private stopTimeUpdate(): void {
    if (this.timeUpdateInterval !== null) {
      clearInterval(this.timeUpdateInterval);
      this.timeUpdateInterval = null;
    }
  }

  /**
   * Start time update interval
   */
  private startTimeUpdate(): void {
    this.stopTimeUpdate();
    this.timeUpdateInterval = window.setInterval(() => {
      if (this.playbackState === 'playing') {
        const currentTime = this.playbackOffset + (performance.now() - this.playbackStartTime) / 1000;
        this.playbackCallbacks.onTimeUpdate?.(Math.min(currentTime, this.playbackDuration));
      }
    }, 100); // Update every 100ms
  }

  /**
   * Play audio from a URL
   * Uses decodeOggOpus from @shared/lib/audio-codec for Ogg Opus decoding
   * Works across all browsers including Safari
   */
  async playAudio(url: string, options: PlaybackOptions = {}): Promise<void> {
    // Stop any existing playback
    this.stopAudio();

    // Store callbacks for this playback session
    this.playbackCallbacks = options;

    try {
      // Fetch the audio data
      const response = await fetch(url);
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`);
      }
      const arrayBuffer = await response.arrayBuffer();

      // Decode Ogg Opus to PCM using @shared/lib/audio-codec
      const result = decodeOggOpus(Buffer.from(arrayBuffer));

      // Convert Int16Array to Float32Array for Web Audio API
      const float32 = new Float32Array(result.pcm.length);
      for (let i = 0; i < result.pcm.length; i++) {
        float32[i] = result.pcm[i] / 32768;
      }

      // Get or create AudioContext
      const ctx = this.getAudioContext();

      // Create AudioBuffer
      this.audioBuffer = ctx.createBuffer(1, float32.length, result.sampleRate);
      this.audioBuffer.copyToChannel(float32, 0);

      // Store duration
      this.playbackDuration = result.duration;
      this.playbackOffset = 0;

      // Create gain node for volume control
      this.gainNode = ctx.createGain();
      this.gainNode.connect(ctx.destination);

      // Create source node and start playback
      this.sourceNode = ctx.createBufferSource();
      this.sourceNode.buffer = this.audioBuffer;
      this.sourceNode.connect(this.gainNode);

      // Set up ended event
      this.sourceNode.onended = () => {
        if (this.playbackState === 'playing') {
          // Only call onEnded if we weren't manually stopped
          const playedDuration = (performance.now() - this.playbackStartTime) / 1000;
          // Check if we played to the end (within 100ms)
          if (this.playbackDuration - playedDuration < 0.1) {
            this.playbackState = 'ended';
            this.playbackCallbacks.onEnded?.();
            this.stopTimeUpdate();
          }
        }
      };

      // Start playback
      this.playbackState = 'playing';
      this.playbackStartTime = performance.now();
      this.startTimeUpdate();
      this.sourceNode.start(0, this.playbackOffset);
    } catch (error) {
      this.playbackState = 'idle';
      this.stopTimeUpdate();
      throw new Error(
        `Failed to play audio: ${error instanceof Error ? error.message : 'Unknown error'}`,
      );
    }
  }

  /**
   * Pause the currently playing audio
   */
  pauseAudio(): void {
    if (this.playbackState === 'playing' && this.sourceNode) {
      // Calculate current position
      this.playbackOffset = (performance.now() - this.playbackStartTime) / 1000;
      this.sourceNode.stop();
      this.sourceNode = null;
      this.playbackState = 'paused';
      this.stopTimeUpdate();
    }
  }

  /**
   * Resume paused audio
   */
  resumeAudio(): void {
    if (this.playbackState === 'paused' && this.audioBuffer) {
      const ctx = this.getAudioContext();

      // Create new source node (they can't be reused)
      this.sourceNode = ctx.createBufferSource();
      this.sourceNode.buffer = this.audioBuffer;
      this.sourceNode.connect(this.gainNode!);

      // Set up ended event
      this.sourceNode.onended = () => {
        if (this.playbackState === 'playing') {
          const playedDuration = (performance.now() - this.playbackStartTime) / 1000;
          if (this.playbackDuration - playedDuration < 0.1) {
            this.playbackState = 'ended';
            this.playbackCallbacks.onEnded?.();
            this.stopTimeUpdate();
          }
        }
      };

      // Resume from offset
      this.playbackState = 'playing';
      this.playbackStartTime = performance.now();
      this.startTimeUpdate();
      this.sourceNode.start(0, this.playbackOffset);
    }
  }

  /**
   * Stop audio playback and reset state
   */
  stopAudio(): void {
    if (this.sourceNode) {
      try {
        this.sourceNode.stop();
      } catch {
        // Already stopped
      }
      this.sourceNode = null;
    }
    this.playbackState = 'idle';
    this.playbackOffset = 0;
    this.playbackDuration = 0;
    this.audioBuffer = null;
    this.stopTimeUpdate();
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
    if (this.playbackState === 'playing') {
      return Math.min(
        this.playbackOffset + (performance.now() - this.playbackStartTime) / 1000,
        this.playbackDuration,
      );
    }
    return this.playbackOffset;
  }

  /**
   * Get the total duration of the currently loaded audio in seconds
   */
  getDuration(): number {
    return this.playbackDuration;
  }

  /**
   * Seek to a specific time in the audio (in seconds)
   */
  seekTo(time: number): void {
    const wasPlaying = this.playbackState === 'playing';
    const newTime = Math.max(0, Math.min(time, this.playbackDuration));

    // Stop current playback
    if (this.sourceNode) {
      try {
        this.sourceNode.stop();
      } catch {
        // Already stopped
      }
      this.sourceNode = null;
    }

    this.playbackOffset = newTime;
    this.stopTimeUpdate();

    // Resume if we were playing
    if (wasPlaying && this.audioBuffer) {
      const ctx = this.getAudioContext();

      this.sourceNode = ctx.createBufferSource();
      this.sourceNode.buffer = this.audioBuffer;
      this.sourceNode.connect(this.gainNode!);

      this.sourceNode.onended = () => {
        if (this.playbackState === 'playing') {
          const playedDuration = (performance.now() - this.playbackStartTime) / 1000;
          if (this.playbackDuration - playedDuration < 0.1) {
            this.playbackState = 'ended';
            this.playbackCallbacks.onEnded?.();
            this.stopTimeUpdate();
          }
        }
      };

      this.playbackState = 'playing';
      this.playbackStartTime = performance.now();
      this.startTimeUpdate();
      this.sourceNode.start(0, this.playbackOffset);
    } else if (this.playbackState === 'paused') {
      // Just update offset, don't start
      this.playbackCallbacks.onTimeUpdate?.(newTime);
    }
  }

  /**
   * Set the volume for playback (0.0 to 1.0)
   */
  setVolume(volume: number): void {
    const clampedVolume = Math.max(0, Math.min(1, volume));
    if (this.gainNode) {
      this.gainNode.gain.value = clampedVolume;
    }
  }
}

// Singleton instance
export const webAudioService = new WebAudioService();
