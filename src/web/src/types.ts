export interface Contact {
  id: string;
  name: string;
  type: 'dm' | 'family';
  unreadCount?: number;
  hasError?: boolean;
  avatarUrl?: string;
}

export type RecordingState = 'idle' | 'starting' | 'recording' | 'sending';

export interface RecordingStatus {
  state: RecordingState;
  duration: number;
  contactId: string | null;
}

// View navigation state
export type ViewState =
  | { view: 'main' }
  | { view: 'history'; contact: Contact }
  | { view: 'admin' };

// Admin panel tabs
export type AdminPanel = 'family' | 'invite' | 'settings' | 'logs';
