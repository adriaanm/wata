import { useState } from 'react';

import { LogService } from '../services/LogService.js';
import { pvRecorderAudioService } from '../services/PvRecorderAudioService.js';

/**
 * Hook for audio playback in TUI using PvRecorderAudioService
 * @param accessToken - Optional access token for authenticated media downloads
 */
export function useAudioPlayer(accessToken?: string) {
  const [isPlaying, setIsPlaying] = useState(false);
  const [currentUri, setCurrentUri] = useState<string | null>(null);
  const [playbackError, setPlaybackError] = useState<string | null>(null);

  const play = async (uri: string) => {
    setPlaybackError(null); // Clear previous error
    try {
      await pvRecorderAudioService.startPlayback(uri, accessToken);
      setIsPlaying(true);
      setCurrentUri(uri);

      // Poll for playback completion (afplay doesn't provide real-time status)
      const checkInterval = setInterval(() => {
        if (!pvRecorderAudioService.getIsPlaying()) {
          setIsPlaying(false);
          setCurrentUri(null);
          clearInterval(checkInterval);
        }
      }, 100);
    } catch (error) {
      // Log error message without stack trace
      const errorMsg = error instanceof Error ? error.message : String(error);
      LogService.getInstance().addEntry(
        'error',
        `Failed to play audio: ${errorMsg}`,
      );
      setPlaybackError(errorMsg);
      setIsPlaying(false);
      setCurrentUri(null);
    }
  };

  const stop = async () => {
    await pvRecorderAudioService.stopPlayback();
    setIsPlaying(false);
    setCurrentUri(null);
    setPlaybackError(null);
  };

  const clearError = () => {
    setPlaybackError(null);
  };

  return {
    isPlaying,
    currentUri,
    playbackError,
    play,
    stop,
    clearError,
  };
}
