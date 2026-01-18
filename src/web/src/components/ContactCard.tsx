import React, { useState, useRef, useCallback } from 'react';
import type { Contact } from '../types.js';
import '../styles/contact-card.css';

interface ContactCardProps {
  contact: Contact;
  isSelected: boolean;
  isDimmed: boolean;
  isRecording: boolean;
  onSelect: () => void;
  onStartRecording: (ripplePosition: { x: number; y: number }) => void;
  onStopRecording: () => void;
  onCancelRecording: () => void;
}

export function ContactCard({
  contact,
  isSelected,
  isDimmed,
  isRecording,
  onSelect,
  onStartRecording,
  onStopRecording,
  onCancelRecording,
}: ContactCardProps) {
  const [localRecording, setLocalRecording] = useState(false);
  const [ripplePosition, setRipplePosition] = useState<{ x: number; y: number } | null>(null);
  const cardRef = useRef<HTMLDivElement>(null);
  const recordingTimeoutRef = useRef<number | null>(null);

  const handlePointerDown = useCallback((e: React.MouseEvent | React.TouchEvent) => {
    if (isRecording) return;

    // Get coordinates for ripple
    const clientX = 'touches' in e ? e.touches[0].clientX : e.clientX;
    const clientY = 'touches' in e ? e.touches[0].clientY : e.clientY;

    const rect = cardRef.current?.getBoundingClientRect();
    if (rect) {
      const x = clientX - rect.left - rect.width / 2;
      const y = clientY - rect.top - rect.height / 2;
      setRipplePosition({ x, y });
    }

    // Start recording with a small delay to show ripple first
    setLocalRecording(true);
    recordingTimeoutRef.current = window.setTimeout(() => {
      onStartRecording({ x: clientX, y: clientY });
    }, 100);
  }, [isRecording, onStartRecording]);

  const handlePointerUp = useCallback(() => {
    if (recordingTimeoutRef.current) {
      clearTimeout(recordingTimeoutRef.current);
      recordingTimeoutRef.current = null;
    }

    if (localRecording) {
      setLocalRecording(false);
      onStopRecording();
    }

    setRipplePosition(null);
  }, [localRecording, onStopRecording]);

  const handlePointerLeave = useCallback(() => {
    if (localRecording) {
      // Cancel if dragged outside
      if (recordingTimeoutRef.current) {
        clearTimeout(recordingTimeoutRef.current);
        recordingTimeoutRef.current = null;
      }
      setLocalRecording(false);
      onCancelRecording();
    }
    setRipplePosition(null);
  }, [localRecording, onCancelRecording]);

  const handleClick = useCallback((e: React.MouseEvent) => {
    // Don't trigger select if we were recording
    if (localRecording) {
      e.stopPropagation();
      return;
    }
    onSelect();
  }, [localRecording, onSelect]);

  const handlePttButtonMouseDown = useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    handlePointerDown(e);
  }, [handlePointerDown]);

  const handlePttButtonMouseUp = useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    handlePointerUp();
  }, [handlePointerUp]);

  const cardClasses = [
    'contact-card',
    isSelected && 'contact-card--selected',
    (isRecording || localRecording) && 'contact-card--recording',
    isDimmed && !localRecording && 'contact-card--dimmed',
    contact.hasError && 'contact-card--error',
  ].filter(Boolean).join(' ');

  return (
    <div
      ref={cardRef}
      className={cardClasses}
      onClick={handleClick}
      onMouseDown={handlePointerDown}
      onMouseUp={handlePointerUp}
      onMouseLeave={handlePointerLeave}
      onTouchStart={handlePointerDown}
      onTouchEnd={handlePointerUp}
      onTouchCancel={handlePointerLeave}
    >
      {/* Ripple animation */}
      {ripplePosition && (
        <div
          className="recording-ripple"
          style={{
            left: '50%',
            top: '50%',
            width: '100px',
            height: '100px',
            marginLeft: '-50px',
            marginTop: '-50px',
          }}
        />
      )}

      <div className="contact-card-left">
        {isSelected && (
          <div className="selection-indicator">▶</div>
        )}
        <div className="contact-info">
          <div className="contact-name">{contact.name}</div>
          <div className="contact-status">
            {contact.type === 'family' && '📢 '}
            {contact.unreadCount && (
              <span className="status-indicator status-indicator--unread">
                ● {contact.unreadCount} new
              </span>
            )}
            {contact.hasError && (
              <span className="status-indicator status-indicator--error">
                ⚠ Send error
              </span>
            )}
          </div>
        </div>
      </div>

      <div className="contact-card-right">
        <button
          className={`ptt-button ${localRecording ? 'ptt-button--recording' : ''}`}
          onMouseDown={handlePttButtonMouseDown}
          onMouseUp={handlePttButtonMouseUp}
          aria-label="Push to talk"
        >
          <svg viewBox="0 0 24 24">
            <path d="M12 14c1.66 0 3-1.34 3-3V5c0-1.66-1.34-3-3-3S9 3.34 9 5v6c0 1.66 1.34 3 3 3z"/>
            <path d="M17 11c0 2.76-2.24 5-5 5s-5-2.24-5-5H5c0 3.53 2.61 6.43 6 6.92V21h2v-3.08c3.39-.49 6-3.39 6-6.92h-2z"/>
          </svg>
        </button>
      </div>
    </div>
  );
}
