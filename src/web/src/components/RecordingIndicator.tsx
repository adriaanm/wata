import React from 'react';
import '../styles/animations.css';

interface RecordingIndicatorProps {
  duration: number;
  contactName: string;
  isSpaceHeld: boolean;
}

export function RecordingIndicator({ duration, contactName, isSpaceHeld }: RecordingIndicatorProps) {
  const formatDuration = (seconds: number): string => {
    const mins = Math.floor(seconds / 60);
    const secs = seconds % 60;
    return `${mins}:${secs.toString().padStart(2, '0')}`;
  };

  return (
    <div className="recording-banner">
      <span className="recording-dot">●</span>
      <span>REC</span>
      <span>{formatDuration(duration)}</span>
      <span>→ {contactName}</span>
      <span className="recording-hint">
        {isSpaceHeld ? 'Release to send' : 'Release to send'}
      </span>
      <style>{`
        .recording-banner {
          position: fixed;
          top: 0;
          left: 0;
          right: 0;
          background-color: var(--color-surface);
          border-bottom: 2px solid var(--color-recording);
          padding: var(--spacing-md) var(--spacing-lg);
          display: flex;
          align-items: center;
          gap: var(--spacing-md);
          font-size: var(--font-size-lg);
          font-weight: 600;
          z-index: 1000;
          box-shadow: 0 4px 20px rgba(255, 51, 51, 0.3);
        }

        .recording-dot {
          color: var(--color-recording);
          font-size: var(--font-size-xl);
        }

        .recording-hint {
          margin-left: auto;
          font-size: var(--font-size-sm);
          color: var(--color-text-muted);
          font-weight: 400;
        }
      `}</style>
    </div>
  );
}
