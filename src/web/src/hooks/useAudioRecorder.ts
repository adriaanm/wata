/**
 * Audio Recorder Hook
 *
 * Integrates WebAudioService with React components for voice message recording.
 * Handles the full recording lifecycle:
 * - Request microphone access
 * - Record audio with duration tracking
 * - Return recorded audio buffer for Matrix upload
 * - Handle errors and cancellation
 *
 * This hook is designed to work with the usePtt hook for PTT (push-to-talk) functionality.
 */

import { useState, useCallback, useRef, useEffect } from 'react';
import { webAudioService } from '../services/WebAudioService.js';
import type { RecordingResult } from '../services/WebAudioService.js';

export interface RecordingState {
  isRecording: boolean;
  isProcessing: boolean;
  duration: number;
  error: string | null;
}

export interface UseAudioRecorderOptions {
  onError?: (error: Error) => void;
  onDurationUpdate?: (duration: number) => void;
}

export function useAudioRecorder(options: UseAudioRecorderOptions = {}) {
  const [recordingState, setRecordingState] = useState<RecordingState>({
    isRecording: false,
    isProcessing: false,
    duration: 0,
    error: null,
  });

  const startTimeRef = useRef<number | null>(null);
  const durationIntervalRef = useRef<number | null>(null);

  // Clear interval on unmount
  useEffect(() => {
    return () => {
      if (durationIntervalRef.current) {
        clearInterval(durationIntervalRef.current);
      }
    };
  }, []);

  // Update duration every 100ms while recording
  const startDurationTracking = useCallback(() => {
    startTimeRef.current = Date.now();

    durationIntervalRef.current = window.setInterval(() => {
      if (startTimeRef.current) {
        const duration = (Date.now() - startTimeRef.current) / 1000;
        setRecordingState(prev => ({ ...prev, duration }));
        options.onDurationUpdate?.(duration);
      }
    }, 100);
  }, [options]);

  const stopDurationTracking = useCallback(() => {
    if (durationIntervalRef.current) {
      clearInterval(durationIntervalRef.current);
      durationIntervalRef.current = null;
    }
    startTimeRef.current = null;
  }, []);

  /**
   * Start recording audio from the microphone
   * Requests microphone access if not already granted
   */
  const startRecording = useCallback(async () => {
    try {
      setRecordingState(prev => ({
        ...prev,
        isRecording: true,
        isProcessing: false,
        duration: 0,
        error: null,
      }));

      await webAudioService.startRecording();
      startDurationTracking();
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Failed to start recording';
      setRecordingState(prev => ({
        ...prev,
        isRecording: false,
        error: errorMessage,
      }));
      options.onError?.(error instanceof Error ? error : new Error(errorMessage));
    }
  }, [startDurationTracking, options]);

  /**
   * Stop recording and return the audio data
   * Returns a RecordingResult with buffer, mimeType, duration, and size
   */
  const stopRecording = useCallback(async (): Promise<RecordingResult> => {
    try {
      stopDurationTracking();

      setRecordingState(prev => ({
        ...prev,
        isRecording: false,
        isProcessing: true,
      }));

      const result = await webAudioService.stopRecording();

      setRecordingState(prev => ({
        ...prev,
        isProcessing: false,
        duration: result.duration,
      }));

      return result;
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : 'Failed to stop recording';
      setRecordingState(prev => ({
        ...prev,
        isRecording: false,
        isProcessing: false,
        error: errorMessage,
      }));
      options.onError?.(error instanceof Error ? error : new Error(errorMessage));
      throw error;
    }
  }, [stopDurationTracking, options]);

  /**
   * Cancel the current recording without saving
   */
  const cancelRecording = useCallback(() => {
    stopDurationTracking();
    webAudioService.cancelRecording();
    setRecordingState({
      isRecording: false,
      isProcessing: false,
      duration: 0,
      error: null,
    });
  }, [stopDurationTracking]);

  /**
   * Reset the recording state (clear errors, etc.)
   */
  const reset = useCallback(() => {
    setRecordingState({
      isRecording: false,
      isProcessing: false,
      duration: 0,
      error: null,
    });
  }, []);

  return {
    recordingState,
    startRecording,
    stopRecording,
    cancelRecording,
    reset,
    // Convenience getters
    isRecording: recordingState.isRecording,
    isProcessing: recordingState.isProcessing,
    duration: recordingState.duration,
    error: recordingState.error,
  };
}
