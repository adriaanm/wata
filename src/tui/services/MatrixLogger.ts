/// <reference types="node" />

import { LogService } from './LogService.js';

/**
 * Configure the global loglevel logger used by matrix-js-sdk.
 *
 * The SDK uses the loglevel library internally, and passing a custom logger
 * to createClient() only overrides the client's logger. Some SDK modules
 * (like MatrixRTCSession) use the global loglevel logger directly, which
 * still outputs to console.
 *
 * This function installs a global handler that redirects all loglevel output
 * to LogService, ensuring complete silence on console/stdout.
 */
export async function configureGlobalMatrixLogger(): Promise<void> {
   
  const loglevel = (await import('loglevel')).default;

  // Store original loglevel methodFactory
  const originalFactory = loglevel.methodFactory;

  // Custom method factory that redirects to LogService
  loglevel.methodFactory = function (
    methodName: string,
    logLevel: number,
    loggerName: string | symbol,
  ) {
    // Return a new function that redirects to LogService instead of console
    return function (...args: unknown[]) {
      // Format the message
      const nameStr =
        typeof loggerName === 'symbol' ? String(loggerName) : loggerName;
      const prefix = nameStr !== 'matrix' ? `[${nameStr}] ` : '';
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

      // Map loglevel method names to LogService levels
      const levelMap: Record<string, 'log' | 'warn' | 'error'> = {
        trace: 'log',
        debug: 'log',
        info: 'log',
        warn: 'warn',
        error: 'error',
      };

      const level = levelMap[methodName] || 'log';

      // Send to LogService
      LogService.getInstance().addEntry(level, prefix + message);
    };
  };

  // Rebuild all existing loggers to apply the new methodFactory
  // This includes the default 'matrix' logger and any child loggers
  const loggers = [
    'matrix',
    'matrix-sdk',
    'MatrixRTCSession',
    'sync',
    'client',
  ];
  for (const name of loggers) {
    const logger = loglevel.getLogger(name);
    logger.rebuild();
  }

  // Also rebuild the default logger
  loglevel.getLogger('matrix').rebuild();
}

/**
 * Initialize global matrix-js-sdk logging configuration.
 * Should be called once at app startup before any Matrix SDK usage.
 */
let globalLoggerConfigured = false;

export async function ensureGlobalMatrixLogger(): Promise<void> {
  if (globalLoggerConfigured) return;

  try {
    await configureGlobalMatrixLogger();
    globalLoggerConfigured = true;
  } catch (error) {
    // If loglevel is not available or setup fails, silently continue
    // LogService may not be fully initialized yet
  }
}
