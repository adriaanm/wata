/**
 * Jest setup file for integration tests
 *
 * Configures the Matrix SDK logger to reduce verbosity during tests.
 * Only warnings and errors are shown by default.
 */

// Import logger directly from the logger module
import { logger } from 'matrix-js-sdk/lib/logger.js';

// Silence Matrix SDK logs during tests
// Available levels: 'trace', 'debug', 'info', 'warn', 'error', 'silent'
// We use 'silent' because the SDK's RTC features log warnings about unknown rooms during tests.
logger.setLevel('silent');

// Also reduce our own logging in tests
// Override console methods to filter out noisy logs
const originalConsoleLog = console.log;
const originalConsoleDebug = console.debug;
const originalConsoleError = console.error;
const originalConsoleWarn = console.warn;
const originalConsoleInfo = console.info;

// Patterns to filter out (benign SDK noise and verbose test logs)
// Set VERBOSE_TESTS=1 to see all logs
const noisyPatterns = process.env.VERBOSE_TESTS
  ? []
  : [
      '[FixedFetch]',
      '[matrix-auth]',
      '[TestClient:',
      '[TestOrchestrator]',
      'MatrixRTCSessionManager',
      'MatrixRTCSession',
      'Missing default',
      'Adding default',
      'ignoring leave call',
      'sync ', // SDK sync debug logs
    ];

function shouldFilter(args: unknown[]): boolean {
  // Check all args, not just the first one (SDK may pass formatted strings)
  const fullMessage = args.map(arg => String(arg)).join(' ');
  return noisyPatterns.some(pattern => fullMessage.includes(pattern));
}

console.log = (...args: unknown[]) => {
  if (!shouldFilter(args)) originalConsoleLog(...args);
};

console.debug = (...args: unknown[]) => {
  if (!shouldFilter(args)) originalConsoleDebug(...args);
};

console.error = (...args: unknown[]) => {
  if (!shouldFilter(args)) originalConsoleError(...args);
};

console.warn = (...args: unknown[]) => {
  if (!shouldFilter(args)) originalConsoleWarn(...args);
};

console.info = (...args: unknown[]) => {
  if (!shouldFilter(args)) originalConsoleInfo(...args);
};
