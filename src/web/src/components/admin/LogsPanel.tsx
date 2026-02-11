import { useEffect, useState } from 'react';

import { LogService } from '../../services/LogService.js';
import { matrixService } from '../../services/matrixService.js';

interface DiagnosticInfo {
  syncState: string;
  userId: string | null;
  homeserver: string;
  isLoggedIn: boolean;
  hasAccessToken: boolean;
  familyRoomId: string | null;
  memberCount: number;
}

export function LogsPanel() {
  const [info, setInfo] = useState<DiagnosticInfo>({
    syncState: 'Unknown',
    userId: null,
    homeserver: '',
    isLoggedIn: false,
    hasAccessToken: false,
    familyRoomId: null,
    memberCount: 0,
  });
  const [recentLogs, setRecentLogs] = useState<string[]>([]);

  useEffect(() => {
    const loadDiagnostics = async () => {
      try {
        const familyRoomId = await matrixService.getFamilyRoomId();
        let memberCount = 0;

        if (familyRoomId) {
          const members = await matrixService.getFamilyMembers(true);
          memberCount = members.length;
        }

        setInfo({
          syncState: matrixService.getSyncState() || 'Not syncing',
          userId: matrixService.getUserId(),
          homeserver: matrixService.getHomeserverUrl(),
          isLoggedIn: matrixService.isLoggedIn(),
          hasAccessToken: !!matrixService.getAccessToken(),
          familyRoomId,
          memberCount,
        });
      } catch (err) {
        console.error('Failed to load diagnostics:', err);
      }
    };

    loadDiagnostics();

    // Subscribe to sync state changes
    const unsubscribe = matrixService.onSyncStateChange(state => {
      setInfo(prev => ({ ...prev, syncState: state || 'Unknown' }));
    });

    return unsubscribe;
  }, []);

  // Load recent logs
  useEffect(() => {
    const logService = LogService.getInstance();
    const entries = logService.getEntries();
    const recent = entries.slice(-20).map(entry => {
      const time = new Date(entry.timestamp).toLocaleTimeString();
      return `[${time}] ${entry.level.toUpperCase()}: ${entry.message}`;
    });
    setRecentLogs(recent);
  }, []);

  const getSyncStatusColor = (state: string): string => {
    switch (state) {
      case 'SYNCING':
      case 'PREPARED':
        return 'var(--color-success)';
      case 'PREPARING':
        return 'var(--color-accent)';
      case 'STOPPED':
      case 'ERROR':
        return 'var(--color-error)';
      default:
        return 'var(--color-text-muted)';
    }
  };

  const truncateToken = (hasToken: boolean): string => {
    if (!hasToken) return 'None';
    const token = matrixService.getAccessToken();
    if (!token) return 'None';
    return token.substring(0, 8) + '...';
  };

  return (
    <div className="logs-panel">
      <h2 className="section-title">Diagnostics</h2>

      <section className="diagnostic-section">
        <h3 className="subsection-title">Connection</h3>
        <div className="diagnostic-grid">
          <div className="diagnostic-item">
            <span className="diagnostic-label">Status</span>
            <span
              className="diagnostic-value status-value"
              style={{ color: getSyncStatusColor(info.syncState) }}
            >
              <span className="status-dot">●</span> {info.syncState}
            </span>
          </div>
          <div className="diagnostic-item">
            <span className="diagnostic-label">User</span>
            <span className="diagnostic-value">
              {info.userId || 'Not logged in'}
            </span>
          </div>
          <div className="diagnostic-item">
            <span className="diagnostic-label">Server</span>
            <span className="diagnostic-value">{info.homeserver}</span>
          </div>
        </div>
      </section>

      <section className="diagnostic-section">
        <h3 className="subsection-title">Session</h3>
        <div className="diagnostic-grid">
          <div className="diagnostic-item">
            <span className="diagnostic-label">Logged in</span>
            <span className="diagnostic-value">
              {info.isLoggedIn ? 'Yes' : 'No'}
            </span>
          </div>
          <div className="diagnostic-item">
            <span className="diagnostic-label">Token</span>
            <span className="diagnostic-value mono">
              {truncateToken(info.hasAccessToken)}
            </span>
          </div>
        </div>
      </section>

      <section className="diagnostic-section">
        <h3 className="subsection-title">Family Room</h3>
        <div className="diagnostic-grid">
          <div className="diagnostic-item">
            <span className="diagnostic-label">Room ID</span>
            <span className="diagnostic-value mono">
              {info.familyRoomId || 'Not found'}
            </span>
          </div>
          <div className="diagnostic-item">
            <span className="diagnostic-label">Members</span>
            <span className="diagnostic-value">{info.memberCount}</span>
          </div>
        </div>
      </section>

      <section className="diagnostic-section">
        <h3 className="subsection-title">Recent Logs</h3>
        <div className="logs-container">
          {recentLogs.length > 0 ? (
            recentLogs.map((log, i) => (
              <div key={i} className="log-entry">
                {log}
              </div>
            ))
          ) : (
            <div className="log-entry">No recent logs</div>
          )}
        </div>
      </section>

      <style>{`
        .logs-panel {
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

        .diagnostic-section {
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

        .diagnostic-grid {
          display: flex;
          flex-direction: column;
          gap: var(--spacing-sm);
        }

        .diagnostic-item {
          display: flex;
          justify-content: space-between;
          align-items: center;
          padding: var(--spacing-sm) 0;
        }

        .diagnostic-label {
          font-size: var(--font-size-sm);
          color: var(--color-text-muted);
        }

        .diagnostic-value {
          font-size: var(--font-size-sm);
          color: var(--color-text);
          text-align: right;
          word-break: break-all;
          max-width: 60%;
        }

        .diagnostic-value.mono {
          font-family: monospace;
          font-size: var(--font-size-xs);
        }

        .status-value {
          display: flex;
          align-items: center;
          gap: var(--spacing-xs);
        }

        .status-dot {
          font-size: var(--font-size-xs);
        }

        .logs-container {
          background-color: var(--color-surface);
          border: 1px solid var(--color-surface-elevated);
          border-radius: 8px;
          padding: var(--spacing-md);
          max-height: 200px;
          overflow-y: auto;
          font-family: monospace;
          font-size: var(--font-size-xs);
        }

        .log-entry {
          padding: var(--spacing-xs) 0;
          color: var(--color-text-muted);
          white-space: pre-wrap;
          word-break: break-all;
        }

        .log-entry:not(:last-child) {
          border-bottom: 1px solid var(--color-surface-elevated);
        }

        /* Scrollbar styling */
        .logs-container::-webkit-scrollbar {
          width: 6px;
        }

        .logs-container::-webkit-scrollbar-track {
          background: transparent;
        }

        .logs-container::-webkit-scrollbar-thumb {
          background: var(--color-surface-elevated);
          border-radius: 3px;
        }

        .logs-container::-webkit-scrollbar-thumb:hover {
          background: var(--color-text-muted);
        }
      `}</style>
    </div>
  );
}
