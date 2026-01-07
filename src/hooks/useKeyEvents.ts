import { useState, useEffect } from 'react';
import { DeviceEventEmitter } from 'react-native';

interface KeyEvent {
  keyCode: number;
  action: 'DOWN' | 'UP';
  keyName: string;
  timestamp: number;
}

export function useKeyEvents(maxHistory = 10) {
  const [lastKey, setLastKey] = useState<KeyEvent | null>(null);
  const [keyHistory, setKeyHistory] = useState<KeyEvent[]>([]);

  useEffect(() => {
    const subscription = DeviceEventEmitter.addListener(
      'onKeyEvent',
      (event: { keyCode: number; action: string; keyName: string }) => {
        const keyEvent: KeyEvent = {
          keyCode: event.keyCode,
          action: event.action as 'DOWN' | 'UP',
          keyName: event.keyName,
          timestamp: Date.now(),
        };

        setLastKey(keyEvent);

        // Only add DOWN events to history to avoid duplicates
        if (event.action === 'DOWN') {
          setKeyHistory(prev => [keyEvent, ...prev].slice(0, maxHistory));
        }
      },
    );

    return () => {
      subscription.remove();
    };
  }, [maxHistory]);

  return { lastKey, keyHistory };
}
