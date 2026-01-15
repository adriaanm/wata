import { useState } from 'react';
import { tuiAudioService } from '../services/TuiAudioService.js';
import { LogService } from '../services/LogService.js';

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
      // Log error message without stack trace
      const errorMsg = error instanceof Error ? error.message : String(error);
      LogService.getInstance().addEntry('error', `Failed to play audio: ${errorMsg}`);
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
