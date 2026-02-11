import type { FamilyMember } from '@shared/services/WataService';
import { useCallback, useEffect, useState } from 'react';

import { matrixService } from '../../services/matrixService.js';

export function FamilyManager() {
  const [familyRoomId, setFamilyRoomId] = useState<string | null>(null);
  const [members, setMembers] = useState<FamilyMember[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isCreating, setIsCreating] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [successMessage, setSuccessMessage] = useState<string | null>(null);
  const [removingUserId, setRemovingUserId] = useState<string | null>(null);
  const [showConfirmRemove, setShowConfirmRemove] = useState<string | null>(
    null,
  );

  const currentUserId = matrixService.getUserId();

  const loadFamilyState = useCallback(async () => {
    try {
      setIsLoading(true);
      setError(null);
      const roomId = await matrixService.getFamilyRoomId();
      setFamilyRoomId(roomId);
      if (roomId) {
        const familyMembers = await matrixService.getFamilyMembers(true);
        setMembers(familyMembers);
      } else {
        setMembers([]);
      }
    } catch (err) {
      setError(
        err instanceof Error ? err.message : 'Failed to load family state',
      );
    } finally {
      setIsLoading(false);
    }
  }, []);

  const handleCreateFamilyRoom = useCallback(async () => {
    try {
      setIsCreating(true);
      setError(null);
      const roomId = await matrixService.createFamilyRoom();
      setFamilyRoomId(roomId);
      setSuccessMessage('Family room created successfully!');
      // Load members after creation
      const familyMembers = await matrixService.getFamilyMembers(true);
      setMembers(familyMembers);
      // Clear success message after 3 seconds
      setTimeout(() => setSuccessMessage(null), 3000);
    } catch (err) {
      setError(
        err instanceof Error ? err.message : 'Failed to create family room',
      );
    } finally {
      setIsCreating(false);
    }
  }, []);

  useEffect(() => {
    loadFamilyState();
  }, [loadFamilyState]);

  const handleRemoveMember = async (userId: string) => {
    try {
      setRemovingUserId(userId);
      setError(null);

      const client = matrixService.getClient();
      const roomId = await matrixService.getFamilyRoomId();

      if (!client || !roomId) {
        throw new Error('Not connected to family room');
      }

      await client.kick(roomId, userId, 'Removed from family');
      setShowConfirmRemove(null);
      await loadFamilyState();
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to remove member');
    } finally {
      setRemovingUserId(null);
    }
  };

  if (isLoading) {
    return (
      <div className="family-manager">
        <div className="loading">Loading family members...</div>
        <style>{familyManagerStyles}</style>
      </div>
    );
  }

  return (
    <div className="family-manager">
      <h2 className="section-title">Family Members ({members.length})</h2>

      {error && <div className="error-message">{error}</div>}
      {successMessage && (
        <div className="success-message">{successMessage}</div>
      )}

      {/* No family room - show create button */}
      {!isLoading && !familyRoomId && (
        <div className="no-family-room">
          <p className="no-room-text">
            No family room found. Create one to get started!
          </p>
          <button
            className="create-family-button"
            onClick={handleCreateFamilyRoom}
            disabled={isCreating}
          >
            {isCreating ? 'Creating...' : 'Create Family Room'}
          </button>
        </div>
      )}

      {/* Family room exists - show members */}
      {familyRoomId && (
        <div className="member-list">
          {members.map(member => (
            <div key={member.userId} className="member-card">
              <div className="member-avatar">
                {member.avatarUrl ? (
                  <img src={member.avatarUrl} alt="" className="avatar-image" />
                ) : (
                  <span className="avatar-placeholder">👤</span>
                )}
              </div>
              <div className="member-info">
                <div className="member-name">
                  {member.displayName}
                  {member.userId === currentUserId && (
                    <span className="you-badge">(you)</span>
                  )}
                </div>
                <div className="member-id">{member.userId}</div>
              </div>
              {member.userId !== currentUserId && (
                <div className="member-actions">
                  {showConfirmRemove === member.userId ? (
                    <div className="confirm-remove">
                      <button
                        className="confirm-yes"
                        onClick={() => handleRemoveMember(member.userId)}
                        disabled={removingUserId === member.userId}
                      >
                        {removingUserId === member.userId ? '...' : 'Remove'}
                      </button>
                      <button
                        className="confirm-no"
                        onClick={() => setShowConfirmRemove(null)}
                        disabled={removingUserId === member.userId}
                      >
                        Cancel
                      </button>
                    </div>
                  ) : (
                    <button
                      className="options-button"
                      onClick={() => setShowConfirmRemove(member.userId)}
                      aria-label="Options"
                    >
                      ···
                    </button>
                  )}
                </div>
              )}
            </div>
          ))}
        </div>
      )}

      {familyRoomId && members.length === 0 && !error && (
        <div className="empty-state">
          No family members yet. Use the Invite tab to add someone!
        </div>
      )}

      <style>{familyManagerStyles}</style>
    </div>
  );
}

