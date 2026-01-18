import type { Contact } from '../types.js';

export const mockContacts: Contact[] = [
  {
    id: 'mom',
    name: 'Mom',
    type: 'dm',
    unreadCount: 2,
  },
  {
    id: 'dad',
    name: 'Dad',
    type: 'dm',
  },
  {
    id: 'sister',
    name: 'Sister',
    type: 'dm',
    hasError: true,
  },
  {
    id: 'brother',
    name: 'Brother',
    type: 'dm',
    unreadCount: 5,
  },
  {
    id: 'family',
    name: 'Family',
    type: 'family',
    unreadCount: 3,
  },
];
