export interface LogEntry {
  timestamp: number;
  level: 'log' | 'warn' | 'error' | 'success';
  message: string;
}

/**
 * LogService intercepts console.log/warn/error to prevent UI corruption
 * in the Ink terminal interface. Logs are stored in a circular buffer
 * and can be viewed via the LogView component.
 */
export class LogService {
  private static instance: LogService;
  private logs: LogEntry[] = [];
  private maxLogs = 1000; // Circular buffer size
  private originalConsole = {
    log: console.log,
    warn: console.warn,
    error: console.error,
  };

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
   * Install console interceptors. Call this once at app startup.
   */
  install(): void {
    console.log = (...args: unknown[]) => this.capture('log', args);
    console.warn = (...args: unknown[]) => this.capture('warn', args);
    console.error = (...args: unknown[]) => this.capture('error', args);
  }

  /**
   * Restore original console methods (for cleanup/testing)
   */
  uninstall(): void {
    console.log = this.originalConsole.log;
    console.warn = this.originalConsole.warn;
    console.error = this.originalConsole.error;
  }

  private capture(level: LogEntry['level'], args: unknown[]): void {
    try {
      const message = args
        .map(arg => {
          if (typeof arg === 'object' && arg !== null) {
            try {
              return JSON.stringify(arg);
            } catch {
              return '[Object]';
            }
          }
          return String(arg);
        })
        .join(' ');

      const entry: LogEntry = {
        timestamp: Date.now(),
        level,
        message,
      };

      this.logs.push(entry);

      // Circular buffer: remove oldest when exceeding max
      if (this.logs.length > this.maxLogs) {
        this.logs.shift();
      }

      // Optionally write to original console for debugging
      // Uncomment this if you need to see logs in the terminal:
      // this.originalConsole[level](...args);
    } catch (error) {
      // Fallback: if logging itself fails, use original console
      this.originalConsole.error('LogService capture failed:', error);
    }
  }

  /**
   * Get all logs (returns a copy to prevent mutation)
   */
  getLogs(): LogEntry[] {
    return [...this.logs];
  }

  /**
   * Clear all logs
   */
  clear(): void {
    this.logs = [];
  }

  /**
   * Get log count
   */
  getCount(): number {
    return this.logs.length;
  }

  /**
   * Add a log entry directly
   */
  addEntry(level: LogEntry['level'], message: string): void {
    this.logs.push({
      timestamp: Date.now(),
      level,
      message,
    });

    // Circular buffer: remove oldest when exceeding max
    if (this.logs.length > this.maxLogs) {
      this.logs.shift();
    }
  }
}

// Auto-install when this module is first imported
// This ensures console is intercepted before any other code runs
LogService.getInstance().install();
