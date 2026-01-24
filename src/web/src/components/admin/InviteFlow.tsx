import { useCallback, useEffect, useState } from 'react';

import { matrixService } from '../../services/matrixService.js';

type InviteStatus = 'idle' | 'sending' | 'success' | 'error';

export function InviteFlow() {
  const [userId, setUserId] = useState('');
  const [status, setStatus] = useState<InviteStatus>('idle');
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [roomLink, setRoomLink] = useState<string | null>(null);
  const [copied, setCopied] = useState(false);
  const [familyRoomExists, setFamilyRoomExists] = useState<boolean | null>(
    null,
  );

  // Load room link on mount
  useEffect(() => {
    const loadRoomLink = async () => {
      try {
        const familyRoom = await matrixService.getFamilyRoom();
        if (familyRoom) {
          // Create a matrix.to link
          const link = `https://matrix.to/#/${familyRoom.roomId}`;
          setRoomLink(link);
          setFamilyRoomExists(true);
        } else {
          setFamilyRoomExists(false);
        }
      } catch (err) {
        console.error('Failed to get family room:', err);
        setFamilyRoomExists(false);
      }
    };
    loadRoomLink();
  }, []);

  const handleInvite = useCallback(async () => {
    const trimmedUserId = userId.trim();

    if (!trimmedUserId) {
      setStatus('error');
      setErrorMessage('Please enter a Matrix ID');
      return;
    }

    // Basic validation for Matrix ID format
    if (!trimmedUserId.startsWith('@') || !trimmedUserId.includes(':')) {
      setStatus('error');
      setErrorMessage('Matrix ID should be in format @username:server.com');
      return;
    }

    try {
      setStatus('sending');
      setErrorMessage(null);

      await matrixService.inviteToFamily(trimmedUserId);

      setStatus('success');
      setUserId('');

      // Reset success status after 3 seconds
      setTimeout(() => {
        setStatus('idle');
      }, 3000);
    } catch (err) {
      setStatus('error');
      setErrorMessage(
        err instanceof Error ? err.message : 'Failed to send invite',
      );
    }
  }, [userId]);

  const handleCopyLink = useCallback(async () => {
    if (!roomLink) return;

    try {
      await navigator.clipboard.writeText(roomLink);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch (err) {
      console.error('Failed to copy:', err);
    }
  }, [roomLink]);

  const handleKeyDown = (e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && status !== 'sending') {
      handleInvite();
    }
  };

  return (
    <div className="invite-flow">
      <h2 className="section-title">Invite to Family</h2>

      {/* Loading state */}
      {familyRoomExists === null && (
        <div className="loading">Checking family room...</div>
      )}

      {/* No family room - show helpful message */}
      {familyRoomExists === false && (
        <div className="no-family-room">
          <p className="no-room-text">
            No family room found. You need to create a family room first before
            you can invite members.
          </p>
          <p className="no-room-hint">
            Go to the Family tab to create a family room.
          </p>
        </div>
      )}

      {/* Family room exists - show invite form */}
      {familyRoomExists === true && (
        <>
          <div className="invite-form">
            <label className="form-label" htmlFor="userId">
              Enter Matrix ID:
            </label>
            <div className="input-row">
              <input
                id="userId"
                type="text"
                className="user-id-input"
                placeholder="@username:matrix.org"
                value={userId}
                onChange={e => setUserId(e.target.value)}
                onKeyDown={handleKeyDown}
                disabled={status === 'sending'}
              />
            </div>

            <button
              className="invite-button"
              onClick={handleInvite}
              disabled={status === 'sending'}
            >
              {status === 'sending' ? 'Sending...' : 'Send Invite'}
            </button>

            {status === 'success' && (
              <div className="status-message status-success">
                Invite sent successfully!
              </div>
            )}

            {status === 'error' && errorMessage && (
              <div className="status-message status-error">{errorMessage}</div>
            )}
          </div>

          <div className="divider">
            <span>or</span>
          </div>

          <div className="share-section">
            <label className="form-label">Share room link:</label>
            <div className="link-row">
              <input
                type="text"
                className="link-input"
                value={roomLink || 'Loading...'}
                readOnly
              />
              <button
                className="copy-button"
                onClick={handleCopyLink}
                disabled={!roomLink}
              >
                {copied ? 'Copied!' : 'Copy'}
              </button>
            </div>
            <p className="link-hint">
              Anyone with this link can request to join your family room.
            </p>
          </div>
        </>
      )}

      <style>{`
        .invite-flow {
          display: flex;
          flex-direction: column;
          gap: var(--spacing-lg);
        }

        .section-title {
          font-size: var(--font-size-lg);
          font-weight: 600;
          color: var(--color-text);
          margin: 0;
        }

        .invite-form {
          display: flex;
          flex-direction: column;
          gap: var(--spacing-md);
        }

        .form-label {
          font-size: var(--font-size-sm);
          color: var(--color-text-muted);
          font-weight: 500;
        }

        .input-row {
          display: flex;
          gap: var(--spacing-sm);
        }

        .user-id-input {
          flex: 1;
          padding: var(--spacing-md);
          background-color: var(--color-surface);
          border: 1px solid var(--color-surface-elevated);
          border-radius: 8px;
          color: var(--color-text);
          font-size: var(--font-size-base);
          font-family: inherit;
        }

        .user-id-input:focus {
          outline: none;
          border-color: var(--color-accent);
        }

        .user-id-input:disabled {
          opacity: 0.5;
        }

        .user-id-input::placeholder {
          color: var(--color-text-muted);
        }

        .invite-button {
          padding: var(--spacing-md);
          background-color: var(--color-accent);
          border: none;
          border-radius: 8px;
          color: var(--color-background);
          font-size: var(--font-size-base);
          font-weight: 600;
          cursor: pointer;
          transition: opacity var(--transition-fast);
        }

        .invite-button:hover:not(:disabled) {
          opacity: 0.9;
        }

        .invite-button:disabled {
          opacity: 0.5;
          cursor: not-allowed;
        }

        .status-message {
          padding: var(--spacing-md);
          border-radius: 8px;
          font-size: var(--font-size-sm);
          text-align: center;
        }

        .status-success {
          background-color: rgba(51, 255, 51, 0.1);
          border: 1px solid var(--color-success);
          color: var(--color-success);
        }

        .status-error {
          background-color: rgba(255, 153, 0, 0.1);
          border: 1px solid var(--color-error);
          color: var(--color-error);
        }

        .divider {
          display: flex;
          align-items: center;
          gap: var(--spacing-md);
          color: var(--color-text-muted);
          font-size: var(--font-size-sm);
        }

        .divider::before,
        .divider::after {
          content: '';
          flex: 1;
          height: 1px;
          background-color: var(--color-surface-elevated);
        }

        .share-section {
          display: flex;
          flex-direction: column;
          gap: var(--spacing-md);
        }

        .link-row {
          display: flex;
          gap: var(--spacing-sm);
        }

        .link-input {
          flex: 1;
          padding: var(--spacing-md);
          background-color: var(--color-surface);
          border: 1px solid var(--color-surface-elevated);
          border-radius: 8px;
          color: var(--color-text-muted);
          font-size: var(--font-size-sm);
          font-family: monospace;
        }

        .link-input:focus {
          outline: none;
        }

        .copy-button {
          padding: var(--spacing-md);
          background-color: var(--color-surface-elevated);
          border: none;
          border-radius: 8px;
          color: var(--color-text);
          font-size: var(--font-size-sm);
          font-weight: 500;
          cursor: pointer;
          transition: all var(--transition-fast);
          white-space: nowrap;
        }

        .copy-button:hover:not(:disabled) {
          background-color: var(--color-text-muted);
          color: var(--color-background);
        }

        .copy-button:disabled {
          opacity: 0.5;
          cursor: not-allowed;
        }

        .link-hint {
          font-size: var(--font-size-xs);
          color: var(--color-text-muted);
          margin: 0;
        }

        .loading {
          color: var(--color-text-muted);
          text-align: center;
          padding: var(--spacing-xl);
          font-size: var(--font-size-sm);
        }

        .no-family-room {
          display: flex;
          flex-direction: column;
          gap: var(--spacing-md);
          align-items: center;
          text-align: center;
          padding: var(--spacing-xl) 0;
        }

        .no-room-text {
          color: var(--color-text-muted);
          margin: 0;
          font-size: var(--font-size-base);
          line-height: 1.5;
        }

        .no-room-hint {
          color: var(--color-accent);
          margin: 0;
          font-size: var(--font-size-sm);
          font-weight: 500;
        }
      `}</style>
    </div>
  );
}
