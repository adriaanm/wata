import { useState, useRef, useEffect, useCallback } from 'react';

import { LogService } from '../services/LogService.js';

// PTT hold-to-record: detect key release by gap in key repeat events
const PTT_RELEASE_TIMEOUT_MS = 200;

interface RecordingResult {
  buffer: Buffer;
  mimeType: string;
  duration: number;
  size: number;
}

interface UsePttOptions {
  isRecording: boolean;
  startRecording: () => Promise<void>;
  stopRecording: () => Promise<RecordingResult>;
  onSend: (result: RecordingResult) => Promise<void>;
  onRecordingStart?: () => void;
}

/**
 * Hook for push-to-talk functionality.
 * Handles the PTT timing logic (detecting key release via timeout gap).
 */
export function usePtt({
  isRecording,
  startRecording,
  stopRecording,
  onSend,
  onRecordingStart,
}: UsePttOptions) {
  const pttTimeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const isStoppingRef = useRef(false);
  const [isHoldingSpace, setIsHoldingSpace] = useState(false);
  const [sendError, setSendError] = useState<string | null>(null);

  // Stop recording and send message
  const doStopAndSend = useCallback(async () => {
    if (isStoppingRef.current || !isRecording) return;
    isStoppingRef.current = true;

    try {
      const result = await stopRecording();
      await onSend(result);
      setSendError(null);
    } catch (err) {
      const errorMsg = err instanceof Error ? err.message : String(err);
      LogService.getInstance().addEntry('error', `Failed to send: ${errorMsg}`);
      setSendError(errorMsg);
    } finally {
      isStoppingRef.current = false;
    }
  }, [isRecording, stopRecording, onSend]);

  // Clear PTT timeout on unmount or when recording stops
  useEffect(() => {
    if (!isRecording) {
      if (pttTimeoutRef.current) {
        clearTimeout(pttTimeoutRef.current);
        pttTimeoutRef.current = null;
      }
      setIsHoldingSpace(false);
    }
    return () => {
      if (pttTimeoutRef.current) {
        clearTimeout(pttTimeoutRef.current);
      }
    };
  }, [isRecording]);

  /**
   * Call this when space bar is pressed.
   * Handles the PTT timing logic for hold-to-record.
   */
  const handleSpacePress = useCallback(() => {
    if (isRecording) {
      setIsHoldingSpace(true);
      if (pttTimeoutRef.current) {
        clearTimeout(pttTimeoutRef.current);
      }
      pttTimeoutRef.current = setTimeout(() => {
        doStopAndSend();
      }, PTT_RELEASE_TIMEOUT_MS);
    } else {
      // Clear any previous send error when starting a new recording
      setSendError(null);
      onRecordingStart?.();
      startRecording().catch(err => {
        const errorMsg = err instanceof Error ? err.message : String(err);
        LogService.getInstance().addEntry(
          'error',
          `Failed to start recording: ${errorMsg}`,
        );
      });
      pttTimeoutRef.current = setTimeout(() => {
        doStopAndSend();
      }, PTT_RELEASE_TIMEOUT_MS);
    }
  }, [isRecording, startRecording, doStopAndSend, onRecordingStart]);

  return {
    isHoldingSpace,
    sendError,
    setSendError,
    handleSpacePress,
  };
}
