import React from 'react';
import { MainView } from './components/MainView.js';
import { LoginView } from './components/LoginView.js';
import { LoadingView } from './components/LoadingView.js';
import { useAuth } from './hooks/useMatrix.js';
import { useContacts } from './hooks/useContacts.js';
import './styles/variables.css';
import './styles/animations.css';

function App() {
  const { isLoggedIn, isLoading } = useAuth();
  const contacts = useContacts();

  // Show loading screen during initial auth check or login
  if (isLoading) {
    return <LoadingView message={isLoggedIn ? 'Syncing...' : 'Connecting...'} />;
  }

  // Show login form if not authenticated
  if (!isLoggedIn) {
    return <LoginView onLoginSuccess={() => { /* Auth state change handles transition */ }} />;
  }

  // Show main app with real contacts
  return (
    <div className="app no-select">
      <MainView contacts={contacts} />

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
