/**
 * HistoryView Component
 *
 * Displays the message history for a contact.
 * Shows a list of voice messages with playback controls.
 */

import { useEffect, useState, useCallback, useMemo } from 'react';

import type { VoiceMessage } from '@shared/services/MatrixService';
import { useVoiceMessages } from '../hooks/useMatrix.js';
import { matrixService } from '../services/matrixService.js';
import type { Contact } from '../types.js';
import { MessageItem } from './MessageItem.js';

interface HistoryViewProps {
  contact: Contact;
  onBack: () => void;
}

export function HistoryView({ contact, onBack }: HistoryViewProps) {
  const [roomId, setRoomId] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Resolve contact to room ID
  useEffect(() => {
    const resolveRoom = async () => {
      try {
        setIsLoading(true);
        setError(null);

        let resolvedRoomId: string;
        if (contact.id === 'family') {
          const familyRoomId = await matrixService.getFamilyRoomId();
          if (!familyRoomId) {
            throw new Error('Family room not found');
          }
          resolvedRoomId = familyRoomId;
        } else {
          // For individual contacts, get or create DM room
          resolvedRoomId = await matrixService.getOrCreateDmRoom(contact.id);
        }

        setRoomId(resolvedRoomId);
      } catch (err) {
        const errorMessage =
          err instanceof Error ? err.message : 'Failed to load room';
        setError(errorMessage);
      } finally {
        setIsLoading(false);
      }
    };

    resolveRoom();
  }, [contact.id]);

  // Handle Escape key to go back
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        e.preventDefault();
        onBack();
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => {
      window.removeEventListener('keydown', handleKeyDown);
    };
  }, [onBack]);

  return (
    <div className="history-view">
      {/* Header */}
      <header className="history-header">
        <button
          className="back-button"
          onClick={onBack}
          aria-label="Go back to contacts"
        >
          <span className="back-arrow">&larr;</span>
          <span className="back-text">Back</span>
        </button>
        <h1 className="history-title">{contact.name}</h1>
        <div className="header-spacer" />
      </header>

      {/* Message List */}
      <div className="message-list">
        {isLoading && (
          <div className="history-state">
            <span className="loading-spinner" />
            <span>Loading messages...</span>
          </div>
        )}

        {error && (
          <div className="history-state history-error">
            <span>Failed to load: {error}</span>
          </div>
        )}

        {!isLoading && !error && roomId && (
          <MessageListContent roomId={roomId} contact={contact} />
        )}
      </div>

      {/* Footer with keyboard hints */}
      <footer className="history-footer">
        <span className="keyboard-hint">
          <span className="keyboard-key">Esc</span> Back
        </span>
      </footer>

      <style>{`
        .history-view {
          height: 100vh;
          display: flex;
          flex-direction: column;
          max-width: 600px;
          margin: 0 auto;
          background-color: var(--color-background);
        }

        .history-header {
          display: flex;
          justify-content: space-between;
          align-items: center;
          padding: var(--spacing-md) var(--spacing-lg);
          border-bottom: 1px solid var(--color-surface-elevated);
        }

        .back-button {
          display: flex;
          align-items: center;
          gap: var(--spacing-sm);
          padding: var(--spacing-sm) var(--spacing-md);
          background-color: var(--color-surface);
          border: 1px solid var(--color-surface-elevated);
          border-radius: 8px;
          color: var(--color-text);
          font-size: var(--font-size-base);
          cursor: pointer;
          transition: background-color var(--transition-fast);
        }

        .back-button:hover {
          background-color: var(--color-surface-elevated);
        }

        .back-arrow {
          font-size: var(--font-size-lg);
        }

        .back-text {
          font-size: var(--font-size-sm);
        }

        .history-title {
          font-size: var(--font-size-xl);
          font-weight: 600;
          color: var(--color-text);
        }

        .header-spacer {
          width: 80px; /* Match back button width for centering */
        }

        .message-list {
          flex: 1;
          overflow-y: auto;
          padding: var(--spacing-md);
          display: flex;
          flex-direction: column;
          gap: var(--spacing-md);
        }

        .history-state {
          display: flex;
          flex-direction: column;
          align-items: center;
          justify-content: center;
          gap: var(--spacing-md);
          padding: var(--spacing-xl);
          color: var(--color-text-muted);
          text-align: center;
        }

        .history-error {
          color: var(--color-error);
        }

        .loading-spinner {
          width: 24px;
          height: 24px;
          border: 2px solid var(--color-surface-elevated);
          border-top-color: var(--color-accent);
          border-radius: 50%;
          animation: spin 1s linear infinite;
        }

        @keyframes spin {
          to { transform: rotate(360deg); }
        }

        .history-footer {
          display: flex;
          justify-content: center;
          gap: var(--spacing-lg);
          padding: var(--spacing-md);
          background-color: var(--color-surface);
          border-top: 1px solid var(--color-surface-elevated);
        }

        .keyboard-hint {
          font-size: var(--font-size-sm);
          color: var(--color-text-muted);
          display: flex;
          align-items: center;
          gap: var(--spacing-xs);
        }

        .keyboard-key {
          display: inline-block;
          padding: 2px 8px;
          background-color: var(--color-surface-elevated);
          border: 1px solid var(--color-text-muted);
          border-radius: 4px;
          font-size: var(--font-size-xs);
          font-weight: 600;
        }

        /* Hide footer on mobile */
        @media (max-width: 767px) {
          .history-footer {
            display: none;
          }

          .back-text {
            display: none;
          }
        }

        /* Scrollbar styling */
        .message-list::-webkit-scrollbar {
          width: 8px;
        }

        .message-list::-webkit-scrollbar-track {
          background: var(--color-surface);
        }

        .message-list::-webkit-scrollbar-thumb {
          background: var(--color-surface-elevated);
          border-radius: 4px;
        }

        .message-list::-webkit-scrollbar-thumb:hover {
          background: var(--color-text-muted);
        }
      `}</style>
    </div>
  );
}

