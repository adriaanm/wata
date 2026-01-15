#!/usr/bin/env node
/// <reference types="node" />

/**
 * Bootstrap file for TUI that ensures LogService is installed
 * before ANY other modules are loaded.
 *
 * This works around ESM's hoisting behavior by:
 * 1. Having zero static imports (only dynamic imports)
 * 2. Installing LogService first
 * 3. Then dynamically importing the rest of the app
 *
 * CLI arguments:
 *   --profile <name>  Start with the specified profile (e.g., alice, bob)
 */

// Parse CLI arguments
const args = process.argv.slice(2);
const profileIndex = args.indexOf('--profile');
const initialProfile = profileIndex !== -1 && args[profileIndex + 1]
  ? args[profileIndex + 1]
  : null;

// Clear screen first
process.stdout.write('\x1Bc');

async function bootstrap() {
  // Step 1: Import and install LogService FIRST
  const { LogService } = await import('./services/LogService.js');
  // Note: LogService auto-installs on import, but we can verify it's installed
  console.log('[bootstrap] LogService installed, this message should be captured');
  if (initialProfile) {
    console.log(`[bootstrap] Initial profile from CLI: ${initialProfile}`);
  }

  // Step 2: Now import the rest of the app
  const React = await import('react');
  const { render } = await import('ink');
  const { App } = await import('./App.js');

  // Step 3: Render with initial profile
  render(React.createElement(App, { initialProfile }));
}

bootstrap().catch((err) => {
  // This goes to LogService if installed, otherwise to real console
  console.error('[bootstrap] Failed to start TUI:', err);
  process.exit(1);
});
