import { useState, useCallback, useEffect } from 'react';
import type { Contact } from '../types.js';

export function useContactSelection(contacts: Contact[]) {
  const [selectedIndex, setSelectedIndex] = useState(0);

  const selectNext = useCallback(() => {
    setSelectedIndex(prev => Math.min(contacts.length - 1, prev + 1));
  }, [contacts.length]);

  const selectPrevious = useCallback(() => {
    setSelectedIndex(prev => Math.max(0, prev - 1));
  }, []);

  const selectedContact = contacts[selectedIndex] ?? null;

  // Handle keyboard navigation
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'ArrowDown' || e.key === 'j') {
        e.preventDefault();
        selectNext();
      } else if (e.key === 'ArrowUp' || e.key === 'k') {
        e.preventDefault();
        selectPrevious();
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [selectNext, selectPrevious]);

  return {
    selectedIndex,
    selectedContact,
    setSelectedIndex,
    selectNext,
    selectPrevious,
  };
}
