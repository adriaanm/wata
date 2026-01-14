import { useState } from 'react';
import { tuiAudioService } from '../services/TuiAudioService.js';

/**
 * Hook for audio playback in TUI
 */
export function useAudioPlayer() {
  const [isPlaying, setIsPlaying] = useState(false);
  const [currentUri, setCurrentUri] = useState<string | null>(null);

  const play = async (uri: string) => {
    try {
      await tuiAudioService.startPlayback(uri);
      setIsPlaying(true);
      setCurrentUri(uri);

      // Poll for playback completion (afplay doesn't provide real-time status)
      const checkInterval = setInterval(() => {
        if (!tuiAudioService.getIsPlaying()) {
          setIsPlaying(false);
          setCurrentUri(null);
          clearInterval(checkInterval);
        }
      }, 100);
    } catch (error) {
      console.error('Failed to play audio:', error);
      setIsPlaying(false);
      setCurrentUri(null);
    }
  };

  const stop = async () => {
    await tuiAudioService.stopPlayback();
    setIsPlaying(false);
    setCurrentUri(null);
  };

  return {
    isPlaying,
    currentUri,
    play,
    stop,
  };
}
