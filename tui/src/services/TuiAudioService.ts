import { spawn, ChildProcess } from 'child_process';
import { writeFile, unlink } from 'fs/promises';
import { tmpdir } from 'os';
import { join } from 'path';

/**
 * Audio service for TUI using macOS native tools
 */
export class TuiAudioService {
  private playProcess: ChildProcess | null = null;
  private currentAudioUrl: string | null = null;
  private isPlaying: boolean = false;

  /**
   * Download audio from URL and play using afplay (macOS)
   */
  async startPlayback(audioUrl: string): Promise<void> {
    if (this.isPlaying) {
      await this.stopPlayback();
    }

    this.currentAudioUrl = audioUrl;
    this.isPlaying = true;

    try {
      // Download the audio file
      const response = await fetch(audioUrl);
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

      this.playProcess.on('error', (err) => {
        console.error('Playback error:', err);
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
}

// Export singleton instance
export const tuiAudioService = new TuiAudioService();
