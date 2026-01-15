import { useState, useEffect } from 'react';
import { tuiAudioService } from '../services/TuiAudioService.js';
import type { RecordingResult } from '../services/TuiAudioService.js';
import { LogService } from '../services/LogService.js';

/**
 * Hook for audio recording in TUI
 */
export function useAudioRecorder() {
  const [isRecording, setIsRecording] = useState(false);
  const [recordingDuration, setRecordingDuration] = useState(0);
  const [recordingError, setRecordingError] = useState<string | null>(null);

  // Update recording duration every 100ms
  useEffect(() => {
    if (!isRecording) {
      setRecordingDuration(0);
      return;
    }

    const interval = setInterval(() => {
      setRecordingDuration(tuiAudioService.getRecordingDuration());
    }, 100);

    return () => clearInterval(interval);
  }, [isRecording]);

  const startRecording = async () => {
    setRecordingError(null); // Clear previous error
    try {
      await tuiAudioService.startRecording();
      setIsRecording(true);
    } catch (error) {
      const errorMsg = error instanceof Error ? error.message : String(error);
      LogService.getInstance().addEntry('error', `Failed to start recording: ${errorMsg}`);
      setRecordingError(errorMsg);
      setIsRecording(false);
    }
  };

  const stopRecording = async (): Promise<RecordingResult> => {
    try {
      const result = await tuiAudioService.stopRecording();
      setIsRecording(false);
      setRecordingDuration(0);
      setRecordingError(null);
      return result;
    } catch (error) {
      setIsRecording(false);
      setRecordingDuration(0);
      const errorMsg = error instanceof Error ? error.message : String(error);
      setRecordingError(errorMsg);
      throw error;
    }
  };

  const cancelRecording = async () => {
    await tuiAudioService.cancelRecording();
    setIsRecording(false);
    setRecordingDuration(0);
    setRecordingError(null);
  };

  const clearError = () => {
    setRecordingError(null);
  };

  const formatDuration = (ms: number): string => {
    const totalSeconds = Math.floor(ms / 1000);
    const minutes = Math.floor(totalSeconds / 60);
    const seconds = totalSeconds % 60;
    return `${minutes}:${seconds.toString().padStart(2, '0')}`;
  };

  return {
    isRecording,
    recordingDuration,
    recordingError,
    startRecording,
    stopRecording,
    cancelRecording,
    clearError,
    formatDuration,
  };
}
