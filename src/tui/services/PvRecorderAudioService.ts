/// <reference types="node" />

import { PvRecorder } from '@picovoice/pvrecorder-node';
import { writeFile, unlink, readFile } from 'fs/promises';
import { spawn, ChildProcess } from 'child_process';
import { tmpdir } from 'os';
import { join } from 'path';
import { LogService } from './LogService.js';

export interface RecordingResult {
  buffer: Buffer;
  duration: number;
  size: number;
  mimeType: string;
}

/**
 * Frame accumulator to handle PvRecorder → Opus frame size conversion
 *
 * PvRecorder: 512 samples/frame at 16kHz
 * Opus (16kHz): 320 (20ms), 640 (40ms), or 960 (60ms) samples
 *
 * We accumulate 512-sample frames to reach 960-sample Opus frames.
 */
class FrameAccumulator {
  private buffer: Int16Array = new Int16Array(0);
  private readonly targetSize: number;

  constructor(targetSize: number) {
    this.targetSize = targetSize;
  }

  /**
   * Add samples and return complete Opus frames
   * @returns Array of complete frames (may be empty if not enough samples accumulated)
   */
  add(samples: Int16Array): Int16Array[] {
    // Concatenate new samples with buffer
    const combined = new Int16Array(this.buffer.length + samples.length);
    combined.set(this.buffer);
    combined.set(samples, this.buffer.length);
    this.buffer = combined;

    const frames: Int16Array[] = [];

    // Extract complete frames
    while (this.buffer.length >= this.targetSize) {
      const frame = this.buffer.slice(0, this.targetSize);
      frames.push(frame);

      // Keep remaining samples
      this.buffer = this.buffer.slice(this.targetSize);
    }

    return frames;
  }

  /**
   * Get any remaining samples (for end of recording)
   * Pad with zeros if needed
   */
  flush(): Int16Array | null {
    if (this.buffer.length === 0) {
      return null;
    }

    // Pad to target size with zeros
    if (this.buffer.length < this.targetSize) {
      const padded = new Int16Array(this.targetSize);
      padded.set(this.buffer);
      // Rest is already zeros
      this.buffer = padded;
    }

    const result = this.buffer;
    this.buffer = new Int16Array(0);
    return result;
  }

  /**
   * Get current buffer size
   */
  get pendingSamples(): number {
    return this.buffer.length;
  }

  /**
   * Reset accumulator
   */
  reset(): void {
    this.buffer = new Int16Array(0);
  }
}

/**
 * Audio service using PvRecorder + FFmpeg
 *
 * Architecture:
 * - PvRecorder captures PCM audio (Int16Array frames, 16kHz, 512 samples/frame)
 * - Raw PCM samples are accumulated during recording
 * - FFmpeg (libopus) encodes to Ogg Opus at stop time
 *
 * Benefits of 16kHz:
 * - Lower bandwidth than 48kHz (3x less data)
 * - Optimized for voice/speech
 * - Sufficient for walkie-talkie audio quality
 */
export class PvRecorderAudioService {
  private recorder: PvRecorder | null = null;
  private accumulator: FrameAccumulator | null = null;

  private isRecording: boolean = false;
  private isStopping: boolean = false; // True while stopRecording() is in progress
  private recordingStartTime: number = 0;
  private pcmSamples: Int16Array[] = []; // Accumulate raw PCM for FFmpeg encoding

  private isPlaying: boolean = false;
  private playProcess: ChildProcess | null = null;
  private currentAudioPath: string | null = null;

  // PvRecorder configuration
  private readonly PV_FRAME_LENGTH = 512; // PvRecorder samples per frame
  private readonly PV_SAMPLE_RATE = 16000; // PvRecorder sample rate (fixed)
  private readonly OPUS_SAMPLE_RATE = 16000; // 16kHz (voice quality)
  private readonly OPUS_FRAME_SIZE = 960; // 60ms at 16kHz
  private readonly OPUS_CHANNELS = 1; // Mono

