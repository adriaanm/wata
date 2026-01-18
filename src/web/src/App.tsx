import React from 'react';
import { MainView } from './components/MainView';
import { mockContacts } from './data/mockData';
import './styles/variables.css';
import './styles/animations.css';

function App() {
  // Phase 1-2: Use mock data for UI development
  // Phase 3+: Will integrate real Matrix connectivity
  return (
    <div className="app no-select">
      <MainView contacts={mockContacts} />

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
