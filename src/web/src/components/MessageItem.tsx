/**
 * MessageItem Component
 *
 * Individual voice message row with playback controls.
 * Shows sender, timestamp, duration, play/pause button, and progress bar.
 */

import type { VoiceMessage } from '@shared/services/MatrixService';
import { useEffect, useCallback, useRef } from 'react';

import { useAudioPlayer } from '../hooks/useAudioPlayer.js';
import { matrixService } from '../services/matrixService.js';

interface MessageItemProps {
  message: VoiceMessage;
  isPlaying: boolean;
  onPlay: () => void;
  onStop: () => void;
}

export function MessageItem({
  message,
  isPlaying,
  onPlay,
  onStop,
}: MessageItemProps) {
  const progressBarRef = useRef<HTMLDivElement>(null);

  const {
    play,
    pause,
    resume,
    stop,
    seek,
    playerState,
    isPlaying: isActuallyPlaying,
    isPaused,
    currentTime,
    duration: playbackDuration,
  } = useAudioPlayer({
    onEnded: onStop,
    onError: error => {
      console.error('Playback error:', error);
      onStop();
    },
  });

  // Get HTTP URL for audio playback with authorization
  const getAuthenticatedUrl = useCallback(() => {
    // The audioUrl from MatrixService already has the HTTP URL
    // For authenticated media, we need to add the access token
    const accessToken = matrixService.getAccessToken();
    if (accessToken && message.audioUrl) {
      const url = new URL(message.audioUrl);
      url.searchParams.set('access_token', accessToken);
      return url.toString();
    }
    return message.audioUrl;
  }, [message.audioUrl]);

  // Handle play/pause toggle
  const handlePlayPause = useCallback(async () => {
    if (isActuallyPlaying) {
      pause();
    } else if (isPaused) {
      resume();
    } else {
      onPlay();
      try {
        const url = getAuthenticatedUrl();
        await play(url);
      } catch (error) {
        console.error('Failed to play:', error);
        onStop();
      }
    }
  }, [
    isActuallyPlaying,
    isPaused,
    play,
    pause,
    resume,
    onPlay,
    onStop,
    getAuthenticatedUrl,
  ]);

  // Stop playback when another message starts playing
  useEffect(() => {
    if (!isPlaying && isActuallyPlaying) {
      stop();
    }
  }, [isPlaying, isActuallyPlaying, stop]);

  // Handle seeking on progress bar click
  const handleSeek = useCallback(
    (e: React.MouseEvent<HTMLDivElement>) => {
      if (!progressBarRef.current || playbackDuration === 0) return;

      const rect = progressBarRef.current.getBoundingClientRect();
      const clickX = e.clientX - rect.left;
      const percentage = clickX / rect.width;
      const newTime = percentage * playbackDuration;

      seek(newTime);
    },
    [seek, playbackDuration],
  );

  // Format timestamp
  const formatTime = (timestamp: number) => {
    const date = new Date(timestamp);
    return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  };

  // Format duration in seconds to mm:ss
  const formatDuration = (ms: number) => {
    const totalSeconds = Math.floor(ms / 1000);
    const minutes = Math.floor(totalSeconds / 60);
    const seconds = totalSeconds % 60;
    return `${minutes}:${seconds.toString().padStart(2, '0')}`;
  };

  // Calculate progress percentage
  const progress =
    playbackDuration > 0 ? (currentTime / playbackDuration) * 100 : 0;

  // Use original duration from message, or playback duration once loaded
  const displayDuration =
    playbackDuration > 0 ? playbackDuration : message.duration / 1000;

  return (
    <div
      className={`message-item ${message.isOwn ? 'message-item--own' : 'message-item--received'}`}
    >
      {/* Message header: sender and timestamp */}
      <div className="message-header">
        <span className="message-sender">
          {message.isOwn ? 'You' : message.senderName}
        </span>
        <span className="message-time">{formatTime(message.timestamp)}</span>
      </div>

      {/* Playback controls */}
      <div className="message-controls">
        <button
          className={`play-button ${isActuallyPlaying ? 'play-button--playing' : ''}`}
          onClick={handlePlayPause}
          aria-label={isActuallyPlaying ? 'Pause' : 'Play'}
        >
          {isActuallyPlaying ? (
            <span className="pause-icon">❚❚</span>
          ) : (
            <span className="play-icon">▶</span>
          )}
        </button>

        {/* Progress bar */}
        <div
          className="progress-bar"
          ref={progressBarRef}
          onClick={handleSeek}
          role="slider"
          aria-label="Playback progress"
          aria-valuenow={progress}
          aria-valuemin={0}
          aria-valuemax={100}
        >
          <div className="progress-track">
            <div className="progress-fill" style={{ width: `${progress}%` }} />
          </div>
        </div>

        {/* Duration display */}
        <span className="message-duration">
          {isActuallyPlaying || isPaused
            ? formatDuration(currentTime * 1000)
            : formatDuration(displayDuration * 1000)}
        </span>
      </div>

      {/* Error state */}
      {playerState.error && (
        <div className="message-error">Failed to play: {playerState.error}</div>
      )}

      <style>{`
        .message-item {
          display: flex;
          flex-direction: column;
          gap: var(--spacing-sm);
          padding: var(--spacing-md);
          background-color: var(--color-surface);
          border-radius: 12px;
          transition: background-color var(--transition-fast);
        }

        .message-item--own {
          background-color: var(--color-surface-elevated);
          margin-left: var(--spacing-xl);
        }

        .message-item--received {
          margin-right: var(--spacing-xl);
          border-left: 3px solid var(--color-accent);
        }

        .message-header {
          display: flex;
          justify-content: space-between;
          align-items: center;
        }

        .message-sender {
          font-weight: 600;
          color: var(--color-text);
          font-size: var(--font-size-sm);
        }

        .message-item--own .message-sender {
          color: var(--color-text-muted);
        }

        .message-item--received .message-sender {
          color: var(--color-accent);
        }

        .message-time {
          font-size: var(--font-size-xs);
          color: var(--color-text-muted);
        }

        .message-controls {
          display: flex;
          align-items: center;
          gap: var(--spacing-md);
        }

        .play-button {
          width: 40px;
          height: 40px;
          border-radius: 50%;
          background-color: var(--color-accent);
          border: none;
          color: var(--color-background);
          cursor: pointer;
          display: flex;
          align-items: center;
          justify-content: center;
          transition: transform var(--transition-fast), background-color var(--transition-fast);
          flex-shrink: 0;
        }

        .play-button:hover {
          transform: scale(1.05);
          background-color: var(--color-accent-hover, #0099ee);
        }

        .play-button:active {
          transform: scale(0.95);
        }

        .play-button--playing {
          background-color: var(--color-recording);
        }

        .play-button--playing:hover {
          background-color: var(--color-recording);
        }

        .play-icon {
          font-size: 14px;
          margin-left: 2px; /* Visual centering for play icon */
        }

        .pause-icon {
          font-size: 12px;
          letter-spacing: 2px;
        }

        .progress-bar {
          flex: 1;
          height: 24px;
          display: flex;
          align-items: center;
          cursor: pointer;
          padding: 8px 0;
        }

        .progress-track {
          width: 100%;
          height: 4px;
          background-color: var(--color-surface-elevated);
          border-radius: 2px;
          overflow: hidden;
        }

        .message-item--own .progress-track {
          background-color: var(--color-surface);
        }

        .progress-fill {
          height: 100%;
          background-color: var(--color-accent);
          border-radius: 2px;
          transition: width 0.1s linear;
        }

        .message-duration {
          font-size: var(--font-size-sm);
          color: var(--color-text-muted);
          font-variant-numeric: tabular-nums;
          min-width: 40px;
          text-align: right;
        }

        .message-error {
          font-size: var(--font-size-xs);
          color: var(--color-error);
          padding-top: var(--spacing-xs);
        }

        /* Responsive adjustments */
        @media (max-width: 767px) {
          .message-item--own {
            margin-left: var(--spacing-md);
          }

          .message-item--received {
            margin-right: var(--spacing-md);
          }
        }
      `}</style>
    </div>
  );
}
