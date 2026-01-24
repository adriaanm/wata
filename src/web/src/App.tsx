import { useState, useCallback } from 'react';

import { HistoryView } from './components/HistoryView.js';
import { LoadingView } from './components/LoadingView.js';
import { LoginView } from './components/LoginView.js';
import { MainView } from './components/MainView.js';
import { useContacts } from './hooks/useContacts.js';
import { useAuth } from './hooks/useMatrix.js';
import type { Contact, ViewState } from './types.js';
import './styles/variables.css';
import './styles/animations.css';

function App() {
  const { isLoggedIn, isLoading } = useAuth();
  const contacts = useContacts();
  const [viewState, setViewState] = useState<ViewState>({ view: 'main' });

  const handleOpenHistory = useCallback((contact: Contact) => {
    setViewState({ view: 'history', contact });
  }, []);

  const handleBackToMain = useCallback(() => {
    setViewState({ view: 'main' });
  }, []);

  // Show loading screen during initial auth check or login
  if (isLoading) {
    return (
      <LoadingView message={isLoggedIn ? 'Syncing...' : 'Connecting...'} />
    );
  }

  // Show login form if not authenticated
  if (!isLoggedIn) {
    return (
      <LoginView
        onLoginSuccess={() => {
          /* Auth state change handles transition */
        }}
      />
    );
  }

  // Show main app with real contacts
  return (
    <div className="app no-select">
      {viewState.view === 'main' ? (
        <MainView contacts={contacts} onOpenHistory={handleOpenHistory} />
      ) : (
        <HistoryView contact={viewState.contact} onBack={handleBackToMain} />
      )}

      <style>{`
        .app {
          width: 100%;
          height: 100%;
        }
      `}</style>
    </div>
  );
}

export default App;
