import { FormEvent, useState } from 'react';
import { useAuth } from '../hooks/useMatrix.js';

interface LoginViewProps {
  onLoginSuccess: () => void;
}

export function LoginView({ onLoginSuccess }: LoginViewProps) {
  const { login, isLoading, error } = useAuth();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    try {
      await login(username, password);
      onLoginSuccess();
    } catch {
      // Error shown via useAuth error state
    }
  };

  return (
    <div className="login-view">
      <div className="login-container">
        <div className="login-logo">
          <h1 className="login-title">WATA</h1>
          <p className="login-subtitle">Walkie-Talkie App</p>
        </div>

        <form className="login-form" onSubmit={handleSubmit}>
          <div className="login-form-group">
            <label htmlFor="username" className="login-label">
              Username
            </label>
            <input
              id="username"
              type="text"
              className="login-input"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              disabled={isLoading}
              autoFocus
              required
            />
          </div>

          <div className="login-form-group">
            <label htmlFor="password" className="login-label">
              Password
            </label>
            <input
              id="password"
              type="password"
              className="login-input"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              disabled={isLoading}
              required
            />
          </div>

          {error && (
            <div className="login-error">
              <span className="login-error-icon">⚠</span>
              <span className="login-error-text">{error}</span>
            </div>
          )}

          <button type="submit" className="login-button" disabled={isLoading || !username || !password}>
            {isLoading ? (
              <>
                <span className="login-button-spinner" />
                Connecting...
              </>
            ) : (
              'Connect'
            )}
          </button>
        </form>
      </div>

      <style>{`
        .login-view {
          height: 100vh;
          width: 100vw;
          display: flex;
          align-items: center;
          justify-content: center;
          background-color: var(--color-background);
        }

        .login-container {
          width: 100%;
          max-width: 360px;
          padding: var(--spacing-xl);
        }

        .login-logo {
          text-align: center;
          margin-bottom: var(--spacing-2xl);
        }

        .login-title {
          font-size: 48px;
          font-weight: 700;
          color: var(--color-accent);
          letter-spacing: 4px;
          margin: 0;
        }

        .login-subtitle {
          font-size: var(--font-size-sm);
          color: var(--color-text-muted);
          margin-top: var(--spacing-sm);
        }

        .login-form {
          display: flex;
          flex-direction: column;
          gap: var(--spacing-md);
        }

        .login-form-group {
          display: flex;
          flex-direction: column;
          gap: var(--spacing-xs);
        }

        .login-label {
          font-size: var(--font-size-sm);
          font-weight: 600;
          color: var(--color-text);
        }

        .login-input {
          padding: var(--spacing-md);
          font-size: var(--font-size-base);
          background-color: var(--color-surface);
          border: 1px solid var(--color-surface-elevated);
          border-radius: 8px;
          color: var(--color-text);
          outline: none;
          transition: border-color var(--transition-fast);
        }

        .login-input:focus {
          border-color: var(--color-accent);
        }

        .login-input:disabled {
          opacity: 0.6;
          cursor: not-allowed;
        }

        .login-error {
          display: flex;
          align-items: center;
          gap: var(--spacing-sm);
          padding: var(--spacing-md);
          background-color: rgba(239, 68, 68, 0.1);
          border: 1px solid rgba(239, 68, 68, 0.3);
          border-radius: 8px;
        }

        .login-error-icon {
          font-size: var(--font-size-lg);
        }

        .login-error-text {
          font-size: var(--font-size-sm);
          color: var(--color-error);
        }

        .login-button {
          display: flex;
          align-items: center;
          justify-content: center;
          gap: var(--spacing-sm);
          padding: var(--spacing-md);
          font-size: var(--font-size-base);
          font-weight: 600;
          background-color: var(--color-accent);
          color: white;
          border: none;
          border-radius: 8px;
          cursor: pointer;
          transition: background-color var(--transition-fast), opacity var(--transition-fast);
          margin-top: var(--spacing-sm);
        }

        .login-button:hover:not(:disabled) {
          background-color: var(--color-accent-hover);
        }

        .login-button:disabled {
          opacity: 0.6;
          cursor: not-allowed;
        }

        .login-button-spinner {
          width: 16px;
          height: 16px;
          border: 2px solid transparent;
          border-top-color: white;
          border-radius: 50%;
          animation: spin 0.8s linear infinite;
        }

        @keyframes spin {
          to {
            transform: rotate(360deg);
          }
        }
      `}</style>
    </div>
  );
}
