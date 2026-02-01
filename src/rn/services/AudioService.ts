import { Buffer } from 'buffer';

import { encodeOggOpus } from '@shared/lib/audio-codec';

import LiveAudioStream from 'react-native-live-audio-stream';
import AudioRecorderPlayer, {
  PlayBackType,
} from 'react-native-audio-recorder-player';
import RNFS from 'react-native-fs';

export interface RecordingResult {
  uri: string;
  duration: number;
  size: number;
  mimeType: string;
}

type RecordingCallback = (currentPosition: number) => void;
type PlaybackCallback = (currentPosition: number, duration: number) => void;
type PlaybackCompleteCallback = () => void;

// Audio configuration constants
const SAMPLE_RATE = 16000;
const CHANNELS = 1;
const BITS_PER_SAMPLE = 16;
const AUDIO_SOURCE = 6; // VOICE_RECOGNITION on Android

class AudioService {
  // AudioRecorderPlayer is exported as a singleton - used for playback only
  private audioRecorderPlayer = AudioRecorderPlayer;
  private isRecording = false;
  private isPlaying = false;
  private currentRecordingPath: string | null = null;
  private recordingStartTime: number = 0;

  // PCM chunks accumulated during recording
  private pcmChunks: Int16Array[] = [];
  private recordingProgressTimer: NodeJS.Timeout | null = null;

  private recordingCallbacks: RecordingCallback[] = [];
  private playbackCallbacks: PlaybackCallback[] = [];
  private playbackCompleteCallbacks: PlaybackCompleteCallback[] = [];

  constructor() {
    this.audioRecorderPlayer.setSubscriptionDuration(0.1); // Update every 100ms

    // Initialize LiveAudioStream
    LiveAudioStream.init({
      sampleRate: SAMPLE_RATE,
      channels: CHANNELS,
      bitsPerSample: BITS_PER_SAMPLE,
      audioSource: AUDIO_SOURCE,
      bufferSize: 4096,
      wavFile: '', // Required but unused when streaming live audio
    } as Parameters<typeof LiveAudioStream.init>[0]);
  }

  async startRecording(): Promise<void> {
    if (this.isRecording) {
      throw new Error('Already recording');
    }

    // Clear any previous PCM data
    this.pcmChunks = [];

    // Generate unique filename for later
    const timestamp = Date.now();
    this.currentRecordingPath = `${RNFS.CachesDirectoryPath}/voice_${timestamp}.ogg`;

    this.recordingStartTime = Date.now();
    this.isRecording = true;

    // Set up data listener for PCM chunks
    LiveAudioStream.on('data', (data: string) => {
      if (this.isRecording) {
        // data is base64-encoded PCM
        const chunk = Buffer.from(data, 'base64');
        // Convert buffer to Int16Array (16-bit signed PCM)
        const int16Chunk = new Int16Array(
          chunk.buffer,
          chunk.byteOffset,
          chunk.byteLength / 2
        );
        this.pcmChunks.push(int16Chunk);
      }
    });

    // Start recording progress timer
    this.recordingProgressTimer = setInterval(() => {
      const position = Date.now() - this.recordingStartTime;
      this.recordingCallbacks.forEach((cb) => cb(position));
    }, 100);

    await LiveAudioStream.start();
  }

