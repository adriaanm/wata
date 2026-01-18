import { useState, useCallback, useRef, useEffect } from 'react';
import type { RecordingState } from '../types.js';

interface UsePttOptions {
  onStartRecording?: (contactId: string) => void;
  onStopRecording?: (contactId: string, duration: number) => void;
  onCancelRecording?: () => void;
}

export function usePtt({ onStartRecording, onStopRecording, onCancelRecording }: UsePttOptions = {}) {
  const [recordingState, setRecordingState] = useState<RecordingState>('idle');
  const [recordingDuration, setRecordingDuration] = useState(0);
  const [recordingContactId, setRecordingContactId] = useState<string | null>(null);
  const [isSpaceHeld, setIsSpaceHeld] = useState(false);

  const recordingStartTimeRef = useRef<number | null>(null);
  const durationIntervalRef = useRef<number | null>(null);

  const startRecording = useCallback((contactId: string) => {
    setRecordingState('starting');
    setRecordingContactId(contactId);
    setRecordingDuration(0);

    // Simulate recording start delay
    setTimeout(() => {
      setRecordingState('recording');
      recordingStartTimeRef.current = Date.now();
      onStartRecording?.(contactId);

      // Start duration counter
      durationIntervalRef.current = window.setInterval(() => {
        if (recordingStartTimeRef.current) {
          setRecordingDuration(Math.floor((Date.now() - recordingStartTimeRef.current) / 1000));
        }
      }, 100);
    }, 100);
  }, [onStartRecording]);

  const stopRecording = useCallback(() => {
    if (recordingState === 'idle' || recordingState === 'starting') return;

    const duration = recordingDuration;
    const contactId = recordingContactId;

    // Clean up
    if (durationIntervalRef.current) {
      clearInterval(durationIntervalRef.current);
      durationIntervalRef.current = null;
    }
    recordingStartTimeRef.current = null;

    setRecordingState('sending');

    // Simulate sending
    setTimeout(() => {
      setRecordingState('idle');
      setRecordingDuration(0);
      setRecordingContactId(null);
      setIsSpaceHeld(false);

      if (contactId) {
        onStopRecording?.(contactId, duration);
      }
    }, 500);
  }, [recordingState, recordingDuration, recordingContactId, onStopRecording]);

  const cancelRecording = useCallback(() => {
    if (durationIntervalRef.current) {
      clearInterval(durationIntervalRef.current);
      durationIntervalRef.current = null;
    }
    recordingStartTimeRef.current = null;

    setRecordingState('idle');
    setRecordingDuration(0);
    setRecordingContactId(null);
    setIsSpaceHeld(false);

    onCancelRecording?.();
  }, [onCancelRecording]);

  // Handle space bar for PTT
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === ' ' && !isSpaceHeld && recordingState === 'idle') {
        e.preventDefault();
        setIsSpaceHeld(true);
        // Note: In real app, this would use the selected contact
        // For mock, we'll pass 'selected' as a placeholder
      }
    };

    const handleKeyUp = (e: KeyboardEvent) => {
      if (e.key === ' ' && isSpaceHeld) {
        e.preventDefault();
        setIsSpaceHeld(false);
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    window.addEventListener('keyup', handleKeyUp);
    return () => {
      window.removeEventListener('keydown', handleKeyDown);
      window.removeEventListener('keyup', handleKeyUp);
    };
  }, [isSpaceHeld, recordingState]);

  // Clear interval on unmount
  useEffect(() => {
    return () => {
      if (durationIntervalRef.current) {
        clearInterval(durationIntervalRef.current);
      }
    };
  }, []);

  return {
    recordingState,
    recordingDuration,
    recordingContactId,
    isSpaceHeld,
    startRecording,
    stopRecording,
    cancelRecording,
    isRecording: recordingState === 'recording' || recordingState === 'sending',
  };
}
