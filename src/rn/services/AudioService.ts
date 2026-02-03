import AudioRecorderPlayer, {
  AudioEncoderAndroidType,
  AudioSourceAndroidType,
  OutputFormatAndroidType,
  RecordBackType,
  PlayBackType,
  AVEncoderAudioQualityIOSType,
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

class AudioService {
  // AudioRecorderPlayer is exported as a singleton
  private audioRecorderPlayer = AudioRecorderPlayer;
  private isRecording = false;
  private isPlaying = false;
  private currentRecordingPath: string | null = null;
  private recordingStartTime: number = 0;

  private recordingCallbacks: RecordingCallback[] = [];
  private playbackCallbacks: PlaybackCallback[] = [];
  private playbackCompleteCallbacks: PlaybackCompleteCallback[] = [];

  constructor() {
    this.audioRecorderPlayer.setSubscriptionDuration(0.1); // Update every 100ms
  }

  async startRecording(): Promise<void> {
    if (this.isRecording) {
      throw new Error('Already recording');
    }

    // Generate unique filename
    const timestamp = Date.now();
    const path = `${RNFS.CachesDirectoryPath}/voice_${timestamp}.m4a`;

    const audioSet = {
      AudioEncoderAndroid: AudioEncoderAndroidType.AAC,
      AudioSourceAndroid: AudioSourceAndroidType.MIC,
      AVEncoderAudioQualityKeyIOS: AVEncoderAudioQualityIOSType.medium,
      AVNumberOfChannelsKeyIOS: 1,
      AVFormatIDKeyIOS: 'aac' as const,
      OutputFormatAndroid: OutputFormatAndroidType.AAC_ADTS,
    };

    this.currentRecordingPath = path;
    this.recordingStartTime = Date.now();
    this.isRecording = true;

    await this.audioRecorderPlayer.startRecorder(path, audioSet);

    this.audioRecorderPlayer.addRecordBackListener((e: RecordBackType) => {
      const position = e.currentPosition;
      this.recordingCallbacks.forEach(cb => cb(position));
    });
  }

  async stopRecording(): Promise<RecordingResult> {
    if (!this.isRecording || !this.currentRecordingPath) {
      throw new Error('Not recording');
    }

    await this.audioRecorderPlayer.stopRecorder();
    this.audioRecorderPlayer.removeRecordBackListener();
    this.isRecording = false;

    const duration = Date.now() - this.recordingStartTime;
    const stat = await RNFS.stat(this.currentRecordingPath);

    const result: RecordingResult = {
      uri: this.currentRecordingPath,
      duration,
      size: Number(stat.size),
      mimeType: 'audio/mp4',
    };

    this.currentRecordingPath = null;
    return result;
  }

  async cancelRecording(): Promise<void> {
    if (!this.isRecording) return;

    await this.audioRecorderPlayer.stopRecorder();
    this.audioRecorderPlayer.removeRecordBackListener();
    this.isRecording = false;

    // Delete the partial recording
    if (this.currentRecordingPath) {
      try {
        await RNFS.unlink(this.currentRecordingPath);
      } catch {
        // Ignore deletion errors
      }
    }
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

      this.playbackCallbacks.forEach(cb => cb(currentPosition, duration));

      // Check if playback finished
      if (currentPosition >= duration - 100) {
        this.stopPlayback();
        this.playbackCompleteCallbacks.forEach(cb => cb());
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
        cb => cb !== callback,
      );
    };
  }

  onPlaybackProgress(callback: PlaybackCallback): () => void {
    this.playbackCallbacks.push(callback);
    return () => {
      this.playbackCallbacks = this.playbackCallbacks.filter(
        cb => cb !== callback,
      );
    };
  }

  onPlaybackComplete(callback: PlaybackCompleteCallback): () => void {
    this.playbackCompleteCallbacks.push(callback);
    return () => {
      this.playbackCompleteCallbacks = this.playbackCompleteCallbacks.filter(
        cb => cb !== callback,
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
