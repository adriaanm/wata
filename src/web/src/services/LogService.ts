/**
 * Web LogService - simple console-based logging for web UI.
 *
 * Unlike the TUI LogService, this just uses console methods directly
 * since the web browser doesn't have the same UI corruption concerns.
 */

export interface LogEntry {
  timestamp: number;
  level: 'log' | 'warn' | 'error' | 'success';
  message: string;
}

/**
 * Web LogService provides logging for the web UI.
 * Logs are output to console for browser dev tools.
 */
export class LogService {
  private static instance: LogService;

  private constructor() {
    // Private constructor for singleton
  }

  static getInstance(): LogService {
    if (!LogService.instance) {
      LogService.instance = new LogService();
    }
    return LogService.instance;
  }

  /**
   * Add a log entry (outputs to console)
   */
  addEntry(level: 'log' | 'warn' | 'error' | 'success', message: string): void {
    // const timestamp = Date.now(); // Available for future logging features
    const logFn = level === 'log' ? console.log :
                    level === 'warn' ? console.warn :
                    level === 'error' ? console.error :
                    console.info;
    logFn(`[MatrixService] ${level.toUpperCase()}: ${message}`);
  }
}