/**
 * Inner component that renders messages once roomId is resolved.
 * Separated to use the useVoiceMessages hook cleanly.
 */

interface MessageListContentProps {
  roomId: string;
  contact: Contact;
}

function MessageListContent({ roomId, contact }: MessageListContentProps) {
  const messages = useVoiceMessages(roomId);
  const [playingMessageId, setPlayingMessageId] = useState<string | null>(null);

  // Sort messages by timestamp descending (newest first)
  const sortedMessages = useMemo(
    () => [...messages].sort((a, b) => b.timestamp - a.timestamp),
    [messages],
  );

  // Mark messages as read when viewing history
  useEffect(() => {
    const markAsRead = async () => {
      if (messages.length === 0) return;

      // Mark the most recent message as read (last in original order)
      const lastMessage = messages[messages.length - 1];
      try {
        await matrixService.markMessageAsPlayed(roomId, lastMessage.eventId);
      } catch (err) {
        console.error('Failed to mark messages as read:', err);
      }
    };

    markAsRead();
  }, [roomId, messages]);

  const handlePlay = useCallback((messageId: string) => {
    setPlayingMessageId(messageId);
  }, []);

  const handleStop = useCallback(() => {
    setPlayingMessageId(null);
  }, []);

  if (messages.length === 0) {
    return (
      <div className="history-state empty-state">
        <span className="empty-icon">🎙</span>
        <span>No messages yet</span>
        <span className="empty-hint">
          Hold Space to send a voice message to {contact.name}
        </span>
      </div>
    );
  }

  return (
    <>
      {sortedMessages.map((message: VoiceMessage) => (
        <MessageItem
          key={message.eventId}
          message={message}
          isPlaying={playingMessageId === message.eventId}
          onPlay={() => handlePlay(message.eventId)}
          onStop={handleStop}
        />
      ))}

      <style>{`
        .empty-state {
          flex: 1;
        }

        .empty-icon {
          font-size: 48px;
          opacity: 0.5;
        }

        .empty-hint {
          font-size: var(--font-size-sm);
          max-width: 200px;
        }
      `}</style>
    </>
  );
}
