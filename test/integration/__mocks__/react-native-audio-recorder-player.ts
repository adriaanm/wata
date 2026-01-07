// Mock for react-native-audio-recorder-player in Node.js tests

export enum AudioEncoderAndroidType {
  DEFAULT = 0,
  AMR_NB = 1,
  AMR_WB = 2,
  AAC = 3,
}

export enum AudioSourceAndroidType {
  DEFAULT = 0,
  MIC = 1,
}

export enum OutputFormatAndroidType {
  DEFAULT = 0,
  AAC_ADTS = 6,
}

export enum AVEncoderAudioQualityIOSType {
  min = 0,
  low = 32,
  medium = 64,
  high = 96,
  max = 127,
}

export interface RecordBackType {
  currentPosition: number;
  isRecording: boolean;
}

export interface PlayBackType {
  currentPosition: number;
  duration: number;
  isPlaying: boolean;
}

// Helper function to format time
function formatMmss(secs: number): string {
  const mins = Math.floor(secs / 60);
  const s = secs % 60;
  return `${mins}:${s.toString().padStart(2, '0')}`;
}

// Singleton mock instance
const mockInstance = {
  setSubscriptionDuration: jest.fn(),
  startRecorder: jest.fn().mockResolvedValue('file:///mock/recording.m4a'),
  stopRecorder: jest.fn().mockResolvedValue('file:///mock/recording.m4a'),
  pauseRecorder: jest.fn().mockResolvedValue('paused'),
  resumeRecorder: jest.fn().mockResolvedValue('resumed'),
  startPlayer: jest.fn().mockResolvedValue('started'),
  stopPlayer: jest.fn().mockResolvedValue('stopped'),
  pausePlayer: jest.fn().mockResolvedValue('paused'),
  resumePlayer: jest.fn().mockResolvedValue('resumed'),
  seekToPlayer: jest.fn().mockResolvedValue('seeked'),
  setVolume: jest.fn().mockResolvedValue('volume set'),
  addRecordBackListener: jest.fn(),
  removeRecordBackListener: jest.fn(),
  addPlayBackListener: jest.fn(),
  removePlayBackListener: jest.fn(),
  mmss: jest.fn((secs: number) => formatMmss(secs)),
  mmssss: jest.fn((ms: number) => formatMmss(Math.floor(ms / 1000))),
};

export default mockInstance;
