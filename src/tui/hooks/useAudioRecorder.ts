import { useState, useEffect, useRef } from 'react';
import { pvRecorderAudioService } from '../services/PvRecorderAudioService.js';
import type { RecordingResult } from '../services/PvRecorderAudioService.js';
import { LogService } from '../services/LogService.js';

/**
 * Hook for audio recording in TUI using PvRecorder + Opus
 */
export function useAudioRecorder() {
  const [isRecording, setIsRecording] = useState(false);
  const [recordingDuration, setRecordingDuration] = useState(0);
  const [recordingError, setRecordingError] = useState<string | null>(null);
  const recordingLoopRef = useRef<boolean>(false);

  // Recording loop - continuously captures audio frames while recording
  useEffect(() => {
    if (!isRecording) {
      recordingLoopRef.current = false;
      return;
    }

    recordingLoopRef.current = true;

    const runRecordingLoop = async () => {
      while (
        recordingLoopRef.current &&
        pvRecorderAudioService.getIsRecording()
      ) {
        await pvRecorderAudioService.recordFrame();
      }
    };

    runRecordingLoop();

    return () => {
      recordingLoopRef.current = false;
    };
  }, [isRecording]);

  // Update recording duration every 100ms
  useEffect(() => {
    if (!isRecording) {
      setRecordingDuration(0);
      return;
    }

    const interval = setInterval(() => {
      setRecordingDuration(pvRecorderAudioService.getRecordingDuration());
    }, 100);

    return () => clearInterval(interval);
  }, [isRecording]);

  const startRecording = async () => {
    setRecordingError(null); // Clear previous error
    try {
      await pvRecorderAudioService.startRecording();
      setIsRecording(true);
    } catch (error) {
      const errorMsg = error instanceof Error ? error.message : String(error);
      LogService.getInstance().addEntry(
        'error',
        `Failed to start recording: ${errorMsg}`,
      );
      setRecordingError(errorMsg);
      setIsRecording(false);
    }
  };

  const stopRecording = async (): Promise<RecordingResult> => {
    recordingLoopRef.current = false; // Stop the recording loop
    try {
      const result = await pvRecorderAudioService.stopRecording();
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
    recordingLoopRef.current = false; // Stop the recording loop
    await pvRecorderAudioService.cancelRecording();
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
