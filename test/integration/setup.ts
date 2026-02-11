/**
 * Jest setup file for integration tests
 *
 * Reduces log verbosity during tests.
 * Only warnings and errors are shown by default.
 */

// Override console methods to filter out noisy logs
const originalConsoleLog = console.log;
const originalConsoleDebug = console.debug;
const originalConsoleError = console.error;
const originalConsoleWarn = console.warn;
const originalConsoleInfo = console.info;

// Patterns to filter out (verbose test logs)
// Set VERBOSE_TESTS=1 to see all logs
const noisyPatterns = process.env.VERBOSE_TESTS
  ? []
  : [
      '[TestClient:',
      '[TestOrchestrator]',
      'sync ', // sync debug logs
    ];

function shouldFilter(args: unknown[]): boolean {
  // Check all args, not just the first one
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
