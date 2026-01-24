import { useCallback, useEffect, useState } from 'react';

import { matrixService } from '../../services/matrixService.js';

interface SettingsPanelProps {
  onLogout: () => void;
}

type SaveStatus = 'idle' | 'saving' | 'success' | 'error';

export function SettingsPanel({ onLogout }: SettingsPanelProps) {
  const [displayName, setDisplayName] = useState('');
  const [originalName, setOriginalName] = useState('');
  const [saveStatus, setSaveStatus] = useState<SaveStatus>('idle');
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [isLoggingOut, setIsLoggingOut] = useState(false);

  const userId = matrixService.getUserId();

  useEffect(() => {
    const loadDisplayName = async () => {
      try {
        const name = await matrixService.getDisplayName();
        setDisplayName(name || '');
        setOriginalName(name || '');
      } catch (err) {
        console.error('Failed to load display name:', err);
      }
    };
    loadDisplayName();
  }, []);

  const handleSave = useCallback(async () => {
    const trimmedName = displayName.trim();

    if (!trimmedName) {
      setSaveStatus('error');
      setErrorMessage('Display name cannot be empty');
      return;
    }

    if (trimmedName === originalName) {
      // No change
      return;
    }

    try {
      setSaveStatus('saving');
      setErrorMessage(null);

      await matrixService.setDisplayName(trimmedName);

      setOriginalName(trimmedName);
      setSaveStatus('success');

      // Reset status after 2 seconds
      setTimeout(() => {
        setSaveStatus('idle');
      }, 2000);
    } catch (err) {
      setSaveStatus('error');
      setErrorMessage(err instanceof Error ? err.message : 'Failed to save');
    }
  }, [displayName, originalName]);

  const handleLogout = useCallback(async () => {
    try {
      setIsLoggingOut(true);
      await matrixService.logout();
      onLogout();
      // Page will refresh or redirect to login
      window.location.reload();
    } catch (err) {
      console.error('Logout failed:', err);
      setIsLoggingOut(false);
    }
  }, [onLogout]);

  const hasChanges = displayName.trim() !== originalName;

  return (
    <div className="settings-panel">
      <h2 className="section-title">Settings</h2>

      <section className="settings-section">
        <h3 className="subsection-title">Profile</h3>

        <div className="setting-item">
          <label className="setting-label" htmlFor="displayName">
            Display Name
          </label>
          <div className="setting-input-row">
            <input
              id="displayName"
              type="text"
              className="setting-input"
              value={displayName}
              onChange={e => setDisplayName(e.target.value)}
              disabled={saveStatus === 'saving'}
            />
            <button
              className="save-button"
              onClick={handleSave}
              disabled={!hasChanges || saveStatus === 'saving'}
            >
              {saveStatus === 'saving'
                ? '...'
                : saveStatus === 'success'
                  ? '✓'
                  : 'Save'}
            </button>
          </div>
          {saveStatus === 'error' && errorMessage && (
            <div className="setting-error">{errorMessage}</div>
          )}
        </div>

        <div className="setting-item">
          <label className="setting-label">User ID</label>
          <div className="setting-value">{userId || 'Not logged in'}</div>
        </div>
      </section>

      <section className="settings-section">
        <h3 className="subsection-title">Account</h3>

        <button
          className="logout-button"
          onClick={handleLogout}
          disabled={isLoggingOut}
        >
          {isLoggingOut ? 'Logging out...' : 'Log Out'}
        </button>
      </section>

      <style>{`
        .settings-panel {
          display: flex;
          flex-direction: column;
          gap: var(--spacing-xl);
        }

        .section-title {
          font-size: var(--font-size-lg);
          font-weight: 600;
          color: var(--color-text);
          margin: 0;
        }

        .settings-section {
          display: flex;
          flex-direction: column;
          gap: var(--spacing-md);
        }

        .subsection-title {
          font-size: var(--font-size-base);
          font-weight: 500;
          color: var(--color-text-muted);
          margin: 0;
          padding-bottom: var(--spacing-sm);
          border-bottom: 1px solid var(--color-surface-elevated);
        }

        .setting-item {
          display: flex;
          flex-direction: column;
          gap: var(--spacing-sm);
        }

        .setting-label {
          font-size: var(--font-size-sm);
          color: var(--color-text-muted);
          font-weight: 500;
        }

        .setting-input-row {
          display: flex;
          gap: var(--spacing-sm);
        }

        .setting-input {
          flex: 1;
          padding: var(--spacing-md);
          background-color: var(--color-surface);
          border: 1px solid var(--color-surface-elevated);
          border-radius: 8px;
          color: var(--color-text);
          font-size: var(--font-size-base);
          font-family: inherit;
        }

        .setting-input:focus {
          outline: none;
          border-color: var(--color-accent);
        }

        .setting-input:disabled {
          opacity: 0.5;
        }

        .save-button {
          padding: var(--spacing-md) var(--spacing-lg);
          background-color: var(--color-accent);
          border: none;
          border-radius: 8px;
          color: var(--color-background);
          font-size: var(--font-size-sm);
          font-weight: 600;
          cursor: pointer;
          transition: opacity var(--transition-fast);
          min-width: 60px;
        }

        .save-button:hover:not(:disabled) {
          opacity: 0.9;
        }

        .save-button:disabled {
          opacity: 0.5;
          cursor: not-allowed;
        }

        .setting-error {
          font-size: var(--font-size-xs);
          color: var(--color-error);
        }

        .setting-value {
          padding: var(--spacing-md);
          background-color: var(--color-surface);
          border: 1px solid var(--color-surface-elevated);
          border-radius: 8px;
          color: var(--color-text-muted);
          font-size: var(--font-size-sm);
          font-family: monospace;
          word-break: break-all;
        }

        .logout-button {
          padding: var(--spacing-md);
          background-color: transparent;
          border: 1px solid var(--color-error);
          border-radius: 8px;
          color: var(--color-error);
          font-size: var(--font-size-base);
          font-weight: 500;
          cursor: pointer;
          transition: all var(--transition-fast);
        }

        .logout-button:hover:not(:disabled) {
          background-color: var(--color-error);
          color: var(--color-background);
        }

        .logout-button:disabled {
          opacity: 0.5;
          cursor: not-allowed;
        }
      `}</style>
    </div>
  );
}