const familyManagerStyles = `
  .family-manager {
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

  .loading {
    color: var(--color-text-muted);
    text-align: center;
    padding: var(--spacing-xl);
  }

  .error-message {
    padding: var(--spacing-md);
    background-color: rgba(255, 153, 0, 0.1);
    border: 1px solid var(--color-error);
    border-radius: 8px;
    color: var(--color-error);
    font-size: var(--font-size-sm);
  }

  .member-list {
    display: flex;
    flex-direction: column;
    gap: var(--spacing-md);
  }

  .member-card {
    display: flex;
    align-items: center;
    gap: var(--spacing-md);
    padding: var(--spacing-md);
    background-color: var(--color-surface);
    border: 1px solid var(--color-surface-elevated);
    border-radius: 8px;
  }

  .member-avatar {
    width: 48px;
    height: 48px;
    border-radius: 50%;
    background-color: var(--color-surface-elevated);
    display: flex;
    align-items: center;
    justify-content: center;
    overflow: hidden;
    flex-shrink: 0;
  }

  .avatar-image {
    width: 100%;
    height: 100%;
    object-fit: cover;
  }

  .avatar-placeholder {
    font-size: var(--font-size-xl);
  }

  .member-info {
    flex: 1;
    min-width: 0;
  }

  .member-name {
    font-weight: 500;
    color: var(--color-text);
    display: flex;
    align-items: center;
    gap: var(--spacing-sm);
  }

  .you-badge {
    font-size: var(--font-size-xs);
    color: var(--color-accent);
    font-weight: normal;
  }

  .member-id {
    font-size: var(--font-size-sm);
    color: var(--color-text-muted);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .member-actions {
    flex-shrink: 0;
  }

  .options-button {
    padding: var(--spacing-sm) var(--spacing-md);
    background: none;
    border: 1px solid var(--color-surface-elevated);
    border-radius: 4px;
    color: var(--color-text-muted);
    font-size: var(--font-size-lg);
    cursor: pointer;
    transition: all var(--transition-fast);
  }

  .options-button:hover {
    background-color: var(--color-surface-elevated);
    color: var(--color-text);
  }

  .confirm-remove {
    display: flex;
    gap: var(--spacing-sm);
  }

  .confirm-yes {
    padding: var(--spacing-xs) var(--spacing-md);
    background-color: var(--color-error);
    border: none;
    border-radius: 4px;
    color: var(--color-background);
    font-size: var(--font-size-sm);
    font-weight: 500;
    cursor: pointer;
    transition: opacity var(--transition-fast);
  }

  .confirm-yes:hover {
    opacity: 0.9;
  }

  .confirm-yes:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }

  .confirm-no {
    padding: var(--spacing-xs) var(--spacing-md);
    background-color: var(--color-surface-elevated);
    border: none;
    border-radius: 4px;
    color: var(--color-text-muted);
    font-size: var(--font-size-sm);
    cursor: pointer;
    transition: all var(--transition-fast);
  }

  .confirm-no:hover {
    color: var(--color-text);
  }

  .confirm-no:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }

  .empty-state {
    text-align: center;
    color: var(--color-text-muted);
    padding: var(--spacing-xl);
    font-size: var(--font-size-sm);
  }

  .no-family-room {
    display: flex;
    flex-direction: column;
    gap: var(--spacing-lg);
    align-items: center;
    text-align: center;
    padding: var(--spacing-xl) 0;
  }

  .no-room-text {
    color: var(--color-text-muted);
    margin: 0;
    font-size: var(--font-size-base);
  }

  .create-family-button {
    padding: var(--spacing-md) var(--spacing-xl);
    background-color: var(--color-accent);
    border: none;
    border-radius: 8px;
    color: var(--color-background);
    font-size: var(--font-size-base);
    font-weight: 600;
    cursor: pointer;
    transition: opacity var(--transition-fast);
  }

  .create-family-button:hover:not(:disabled) {
    opacity: 0.9;
  }

  .create-family-button:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }

  .success-message {
    padding: var(--spacing-md);
    background-color: rgba(51, 255, 51, 0.1);
    border: 1px solid var(--color-success);
    border-radius: 8px;
    color: var(--color-success);
    font-size: var(--font-size-sm);
  }
`;
