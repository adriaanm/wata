/**
 * Push-to-Talk (PTT) Hook
 *
 * Manages the PTT recording flow for voice messages:
 * - Records audio when the user holds space bar or touch
 * - Uploads the audio to Matrix when released
 * - Handles errors and cancellation
 *
 * Integrates with:
 * - useAudioRecorder for audio recording
 * - WataService for voice message sending
 */

import { useState, useCallback, useEffect } from 'react';

import { matrixService } from '../services/matrixService.js';
import type { RecordingState } from '../types.js';

import { useAudioRecorder } from './useAudioRecorder.js';

interface UsePttOptions {
  onStartRecording?: (contactId: string) => void;
  onStopRecording?: (contactId: string, duration: number) => void;
  onSendError?: (error: Error) => void;
}

export function usePtt({
  onStartRecording,
  onStopRecording,
  onSendError,
}: UsePttOptions = {}) {
  const [pttState, setPttState] = useState<RecordingState>('idle');
  const [recordingContactId, setRecordingContactId] = useState<string | null>(
    null,
  );
  const [isSpaceHeld, setIsSpaceHeld] = useState(false);
  const [sendError, setSendError] = useState<string | null>(null);

  const {
    recordingState,
    startRecording: startAudioRecording,
    stopRecording: stopAudioRecording,
    cancelRecording: cancelAudioRecording,
    reset: resetAudioRecorder,
    isRecording,
    isProcessing,
    duration,
  } = useAudioRecorder({
    onError: error => {
      setSendError(error.message);
      setPttState('idle');
      setRecordingContactId(null);
      onSendError?.(error);
    },
  });

  // Resolve contact ID to Matrix room ID
  const getRoomId = useCallback(async (contactId: string): Promise<string> => {
    if (contactId === 'family') {
      const roomId = await matrixService.getFamilyRoomId();
      if (!roomId) {
        throw new Error('Family room not found');
      }
      return roomId;
    }
    // For individual contacts, get or create DM room
    return await matrixService.getOrCreateDmRoom(contactId);
  }, []);

  const startRecording = useCallback(
    async (contactId: string) => {
      try {
        setPttState('starting');
        setRecordingContactId(contactId);
        setSendError(null);
        resetAudioRecorder();

        // Start audio recording
        await startAudioRecording();

        setPttState('recording');
        onStartRecording?.(contactId);
      } catch (error) {
        const errorMessage =
          error instanceof Error ? error.message : 'Failed to start recording';
        setSendError(errorMessage);
        setPttState('idle');
        setRecordingContactId(null);
        onSendError?.(error instanceof Error ? error : new Error(errorMessage));
      }
    },
    [startAudioRecording, resetAudioRecorder, onStartRecording, onSendError],
  );

  const stopRecording = useCallback(async () => {
    if (pttState === 'idle' || pttState === 'starting') return;

    const contactId = recordingContactId;
    if (!contactId) return;

    try {
      setPttState('sending');
      setSendError(null);

      // Stop audio recording and get the audio data
      const result = await stopAudioRecording();

      // Get the Matrix room ID for this contact
      const roomId = await getRoomId(contactId);

      // Send the voice message to Matrix
      await matrixService.sendVoiceMessage(
        roomId,
        result.data,
        result.mimeType,
        result.duration,
        result.size,
      );

      // Success
      setPttState('idle');
      setRecordingContactId(null);
      setIsSpaceHeld(false);

      if (contactId) {
        onStopRecording?.(contactId, result.duration);
      }
    } catch (error) {
      const errorMessage =
        error instanceof Error ? error.message : 'Failed to send voice message';
      setSendError(errorMessage);
      setPttState('idle');
      setRecordingContactId(null);
      setIsSpaceHeld(false);
      onSendError?.(error instanceof Error ? error : new Error(errorMessage));
    }
  }, [
    pttState,
    recordingContactId,
    stopAudioRecording,
    getRoomId,
    onStopRecording,
    onSendError,
  ]);

  const cancelRecording = useCallback(() => {
    cancelAudioRecording();
    setPttState('idle');
    setRecordingContactId(null);
    setIsSpaceHeld(false);
    setSendError(null);
  }, [cancelAudioRecording]);

  /**
   * Clear any send errors
   */
  const clearError = useCallback(() => {
    setSendError(null);
  }, []);

  // Handle space bar for PTT (keyboard-based recording)
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === ' ' && !isSpaceHeld && pttState === 'idle') {
        e.preventDefault();
        setIsSpaceHeld(true);
        // Note: In real app, this would use the selected contact
        // The parent component (MainView) handles actual recording start
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
  }, [isSpaceHeld, pttState]);

  return {
    pttState,
    recordingState,
    recordingDuration: duration,
    recordingContactId,
    isSpaceHeld,
    sendError,
    startRecording,
    stopRecording,
    cancelRecording,
    clearError,
    // Convenience getters
    isRecording: pttState === 'recording' || isRecording,
    isSending: pttState === 'sending',
    isProcessing: pttState === 'sending' || isProcessing,
    hasError: !!sendError,
  };
}
