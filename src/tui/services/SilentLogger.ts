import { LogService } from './LogService.js';

/**
 * A silent logger for matrix-js-sdk that captures important logs to LogService
 * instead of writing to console/stdout.
 *
 * Implements the matrix-js-sdk Logger interface.
 * Only errors and warnings are captured; debug/info/trace are silenced.
 */
export class SilentLogger {
  private prefix: string;

  constructor(prefix = '') {
    this.prefix = prefix;
  }

  private formatMessage(...args: unknown[]): string {
    const msg = args
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
    return this.prefix ? `[${this.prefix}] ${msg}` : msg;
  }

  private addToLogService(
    level: 'log' | 'warn' | 'error',
    ...args: unknown[]
  ): void {
    LogService.getInstance().addEntry(level, this.formatMessage(...args));
  }

  // Silenced - trace is very verbose
  trace(): void {}

  // Silenced - debug is verbose
  debug(): void {}

  // Silenced - info is too chatty for TUI
  info(): void {}

  // Silenced - log is too chatty for TUI
  log(): void {}

  // Capture warnings to LogService
  warn(...args: unknown[]): void {
    this.addToLogService('warn', ...args);
  }

  // Capture errors to LogService
  error(...args: unknown[]): void {
    this.addToLogService('error', ...args);
  }

  getChild(namespace: string): SilentLogger {
    const childPrefix = this.prefix ? `${this.prefix}:${namespace}` : namespace;
    return new SilentLogger(childPrefix);
  }
}

/**
 * Singleton silent logger instance for matrix-js-sdk
 */
export const silentLogger = new SilentLogger('matrix');
