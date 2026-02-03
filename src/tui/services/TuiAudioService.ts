/// <reference types="node" />

import { spawn, ChildProcess } from 'child_process';
import { readFile, unlink } from 'fs/promises';
import { tmpdir } from 'os';
import { join } from 'path';

import { LogService } from './LogService.js';

/**
 * Audio service for TUI AudioCode testing.
 *
 * Provides utilities for testing the ABBREE hardware's audio codec:
 * - playWav: Play WAV files via afplay (for tone verification)
 * - recordRawPcm: Record raw PCM for AudioCode decoding
 *
 * Main voice recording/playback uses PvRecorderAudioService instead.
 */
export class TuiAudioService {
  private playProcess: ChildProcess | null = null;
  private isPlaying: boolean = false;

  /**
   * Play WAV file using afplay (for AudioCode tones)
   * @param wavPath - Path to WAV file
   */
  async playWav(wavPath: string): Promise<void> {
    if (this.isPlaying) {
      await this.stopPlayback();
    }

    this.isPlaying = true;

    try {
      // Play using afplay (macOS built-in)
      this.playProcess = spawn('afplay', [wavPath]);

      this.playProcess.on('close', () => {
        this.isPlaying = false;
      });

      this.playProcess.on('error', err => {
        const errorMsg = err instanceof Error ? err.message : String(err);
        LogService.getInstance().addEntry(
          'error',
          `WAV playback error: ${errorMsg}`,
        );
        this.isPlaying = false;
      });
    } catch (error) {
      this.isPlaying = false;
      throw error;
    }
  }

  /**
   * Stop current playback
   */
  private async stopPlayback(): Promise<void> {
    if (this.playProcess) {
      this.playProcess.kill();
      this.playProcess = null;
    }
    this.isPlaying = false;
  }

  /**
   * Record raw PCM audio (for AudioCode decoding)
   * Records at 16kHz mono, returns Float32Array samples
   * @param durationMs - Recording duration in milliseconds
   * @returns Float32Array of audio samples
   */
  async recordRawPcm(durationMs: number): Promise<Float32Array> {
    const sampleRate = 16000;
    const durationSec = durationMs / 1000;
    const numSamples = Math.floor(sampleRate * durationSec);
    const bytesPerSample = 2; // int16
    const _numBytes = numSamples * bytesPerSample;

    const outputPath = join(tmpdir(), `wata-pcm-${Date.now()}.raw`);

    console.error(
      `      [rec] Starting: ${sampleRate}Hz mono, ${durationSec}s...`,
    );

    try {
      // Record using rec (sox) at 16kHz mono
      const rec = spawn('rec', [
        '-q',
        '-r',
        sampleRate.toString(),
        '-c',
        '1',
        '-t',
        's16', // signed 16-bit
        '-e',
        'signed',
        '-b',
        '16',
        outputPath,
      ]);

      // Stop after specified duration
      setTimeout(() => {
        rec.kill('SIGINT');
      }, durationMs + 100); // Add small buffer

      await new Promise<void>((resolve, reject) => {
        rec.on('close', code => {
          if (code === 0 || code === null) {
            console.error(`      [rec] Finished (exit code: ${code})`);
            resolve();
          } else {
            reject(new Error(`rec exited with code ${code}`));
          }
        });
        rec.on('error', reject);
      });

      // Read the recorded file
      const buffer = await readFile(outputPath);
      console.error(`      [rec] Read ${buffer.length} bytes from temp file`);

      // Convert int16 buffer to Float32Array (-1.0 to 1.0)
      const samples = new Float32Array(numSamples);
      const dataView = new DataView(
        buffer.buffer,
        buffer.byteOffset,
        buffer.byteLength,
      );

      for (let i = 0; i < numSamples && i * 2 < buffer.length; i++) {
        const int16 = dataView.getInt16(i * 2, true); // little-endian
        samples[i] = int16 / 32768;
      }

      console.error(`      [rec] Converted to ${numSamples} Float32 samples`);

      // Clean up temp file
      await unlink(outputPath).catch(() => {
        // Ignore cleanup errors
      });

      return samples;
    } catch (error) {
      // Clean up temp file on error
      await unlink(outputPath).catch(() => {
        // Ignore cleanup errors
      });
      console.error(
        `      [rec] Error: ${error instanceof Error ? error.message : String(error)}`,
      );
      throw error;
    }
  }
}

// Export singleton instance
export const tuiAudioService = new TuiAudioService();