  /**
   * Initialize audio recorder
   */
  async initialize(deviceIndex: number = -1): Promise<void> {
    try {
      // Initialize PvRecorder (outputs 16kHz)
      this.recorder = new PvRecorder(this.PV_FRAME_LENGTH, deviceIndex);

      // Initialize frame accumulator
      this.accumulator = new FrameAccumulator(this.OPUS_FRAME_SIZE);

      LogService.getInstance().addEntry(
        'log',
        `Audio initialized: PvRecorder v${this.recorder.version}, Opus @ ${this.OPUS_SAMPLE_RATE}Hz`,
      );
    } catch (error) {
      const errorMsg = error instanceof Error ? error.message : String(error);
      LogService.getInstance().addEntry(
        'error',
        `Failed to initialize audio: ${errorMsg}`,
      );
      throw error;
    }
  }

  /**
   * Get available audio devices
   */
  getAvailableDevices(): string[] {
    try {
      return PvRecorder.getAvailableDevices();
    } catch (error) {
      const errorMsg = error instanceof Error ? error.message : String(error);
      LogService.getInstance().addEntry(
        'error',
        `Failed to get devices: ${errorMsg}`,
      );
      return [];
    }
  }

  /**
   * Start recording audio
   */
  async startRecording(): Promise<void> {
    if (!this.recorder || !this.accumulator) {
      throw new Error('Audio not initialized. Call initialize() first.');
    }

    if (this.isRecording) {
      throw new Error('Already recording');
    }

    this.pcmSamples = [];
    this.accumulator.reset();
    this.recordingStartTime = Date.now();

    try {
      this.recorder.start();
      // Small delay to let PvRecorder fully initialize before reading
      await new Promise(resolve => setTimeout(resolve, 50));
      this.isRecording = true;
      LogService.getInstance().addEntry('log', 'Recording started');
    } catch (error) {
      this.isRecording = false;
      throw error;
    }
  }

  /**
   * Record continuously and accumulate PCM samples
   * Call this repeatedly while recording to capture audio frames
   */
  async recordFrame(): Promise<boolean> {
    if (!this.isRecording || !this.recorder || !this.accumulator) {
      return false;
    }

    try {
      // Read a frame of PCM audio (Int16Array, 512 samples at 16kHz)
      const pcmFrame = await this.recorder.read();

      // Store raw PCM for FFmpeg encoding later
      this.pcmSamples.push(new Int16Array(pcmFrame));

      return true;
    } catch (error) {
      // If recording was stopped or is being stopped, the read() will fail - this is expected
      if (!this.isRecording || this.isStopping) {
        return false;
      }
      // Only log unexpected errors
      const errorMsg = error instanceof Error ? error.message : String(error);
      LogService.getInstance().addEntry(
        'error',
        `Frame capture error: ${errorMsg}`,
      );
      return false;
    }
  }

  /**
   * Stop recording and return the encoded audio as Ogg Opus
   */
  async stopRecording(): Promise<RecordingResult> {
    if (!this.isRecording) {
      throw new Error('Not recording');
    }

    this.isStopping = true;
    this.isRecording = false;

    try {
      // Stop the recorder
      if (this.recorder) {
        this.recorder.stop();
      }

      const duration = Date.now() - this.recordingStartTime;

      // Concatenate all PCM samples into a single buffer
      const totalSamples = this.pcmSamples.reduce(
        (sum, arr) => sum + arr.length,
        0,
      );
      const pcmBuffer = Buffer.alloc(totalSamples * 2); // 2 bytes per Int16 sample
      let offset = 0;
      for (const samples of this.pcmSamples) {
        for (let i = 0; i < samples.length; i++) {
          pcmBuffer.writeInt16LE(samples[i], offset);
          offset += 2;
        }
      }

      // Encode to Ogg Opus using FFmpeg
      const oggBuffer = await this.encodeToOggOpus(pcmBuffer);

      const result: RecordingResult = {
        buffer: oggBuffer,
        duration,
        size: oggBuffer.length,
        mimeType: 'audio/ogg; codecs=opus',
      };

      LogService.getInstance().addEntry(
        'log',
        `Recording stopped: ${duration}ms, ${totalSamples} samples, ${oggBuffer.length} bytes`,
      );

      this.pcmSamples = [];
      this.isStopping = false;
      return result;
    } catch (error) {
      this.pcmSamples = [];
      this.isStopping = false;
      throw error;
    }
  }

