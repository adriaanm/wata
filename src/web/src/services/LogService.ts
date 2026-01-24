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

const MAX_ENTRIES = 100;

/**
 * Web LogService provides logging for the web UI.
 * Logs are output to console for browser dev tools and stored in memory for diagnostics.
 */
export class LogService {
  private static instance: LogService;
  private entries: LogEntry[] = [];

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
   * Add a log entry (outputs to console and stores in memory)
   */
  addEntry(level: 'log' | 'warn' | 'error' | 'success', message: string): void {
    const entry: LogEntry = {
      timestamp: Date.now(),
      level,
      message,
    };

    // Store in memory (keep last MAX_ENTRIES)
    this.entries.push(entry);
    if (this.entries.length > MAX_ENTRIES) {
      this.entries.shift();
    }

    // Output to console
    const logFn =
      level === 'log'
        ? console.log
        : level === 'warn'
          ? console.warn
          : level === 'error'
            ? console.error
            : console.info;
    logFn(`[MatrixService] ${level.toUpperCase()}: ${message}`);
  }

  /**
   * Get all stored log entries
   */
  getEntries(): LogEntry[] {
    return [...this.entries];
  }
}