  async stopRecording(): Promise<RecordingResult> {
    if (!this.isRecording) {
      throw new Error('Not recording');
    }

    // Stop recording
    await LiveAudioStream.stop();

    // Clear progress timer
    if (this.recordingProgressTimer) {
      clearInterval(this.recordingProgressTimer);
      this.recordingProgressTimer = null;
    }

    this.isRecording = false;

    // Calculate duration
    const duration = Date.now() - this.recordingStartTime;

    // Concatenate all PCM chunks into a single Int16Array
    const totalSamples = this.pcmChunks.reduce(
      (sum, chunk) => sum + chunk.length,
      0
    );
    const pcm = new Int16Array(totalSamples);
    let offset = 0;
    for (const chunk of this.pcmChunks) {
      pcm.set(chunk, offset);
      offset += chunk.length;
    }

    // Encode to Ogg Opus using shared library
    const oggOpusBuffer = encodeOggOpus(pcm, { sampleRate: SAMPLE_RATE });

    // Write to temp file for compatibility with existing code
    const recordingPath = this.currentRecordingPath;
    if (!recordingPath) {
      throw new Error('Recording path not set');
    }

    await RNFS.writeFile(
      recordingPath,
      oggOpusBuffer.toString('base64'),
      'base64'
    );

    const result: RecordingResult = {
      uri: recordingPath,
      duration,
      size: oggOpusBuffer.length,
      mimeType: 'audio/ogg; codecs=opus',
    };

    // Clean up
    this.pcmChunks = [];
    this.currentRecordingPath = null;

    return result;
  }

  async cancelRecording(): Promise<void> {
    if (!this.isRecording) return;

    await LiveAudioStream.stop();

    // Clear progress timer
    if (this.recordingProgressTimer) {
      clearInterval(this.recordingProgressTimer);
      this.recordingProgressTimer = null;
    }

    this.isRecording = false;
    this.pcmChunks = [];
    this.currentRecordingPath = null;
  }

  async startPlayback(uri: string): Promise<void> {
    if (this.isPlaying) {
      await this.stopPlayback();
    }

    this.isPlaying = true;

    await this.audioRecorderPlayer.startPlayer(uri);

    this.audioRecorderPlayer.addPlayBackListener((e: PlayBackType) => {
      const currentPosition = e.currentPosition;
      const duration = e.duration;

      this.playbackCallbacks.forEach((cb) => cb(currentPosition, duration));

      // Check if playback finished
      if (currentPosition >= duration - 100) {
        this.stopPlayback();
        this.playbackCompleteCallbacks.forEach((cb) => cb());
      }
    });
  }

  async stopPlayback(): Promise<void> {
    if (!this.isPlaying) return;

    await this.audioRecorderPlayer.stopPlayer();
    this.audioRecorderPlayer.removePlayBackListener();
    this.isPlaying = false;
  }

  async pausePlayback(): Promise<void> {
    if (!this.isPlaying) return;
    await this.audioRecorderPlayer.pausePlayer();
  }

  async resumePlayback(): Promise<void> {
    await this.audioRecorderPlayer.resumePlayer();
  }

  async seekTo(position: number): Promise<void> {
    await this.audioRecorderPlayer.seekToPlayer(position);
  }

  getIsRecording(): boolean {
    return this.isRecording;
  }

  getIsPlaying(): boolean {
    return this.isPlaying;
  }

  onRecordingProgress(callback: RecordingCallback): () => void {
    this.recordingCallbacks.push(callback);
    return () => {
      this.recordingCallbacks = this.recordingCallbacks.filter(
        (cb) => cb !== callback
      );
    };
  }

  onPlaybackProgress(callback: PlaybackCallback): () => void {
    this.playbackCallbacks.push(callback);
    return () => {
      this.playbackCallbacks = this.playbackCallbacks.filter(
        (cb) => cb !== callback
      );
    };
  }

  onPlaybackComplete(callback: PlaybackCompleteCallback): () => void {
    this.playbackCompleteCallbacks.push(callback);
    return () => {
      this.playbackCompleteCallbacks = this.playbackCompleteCallbacks.filter(
        (cb) => cb !== callback
      );
    };
  }

  formatDuration(ms: number): string {
    const totalSeconds = Math.floor(ms / 1000);
    const minutes = Math.floor(totalSeconds / 60);
    const seconds = totalSeconds % 60;
    return `${minutes}:${seconds.toString().padStart(2, '0')}`;
  }
}

// Export singleton instance
export const audioService = new AudioService();