  /**
   * Encode raw PCM buffer to Ogg Opus using FFmpeg
   */
  private async encodeToOggOpus(pcmBuffer: Buffer): Promise<Buffer> {
    const timestamp = Date.now();
    const pcmPath = join(tmpdir(), `wata-pcm-${timestamp}.raw`);
    const oggPath = join(tmpdir(), `wata-opus-${timestamp}.ogg`);

    try {
      // Write PCM to temp file
      await writeFile(pcmPath, pcmBuffer);

      // Encode with FFmpeg
      await new Promise<void>((resolve, reject) => {
        const ffmpeg = spawn('ffmpeg', [
          '-f',
          's16le', // Input format: signed 16-bit little-endian
          '-ar',
          String(this.OPUS_SAMPLE_RATE), // Sample rate
          '-ac',
          String(this.OPUS_CHANNELS), // Channels
          '-i',
          pcmPath, // Input file
          '-c:a',
          'libopus', // Opus codec
          '-b:a',
          '24k', // Bitrate (24kbps good for voice)
          '-application',
          'voip', // Optimize for voice
          '-y', // Overwrite output
          oggPath, // Output file
        ]);

        let stderr = '';
        ffmpeg.stderr?.on('data', data => {
          stderr += data.toString();
        });

        ffmpeg.on('close', code => {
          if (code === 0) {
            resolve();
          } else {
            reject(new Error(`FFmpeg failed with code ${code}: ${stderr}`));
          }
        });

        ffmpeg.on('error', err => {
          reject(new Error(`FFmpeg spawn error: ${err.message}`));
        });
      });

      // Read the encoded Ogg file
      const oggBuffer = await readFile(oggPath);
      return oggBuffer;
    } finally {
      // Cleanup temp files
      await unlink(pcmPath).catch(() => {});
      await unlink(oggPath).catch(() => {});
    }
  }

  /**
   * Cancel recording without saving
   */
  async cancelRecording(): Promise<void> {
    if (!this.isRecording) return;

    this.isStopping = true;
    this.isRecording = false;

    try {
      if (this.recorder) {
        this.recorder.stop();
      }
    } catch (error) {
      // Ignore stop errors
    }

    if (this.accumulator) {
      this.accumulator.reset();
    }

    this.pcmSamples = [];
    this.isStopping = false;
    LogService.getInstance().addEntry('log', 'Recording cancelled');
  }

  /**
   * Get recording state
   */
  getIsRecording(): boolean {
    return this.isRecording;
  }

  /**
   * Get recording duration in milliseconds
   */
  getRecordingDuration(): number {
    if (!this.isRecording) return 0;
    return Date.now() - this.recordingStartTime;
  }

  /**
   * Get number of pending samples in accumulator
   */
  getPendingSamples(): number {
    return this.accumulator?.pendingSamples ?? 0;
  }

  /**
   * Release resources
   */
  async release(): Promise<void> {
    if (this.isRecording) {
      await this.cancelRecording();
    }

    if (this.isPlaying) {
      await this.stopPlayback();
    }

    if (this.recorder) {
      this.recorder.release();
      this.recorder = null;
    }

    this.accumulator = null;

    LogService.getInstance().addEntry('log', 'Audio resources released');
  }

