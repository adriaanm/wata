import {useState, useEffect, useCallback} from 'react';
import {audioService, RecordingResult} from '../services/AudioService';

export function useAudioRecorder() {
  const [isRecording, setIsRecording] = useState(false);
  const [recordingDuration, setRecordingDuration] = useState(0);

  useEffect(() => {
    const unsubscribe = audioService.onRecordingProgress(position => {
      setRecordingDuration(position);
    });

    return unsubscribe;
  }, []);

  const startRecording = useCallback(async () => {
    await audioService.startRecording();
    setIsRecording(true);
    setRecordingDuration(0);
  }, []);

  const stopRecording = useCallback(async (): Promise<RecordingResult> => {
    const result = await audioService.stopRecording();
    setIsRecording(false);
    return result;
  }, []);

  const cancelRecording = useCallback(async () => {
    await audioService.cancelRecording();
    setIsRecording(false);
    setRecordingDuration(0);
  }, []);

  return {
    isRecording,
    recordingDuration,
    startRecording,
    stopRecording,
    cancelRecording,
    formatDuration: audioService.formatDuration,
  };
}

export function useAudioPlayer() {
  const [isPlaying, setIsPlaying] = useState(false);
  const [currentPosition, setCurrentPosition] = useState(0);
  const [duration, setDuration] = useState(0);
  const [currentUri, setCurrentUri] = useState<string | null>(null);

  useEffect(() => {
    const unsubProgress = audioService.onPlaybackProgress((pos, dur) => {
      setCurrentPosition(pos);
      setDuration(dur);
    });

    const unsubComplete = audioService.onPlaybackComplete(() => {
      setIsPlaying(false);
      setCurrentPosition(0);
    });

    return () => {
      unsubProgress();
      unsubComplete();
    };
  }, []);

  const play = useCallback(async (uri: string) => {
    await audioService.startPlayback(uri);
    setIsPlaying(true);
    setCurrentUri(uri);
  }, []);

  const stop = useCallback(async () => {
    await audioService.stopPlayback();
    setIsPlaying(false);
    setCurrentPosition(0);
    setCurrentUri(null);
  }, []);

  const pause = useCallback(async () => {
    await audioService.pausePlayback();
    setIsPlaying(false);
  }, []);

  const resume = useCallback(async () => {
    await audioService.resumePlayback();
    setIsPlaying(true);
  }, []);

  return {
    isPlaying,
    currentPosition,
    duration,
    currentUri,
    play,
    stop,
    pause,
    resume,
    formatDuration: audioService.formatDuration,
  };
}
