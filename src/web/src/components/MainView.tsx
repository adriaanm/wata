import { useEffect, useState } from 'react';

import { useContactSelection } from '../hooks/useContactSelection.js';
import { usePtt } from '../hooks/usePtt.js';
import type { Contact } from '../types.js';

import { AudioCodeTestHarness } from './AudioCodeTestHarness.js';
import { ContactCard } from './ContactCard.js';
import { RecordingIndicator } from './RecordingIndicator.js';

import '../styles/animations.css';

interface MainViewProps {
  contacts: Contact[];
}

export function MainView({ contacts }: MainViewProps) {
  const [showAudioCodeTest, setShowAudioCodeTest] = useState(false);

  const { selectedIndex, selectedContact, setSelectedIndex } =
    useContactSelection(contacts);

  const {
    recordingDuration,
    recordingContactId,
    isSpaceHeld,
    sendError,
    startRecording,
    stopRecording,
    cancelRecording,
    clearError,
    isRecording,
    isSending,
  } = usePtt({
    onStartRecording: contactId => {
      console.log('Started recording to', contactId);
    },
    onStopRecording: (contactId, duration) => {
      console.log('Stopped recording to', contactId, 'duration:', duration);
    },
    onSendError: error => {
      console.error('Failed to send voice message:', error);
    },
  });

  // Handle space bar PTT for selected contact
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === ' ' && !isRecording && !isSending && selectedContact) {
        e.preventDefault();
        clearError(); // Clear any previous errors when starting new recording
        startRecording(selectedContact.id);
      }
    };

    const handleKeyUp = (e: KeyboardEvent) => {
      if (e.key === ' ' && isRecording) {
        e.preventDefault();
        stopRecording();
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    window.addEventListener('keyup', handleKeyUp);
    return () => {
      window.removeEventListener('keydown', handleKeyDown);
      window.removeEventListener('keyup', handleKeyUp);
    };
  }, [
    isRecording,
    isSending,
    selectedContact,
    startRecording,
    stopRecording,
    clearError,
  ]);

  const handleSelectContact = (index: number) => {
    setSelectedIndex(index);
  };

  const handleStartRecording = (
    index: number,
    _ripplePosition: { x: number; y: number },
  ) => {
    clearError(); // Clear any previous errors when starting new recording
    startRecording(contacts[index].id);
  };

  // Find the contact being recorded to for the recording indicator
  const recordingContact = recordingContactId
    ? contacts.find(c => c.id === recordingContactId)
    : null;

  return (
    <>
      {showAudioCodeTest && (
        <AudioCodeTestHarness onClose={() => setShowAudioCodeTest(false)} />
      )}

      {isRecording && recordingContact && (
        <RecordingIndicator
          duration={recordingDuration}
          contactName={recordingContact.name}
          isSpaceHeld={isSpaceHeld}
          isSending={isSending}
          error={sendError}
        />
      )}

      <div className="main-view">
        {/* Header */}
        <header className="main-view-header">
          <h1 className="app-title">WATA</h1>
          <button
            className="admin-button"
            aria-label="Admin menu"
            onClick={() => setShowAudioCodeTest(true)}
          >
            <span>≡</span>
            <span className="admin-button-label">Admin</span>
          </button>
        </header>

        {/* Contact List */}
        <div className="contact-list">
          {contacts.map((contact, index) => (
            <ContactCard
              key={contact.id}
              contact={contact}
              isSelected={index === selectedIndex}
              isDimmed={isRecording && recordingContactId !== contact.id}
              isRecording={recordingContactId === contact.id}
              onSelect={() => handleSelectContact(index)}
              onStartRecording={pos => handleStartRecording(index, pos)}
              onStopRecording={stopRecording}
              onCancelRecording={cancelRecording}
            />
          ))}
        </div>

        {/* Footer with keyboard hints */}
        <footer className="main-view-footer">
          <span className="keyboard-hint">↑↓ Select</span>
          <span className="keyboard-hint keyboard-hint--primary">
            <span className="keyboard-key">Space</span> Talk
          </span>
          <span className="keyboard-hint">Enter History</span>
          <span className="keyboard-hint">≡ Admin</span>
        </footer>
      </div>

      <style>{`
        .main-view {
          height: 100vh;
          display: flex;
          flex-direction: column;
          max-width: 600px;
          margin: 0 auto;
          background-color: var(--color-background);
        }

        .main-view-header {
          display: flex;
          justify-content: space-between;
          align-items: center;
          padding: var(--spacing-md) var(--spacing-lg);
          border-bottom: 1px solid var(--color-surface-elevated);
        }

        .app-title {
          font-size: var(--font-size-2xl);
          font-weight: 700;
          color: var(--color-accent);
          letter-spacing: 2px;
        }

        .admin-button {
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

        .admin-button:hover {
          background-color: var(--color-surface-elevated);
        }

        .admin-button span:first-child {
          font-size: var(--font-size-xl);
          font-weight: 300;
        }

        .admin-button-label {
          font-size: var(--font-size-sm);
        }

        .contact-list {
          flex: 1;
          overflow-y: auto;
          padding: var(--spacing-md);
          display: flex;
          flex-direction: column;
          gap: var(--spacing-md);
        }

        .main-view-footer {
          display: flex;
          justify-content: center;
          gap: var(--spacing-lg);
          padding: var(--spacing-md);
          background-color: var(--color-surface);
          border-top: 1px solid var(--color-surface-elevated);
          flex-wrap: wrap;
        }

        .keyboard-hint {
          font-size: var(--font-size-sm);
          color: var(--color-text-muted);
          display: flex;
          align-items: center;
          gap: var(--spacing-xs);
        }

        .keyboard-hint--primary {
          color: var(--color-accent);
          font-weight: 600;
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
          .main-view-footer {
            display: none;
          }
        }

        /* Scrollbar styling */
        .contact-list::-webkit-scrollbar {
          width: 8px;
        }

        .contact-list::-webkit-scrollbar-track {
          background: var(--color-surface);
        }

        .contact-list::-webkit-scrollbar-thumb {
          background: var(--color-surface-elevated);
          border-radius: 4px;
        }

        .contact-list::-webkit-scrollbar-thumb:hover {
          background: var(--color-text-muted);
        }
      `}</style>
    </>
  );
}
