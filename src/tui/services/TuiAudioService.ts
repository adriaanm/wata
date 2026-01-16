/// <reference types="node" />

import { spawn, ChildProcess } from 'child_process';
import { writeFile, unlink, readFile, stat } from 'fs/promises';
import { tmpdir } from 'os';
import { join } from 'path';
import { Buffer } from 'buffer';
import { LogService } from './LogService.js';

export interface RecordingResult {
  buffer: Buffer;
  duration: number;
  size: number;
  mimeType: string;
}

/**
 * Audio service for TUI using macOS native tools
 */
export class TuiAudioService {
  private playProcess: ChildProcess | null = null;
  private currentAudioUrl: string | null = null;
  private isPlaying: boolean = false;

  private recProcess: ChildProcess | null = null;
  private ffmpegProcess: ChildProcess | null = null;
  private isRecording: boolean = false;
  private recordingStartTime: number = 0;
  private currentRecordingPath: string | null = null;

  /**
   * Download audio from URL and play using afplay (macOS)
   * @param audioUrl - URL of the audio file
   * @param accessToken - Optional access token for authenticated downloads
   */
  async startPlayback(audioUrl: string, accessToken?: string): Promise<void> {
    if (this.isPlaying) {
      await this.stopPlayback();
    }

    this.currentAudioUrl = audioUrl;
    this.isPlaying = true;

    try {
      // Build fetch headers with authentication if token is provided
      const headers: Record<string, string> = {};
      if (accessToken) {
        headers['Authorization'] = `Bearer ${accessToken}`;
      }

      // Download the audio file
      const response = await fetch(audioUrl, { headers });
      if (!response.ok) {
        throw new Error(`Failed to download audio: ${response.statusText}`);
      }

      const arrayBuffer = await response.arrayBuffer();
      const buffer = Buffer.from(arrayBuffer);

      // Write to temporary file
      const tempPath = join(tmpdir(), `wata-audio-${Date.now()}.m4a`);
      await writeFile(tempPath, buffer);

      // Play using afplay (macOS built-in)
      this.playProcess = spawn('afplay', [tempPath]);

      this.playProcess.on('close', () => {
        this.isPlaying = false;
        this.currentAudioUrl = null;
        // Clean up temp file
        unlink(tempPath).catch(() => {
          // Ignore cleanup errors
        });
      });

      this.playProcess.on('error', err => {
        const errorMsg = err instanceof Error ? err.message : String(err);
        LogService.getInstance().addEntry(
          'error',
          `Playback error: ${errorMsg}`,
        );
        this.isPlaying = false;
        this.currentAudioUrl = null;
      });
    } catch (error) {
      this.isPlaying = false;
      this.currentAudioUrl = null;
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
    this.currentAudioUrl = null;
  }

  /**
   * Get current playback state
   */
  getIsPlaying(): boolean {
    return this.isPlaying;
  }

  /**
   * Get currently playing audio URL
   */
  getCurrentAudioUrl(): string | null {
    return this.currentAudioUrl;
  }

  /**
   * Start recording using sox/rec → FFmpeg pipeline
   * rec (from sox) captures audio and pipes to ffmpeg for AAC encoding
   */
  async startRecording(): Promise<void> {
    if (this.isRecording) {
      throw new Error('Already recording');
    }

    const timestamp = Date.now();
    const outputPath = join(tmpdir(), `wata-recording-${timestamp}.m4a`);

    this.currentRecordingPath = outputPath;
    this.recordingStartTime = Date.now();
    this.isRecording = true;

    try {
      // Start rec (sox) to capture audio
      this.recProcess = spawn('rec', [
        '-q', // quiet
        '-r',
        '44100', // sample rate
        '-c',
        '1', // mono
        '-t',
        'raw', // raw PCM output
        '-', // output to stdout
      ]);

      // Start FFmpeg to encode as AAC
      this.ffmpegProcess = spawn('ffmpeg', [
        '-f',
        's16le', // input format (signed 16-bit little-endian)
        '-ar',
        '44100', // sample rate
        '-ac',
        '1', // mono
        '-i',
        'pipe:0', // read from stdin
        '-c:a',
        'aac', // AAC codec
        '-b:a',
        '64k', // bitrate
        '-y', // overwrite output file
        outputPath,
      ]);

      // Pipe rec output to ffmpeg input
      if (this.recProcess.stdout && this.ffmpegProcess.stdin) {
        this.recProcess.stdout.pipe(this.ffmpegProcess.stdin);
      }

      this.recProcess.on('error', err => {
        const errorMsg = err instanceof Error ? err.message : String(err);
        LogService.getInstance().addEntry(
          'error',
          `Recording error (rec): ${errorMsg}`,
        );
        this.isRecording = false;
      });

      this.ffmpegProcess.on('error', err => {
        const errorMsg = err instanceof Error ? err.message : String(err);
        LogService.getInstance().addEntry(
          'error',
          `Recording error (ffmpeg): ${errorMsg}`,
        );
        this.isRecording = false;
      });
    } catch (error) {
      this.isRecording = false;
      throw error;
    }
  }

  /**
   * Stop recording and return the audio buffer
   */
  async stopRecording(): Promise<RecordingResult> {
    if (!this.isRecording || !this.currentRecordingPath) {
      throw new Error('Not recording');
    }

    // Stop rec process
    if (this.recProcess) {
      this.recProcess.kill('SIGINT');
      this.recProcess = null;
    }

    // Wait a moment for ffmpeg to finish encoding
    await new Promise(resolve => setTimeout(resolve, 500));

    // Stop ffmpeg process
    if (this.ffmpegProcess) {
      this.ffmpegProcess.kill('SIGINT');
      this.ffmpegProcess = null;
    }

    // Wait for file to be written
    await new Promise(resolve => setTimeout(resolve, 500));

    this.isRecording = false;
    const duration = Date.now() - this.recordingStartTime;

    try {
      // Read the recorded file
      const buffer = await readFile(this.currentRecordingPath);
      const fileStat = await stat(this.currentRecordingPath);

      const result: RecordingResult = {
        buffer,
        duration,
        size: fileStat.size,
        mimeType: 'audio/mp4',
      };

      // Clean up the temp file
      await unlink(this.currentRecordingPath).catch(() => {
        // Ignore cleanup errors
      });

      this.currentRecordingPath = null;
      return result;
    } catch (error) {
      this.currentRecordingPath = null;
      throw error;
    }
  }

  /**
   * Cancel recording without saving
   */
  async cancelRecording(): Promise<void> {
    if (!this.isRecording) return;

    if (this.recProcess) {
      this.recProcess.kill();
      this.recProcess = null;
    }

    if (this.ffmpegProcess) {
      this.ffmpegProcess.kill();
      this.ffmpegProcess = null;
    }

    this.isRecording = false;

    // Clean up temp file
    if (this.currentRecordingPath) {
      await unlink(this.currentRecordingPath).catch(() => {
        // Ignore cleanup errors
      });
      this.currentRecordingPath = null;
    }
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
}

// Export singleton instance
export const tuiAudioService = new TuiAudioService();
