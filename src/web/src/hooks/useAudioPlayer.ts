/**
 * Audio Player Hook
 *
 * Integrates WebAudioService playback with React components.
 * Provides controls for playing, pausing, seeking, and volume control.
 * Emits events for playback state changes.
 */

import { useState, useCallback, useRef, useEffect } from 'react';

import {
  webAudioService,
  type PlaybackState,
  type PlaybackOptions,
} from '../services/WebAudioService.js';

export interface PlayerState {
  playbackState: PlaybackState;
  currentTime: number;
  duration: number;
  volume: number;
  error: string | null;
}

export interface UseAudioPlayerOptions {
  onEnded?: () => void;
  onError?: (error: Error) => void;
  onTimeUpdate?: (currentTime: number) => void;
  autoPlay?: boolean;
}

export function useAudioPlayer(options: UseAudioPlayerOptions = {}) {
  const [playerState, setPlayerState] = useState<PlayerState>({
    playbackState: 'idle',
    currentTime: 0,
    duration: 0,
    volume: 1.0,
    error: null,
  });

  const currentUrlRef = useRef<string | null>(null);
  const updateIntervalRef = useRef<number | null>(null);

  // Clean up on unmount
  useEffect(() => {
    return () => {
      if (updateIntervalRef.current) {
        clearInterval(updateIntervalRef.current);
      }
      webAudioService.stopAudio();
    };
  }, []);

  // Start tracking playback progress
  const startProgressTracking = useCallback(() => {
    if (updateIntervalRef.current) {
      clearInterval(updateIntervalRef.current);
    }

    updateIntervalRef.current = window.setInterval(() => {
      setPlayerState(prev => ({
        ...prev,
        currentTime: webAudioService.getCurrentTime(),
        duration: webAudioService.getDuration(),
      }));
    }, 100); // Update every 100ms
  }, []);

  // Stop tracking playback progress
  const stopProgressTracking = useCallback(() => {
    if (updateIntervalRef.current) {
      clearInterval(updateIntervalRef.current);
      updateIntervalRef.current = null;
    }
  }, []);

  /**
   * Load and play audio from a URL
   */
  const play = useCallback(
    async (url: string) => {
      try {
        currentUrlRef.current = url;
        setPlayerState(prev => ({
          ...prev,
          playbackState: 'playing',
          currentTime: 0,
          duration: 0,
          error: null,
        }));

        const playbackOptions: PlaybackOptions = {
          onEnded: () => {
            stopProgressTracking();
            setPlayerState(prev => ({
              ...prev,
              playbackState: 'ended',
              currentTime: 0,
            }));
            options.onEnded?.();
          },
          onError: error => {
            stopProgressTracking();
            setPlayerState(prev => ({
              ...prev,
              playbackState: 'idle',
              error: error.message,
            }));
            options.onError?.(error);
          },
          onTimeUpdate: currentTime => {
            options.onTimeUpdate?.(currentTime);
          },
        };

        await webAudioService.playAudio(url, playbackOptions);
        startProgressTracking();
      } catch (error) {
        const errorMessage =
          error instanceof Error ? error.message : 'Failed to play audio';
        setPlayerState(prev => ({
          ...prev,
          playbackState: 'idle',
          error: errorMessage,
        }));
        options.onError?.(
          error instanceof Error ? error : new Error(errorMessage),
        );
      }
    },
    [options, startProgressTracking, stopProgressTracking],
  );

  /**
   * Pause the currently playing audio
   */
  const pause = useCallback(() => {
    webAudioService.pauseAudio();
    setPlayerState(prev => ({
      ...prev,
      playbackState: 'paused',
    }));
  }, []);

  /**
   * Resume paused audio
   */
  const resume = useCallback(() => {
    webAudioService.resumeAudio();
    setPlayerState(prev => ({
      ...prev,
      playbackState: 'playing',
    }));
  }, []);

  /**
   * Stop playback and reset to beginning
   */
  const stop = useCallback(() => {
    stopProgressTracking();
    webAudioService.stopAudio();
    currentUrlRef.current = null;
    setPlayerState({
      playbackState: 'idle',
      currentTime: 0,
      duration: 0,
      volume: playerState.volume,
      error: null,
    });
  }, [playerState.volume, stopProgressTracking]);

  /**
   * Seek to a specific time in seconds
   */
  const seek = useCallback((time: number) => {
    webAudioService.seekTo(time);
    setPlayerState(prev => ({
      ...prev,
      currentTime: time,
    }));
  }, []);

  /**
   * Set the volume (0.0 to 1.0)
   */
  const setVolume = useCallback((volume: number) => {
    const clampedVolume = Math.max(0, Math.min(1, volume));
    webAudioService.setVolume(clampedVolume);
    setPlayerState(prev => ({
      ...prev,
      volume: clampedVolume,
    }));
  }, []);

  /**
   * Toggle between play and pause
   */
  const toggle = useCallback(() => {
    const currentState = webAudioService.getPlaybackState();
    if (currentState === 'playing') {
      pause();
    } else if (currentState === 'paused' && currentUrlRef.current) {
      resume();
    }
  }, [pause, resume]);

  return {
    playerState,
    play,
    pause,
    resume,
    stop,
    seek,
    setVolume,
    toggle,
    // Convenience getters
    isPlaying: playerState.playbackState === 'playing',
    isPaused: playerState.playbackState === 'paused',
    isEnded: playerState.playbackState === 'ended',
    isIdle: playerState.playbackState === 'idle',
    currentTime: playerState.currentTime,
    duration: playerState.duration,
    volume: playerState.volume,
    error: playerState.error,
  };
}
