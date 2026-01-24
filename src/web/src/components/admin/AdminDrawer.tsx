import { useState } from 'react';

import type { AdminPanel } from '../../types.js';

import { FamilyManager } from './FamilyManager.js';
import { InviteFlow } from './InviteFlow.js';
import { LogsPanel } from './LogsPanel.js';
import { SettingsPanel } from './SettingsPanel.js';

interface AdminDrawerProps {
  onClose: () => void;
}

const PANELS: { id: AdminPanel; label: string; icon: string }[] = [
  { id: 'family', label: 'Family', icon: '👨‍👩‍👧‍👦' },
  { id: 'invite', label: 'Invite', icon: '✉️' },
  { id: 'settings', label: 'Settings', icon: '⚙️' },
  { id: 'logs', label: 'Logs', icon: '📋' },
];

export function AdminDrawer({ onClose }: AdminDrawerProps) {
  const [activePanel, setActivePanel] = useState<AdminPanel>('family');

  const renderPanel = () => {
    switch (activePanel) {
      case 'family':
        return <FamilyManager />;
      case 'invite':
        return <InviteFlow />;
      case 'settings':
        return <SettingsPanel onLogout={onClose} />;
      case 'logs':
        return <LogsPanel />;
      default:
        return null;
    }
  };

  return (
    <div className="admin-overlay">
      <div className="admin-backdrop" onClick={onClose} />
      <div className="admin-drawer">
        <header className="admin-header">
          <button
            className="back-button"
            onClick={onClose}
            aria-label="Close admin"
          >
            ←
          </button>
          <h1 className="admin-title">Admin</h1>
          <button className="close-button" onClick={onClose} aria-label="Close">
            ×
          </button>
        </header>

        <nav className="admin-nav">
          {PANELS.map(panel => (
            <button
              key={panel.id}
              className={`nav-tab ${activePanel === panel.id ? 'nav-tab--active' : ''}`}
              onClick={() => setActivePanel(panel.id)}
            >
              <span className="nav-tab-icon">{panel.icon}</span>
              <span className="nav-tab-label">{panel.label}</span>
            </button>
          ))}
        </nav>

        <main className="admin-content">{renderPanel()}</main>
      </div>

      <style>{`
        .admin-overlay {
          position: fixed;
          top: 0;
          left: 0;
          right: 0;
          bottom: 0;
          z-index: 1000;
          display: flex;
          justify-content: flex-end;
        }

        .admin-backdrop {
          position: absolute;
          top: 0;
          left: 0;
          right: 0;
          bottom: 0;
          background-color: rgba(0, 0, 0, 0.6);
        }

        .admin-drawer {
          position: relative;
          width: 100%;
          max-width: 450px;
          height: 100%;
          background-color: var(--color-background);
          border-left: 1px solid var(--color-surface-elevated);
          display: flex;
          flex-direction: column;
          animation: slideIn 0.2s ease-out;
        }

        @keyframes slideIn {
          from {
            transform: translateX(100%);
          }
          to {
            transform: translateX(0);
          }
        }

        .admin-header {
          display: flex;
          align-items: center;
          gap: var(--spacing-md);
          padding: var(--spacing-md) var(--spacing-lg);
          border-bottom: 1px solid var(--color-surface-elevated);
          background-color: var(--color-surface);
        }

        .back-button {
          display: none;
          padding: var(--spacing-sm) var(--spacing-md);
          background: none;
          border: none;
          color: var(--color-accent);
          font-size: var(--font-size-xl);
          cursor: pointer;
        }

        .admin-title {
          flex: 1;
          font-size: var(--font-size-xl);
          font-weight: 600;
          color: var(--color-text);
        }

        .close-button {
          padding: var(--spacing-sm) var(--spacing-md);
          background: none;
          border: none;
          color: var(--color-text-muted);
          font-size: var(--font-size-2xl);
          cursor: pointer;
          transition: color var(--transition-fast);
        }

        .close-button:hover {
          color: var(--color-text);
        }

        .admin-nav {
          display: flex;
          gap: var(--spacing-sm);
          padding: var(--spacing-md);
          border-bottom: 1px solid var(--color-surface-elevated);
          overflow-x: auto;
        }

        .nav-tab {
          display: flex;
          align-items: center;
          gap: var(--spacing-sm);
          padding: var(--spacing-sm) var(--spacing-md);
          background-color: var(--color-surface);
          border: 1px solid var(--color-surface-elevated);
          border-radius: 8px;
          color: var(--color-text-muted);
          font-size: var(--font-size-sm);
          cursor: pointer;
          transition: all var(--transition-fast);
          white-space: nowrap;
        }

        .nav-tab:hover {
          background-color: var(--color-surface-elevated);
          color: var(--color-text);
        }

        .nav-tab--active {
          background-color: var(--color-accent);
          border-color: var(--color-accent);
          color: var(--color-background);
        }

        .nav-tab--active:hover {
          background-color: var(--color-accent);
          color: var(--color-background);
        }

        .nav-tab-icon {
          font-size: var(--font-size-base);
        }

        .nav-tab-label {
          font-weight: 500;
        }

        .admin-content {
          flex: 1;
          overflow-y: auto;
          padding: var(--spacing-lg);
        }

        /* Mobile: full screen */
        @media (max-width: 767px) {
          .admin-drawer {
            max-width: 100%;
            border-left: none;
          }

          .admin-backdrop {
            display: none;
          }

          .back-button {
            display: block;
          }

          .close-button {
            display: none;
          }
        }

        /* Scrollbar styling */
        .admin-content::-webkit-scrollbar {
          width: 8px;
        }

        .admin-content::-webkit-scrollbar-track {
          background: var(--color-surface);
        }

        .admin-content::-webkit-scrollbar-thumb {
          background: var(--color-surface-elevated);
          border-radius: 4px;
        }

        .admin-content::-webkit-scrollbar-thumb:hover {
          background: var(--color-text-muted);
        }
      `}</style>
    </div>
  );
}
