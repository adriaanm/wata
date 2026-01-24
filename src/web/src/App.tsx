import { useState, useCallback } from 'react';

import { AdminDrawer } from './components/admin/index.js';
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

  const handleOpenAdmin = useCallback(() => {
    setViewState({ view: 'admin' });
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

  // Render view based on state
  const renderView = () => {
    switch (viewState.view) {
      case 'main':
        return (
          <MainView
            contacts={contacts}
            onOpenHistory={handleOpenHistory}
            onOpenAdmin={handleOpenAdmin}
          />
        );
      case 'history':
        return (
          <HistoryView contact={viewState.contact} onBack={handleBackToMain} />
        );
      case 'admin':
        return (
          <>
            <MainView
              contacts={contacts}
              onOpenHistory={handleOpenHistory}
              onOpenAdmin={handleOpenAdmin}
            />
            <AdminDrawer onClose={handleBackToMain} />
          </>
        );
    }
  };

  // Show main app with real contacts
  return (
    <div className="app no-select">
      {renderView()}

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
