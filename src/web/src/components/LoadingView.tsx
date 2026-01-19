interface LoadingViewProps {
  message: string;
}

export function LoadingView({ message }: LoadingViewProps) {
  return (
    <div className="loading-view">
      <div className="loading-spinner" />
      <p className="loading-message">{message}</p>

      <style>{`
        .loading-view {
          height: 100vh;
          width: 100vw;
          display: flex;
          flex-direction: column;
          align-items: center;
          justify-content: center;
          background-color: var(--color-background);
        }

        .loading-spinner {
          width: 48px;
          height: 48px;
          border: 4px solid var(--color-surface);
          border-top-color: var(--color-accent);
          border-radius: 50%;
          animation: spin 1s linear infinite;
        }

        @keyframes spin {
          to {
            transform: rotate(360deg);
          }
        }

        .loading-message {
          margin-top: var(--spacing-lg);
          font-size: var(--font-size-lg);
          color: var(--color-text-muted);
        }
      `}</style>
    </div>
  );
}