  /**
   * Start playback of audio from a URL
   * Supports both Ogg Opus and M4A/AAC formats
   * @param audioUrl - URL of the audio file
   * @param accessToken - Optional access token for authenticated downloads
   */
  async startPlayback(audioUrl: string, accessToken?: string): Promise<void> {
    if (this.isPlaying) {
      await this.stopPlayback();
    }

    this.currentAudioPath = audioUrl;
    this.isPlaying = true;

    // Log the URL being fetched for debugging
    LogService.getInstance().addEntry(
      'log',
      `Playback: Fetching audio from ${audioUrl}`,
    );

    try {
      // Build fetch headers with authentication if token is provided
      const headers: Record<string, string> = {};
      if (accessToken) {
        headers['Authorization'] = `Bearer ${accessToken}`;
        LogService.getInstance().addEntry(
          'log',
          `Playback: Using authenticated download`,
        );
      }

      // Download the audio file
      const response = await fetch(audioUrl, { headers });
      if (!response.ok) {
        throw new Error(
          `Failed to download audio: ${response.statusText} (${response.status})`,
        );
      }

      const arrayBuffer = await response.arrayBuffer();
      const audioBuffer = Buffer.from(arrayBuffer);

      // Detect format from magic bytes
      const isOgg =
        audioBuffer.length >= 4 &&
        audioBuffer.toString('ascii', 0, 4) === 'OggS';
      const contentType = response.headers.get('content-type') || '';
      const needsConversion =
        isOgg || contentType.includes('ogg') || contentType.includes('opus');

      const timestamp = Date.now();
      let playPath: string;

      if (needsConversion) {
        // Convert Ogg Opus to WAV for afplay compatibility
        const oggPath = join(tmpdir(), `wata-play-${timestamp}.ogg`);
        const wavPath = join(tmpdir(), `wata-play-${timestamp}.wav`);

        await writeFile(oggPath, audioBuffer);

        // Decode with FFmpeg
        await new Promise<void>((resolve, reject) => {
          const ffmpeg = spawn('ffmpeg', [
            '-i',
            oggPath,
            '-f',
            'wav',
            '-y',
            wavPath,
          ]);

          ffmpeg.on('close', code => {
            // Cleanup source file
            unlink(oggPath).catch(() => {});
            if (code === 0) {
              resolve();
            } else {
              reject(new Error(`FFmpeg decode failed with code ${code}`));
            }
          });

          ffmpeg.on('error', err => {
            unlink(oggPath).catch(() => {});
            reject(new Error(`FFmpeg spawn error: ${err.message}`));
          });
        });

        playPath = wavPath;
      } else {
        // M4A/AAC can be played directly by afplay
        playPath = join(tmpdir(), `wata-play-${timestamp}.m4a`);
        await writeFile(playPath, audioBuffer);
      }

      // Play using afplay (macOS built-in)
      this.playProcess = spawn('afplay', [playPath]);

      this.playProcess.on('close', () => {
        this.isPlaying = false;
        this.currentAudioPath = null;
        this.playProcess = null;
        // Clean up temp file
        unlink(playPath).catch(() => {});
      });

      this.playProcess.on('error', err => {
        const errorMsg = err instanceof Error ? err.message : String(err);
        LogService.getInstance().addEntry(
          'error',
          `Playback error: ${errorMsg}`,
        );
        this.isPlaying = false;
        this.currentAudioPath = null;
        this.playProcess = null;
        unlink(playPath).catch(() => {});
      });

      LogService.getInstance().addEntry(
        'log',
        `Playback started: ${needsConversion ? 'Ogg Opus' : 'M4A'}`,
      );
    } catch (error) {
      this.isPlaying = false;
      this.currentAudioPath = null;
      throw error;
    }
  }

  /**
   * Stop current playback
   */
  async stopPlayback(): Promise<void> {
    if (this.playProcess) {
      this.playProcess.kill();
      this.playProcess = null;
    }
    this.isPlaying = false;
    this.currentAudioPath = null;
  }

  /**
   * Get playback state
   */
  getIsPlaying(): boolean {
    return this.isPlaying;
  }

  /**
   * Get current playback URL
   */
  getCurrentAudioUrl(): string | null {
    return this.currentAudioPath;
  }
}

// Export singleton instance
export const pvRecorderAudioService = new PvRecorderAudioService();
