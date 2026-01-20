import '../styles/animations.css';

interface RecordingIndicatorProps {
  duration: number;
  contactName: string;
  isSpaceHeld: boolean;
  isSending?: boolean;
  error?: string | null;
}

export function RecordingIndicator({
  duration,
  contactName,
  isSpaceHeld,
  isSending = false,
  error = null,
}: RecordingIndicatorProps) {
  const formatDuration = (seconds: number): string => {
    const mins = Math.floor(seconds / 60);
    const secs = Math.floor(seconds % 60);
    return `${mins}:${secs.toString().padStart(2, '0')}`;
  };

  // Determine state text and color
  let stateText = 'REC';
  let stateColor = 'var(--color-recording)';
  let hint = 'Release to send';

  if (isSending) {
    stateText = 'Sending...';
    stateColor = 'var(--color-accent)';
    hint = 'Uploading voice message';
  } else if (error) {
    stateText = 'Error';
    stateColor = 'var(--color-error)';
    hint = error;
  } else if (isSpaceHeld) {
    hint = 'Release to send';
  }

  return (
    <div
      className="recording-banner"
      data-state={isSending ? 'sending' : error ? 'error' : 'recording'}
    >
      <span className="recording-dot" style={{ color: stateColor }}>
        ●
      </span>
      <span style={{ color: stateColor }}>{stateText}</span>
      <span>{formatDuration(duration)}</span>
      <span>→ {contactName}</span>
      <span className="recording-hint">{hint}</span>
      <style>{`
        .recording-banner {
          position: fixed;
          top: 0;
          left: 0;
          right: 0;
          background-color: var(--color-surface);
          border-bottom: 2px solid ${stateColor};
          padding: var(--spacing-md) var(--spacing-lg);
          display: flex;
          align-items: center;
          gap: var(--spacing-md);
          font-size: var(--font-size-lg);
          font-weight: 600;
          z-index: 1000;
          box-shadow: 0 4px 20px ${error ? 'rgba(255, 153, 0, 0.3)' : isSending ? 'rgba(0, 170, 255, 0.3)' : 'rgba(255, 51, 51, 0.3)'};
          transition: border-color 0.2s ease, box-shadow 0.2s ease;
        }

        .recording-dot {
          font-size: var(--font-size-xl);
        }

        .recording-hint {
          margin-left: auto;
          font-size: var(--font-size-sm);
          color: ${error ? 'var(--color-error)' : 'var(--color-text-muted)'};
          font-weight: 400;
        }
      `}</style>
    </div>
  );
}
