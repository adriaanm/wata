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
const initialProfile =
  profileIndex !== -1 && args[profileIndex + 1] ? args[profileIndex + 1] : null;

// Clear screen first
process.stdout.write('\x1Bc');

async function bootstrap() {
  // Step 1: Import and install LogService FIRST
  // Note: LogService auto-installs on import, so we don't need to use the value
  const { LogService: _LogService } = await import('./services/LogService.js');
  // Note: LogService auto-installs on import, but we can verify it's installed
  console.log(
    '[bootstrap] LogService installed, this message should be captured',
  );
  if (initialProfile) {
    console.log(`[bootstrap] Initial profile from CLI: ${initialProfile}`);
  }

  // Step 2: Configure global matrix-js-sdk logger to redirect to LogService
  // This must happen BEFORE any Matrix SDK code is imported
  const { ensureGlobalMatrixLogger } =
    await import('./services/MatrixLogger.js');
  await ensureGlobalMatrixLogger();
  console.log('[bootstrap] Global matrix-js-sdk logger configured');

  // Step 3: Initialize audio service
  const { pvRecorderAudioService } =
    await import('./services/PvRecorderAudioService.js');
  try {
    await pvRecorderAudioService.initialize();
    console.log('[bootstrap] Audio service initialized');
  } catch (error) {
    const errorMsg = error instanceof Error ? error.message : String(error);
    console.log(`[bootstrap] Audio service initialization failed: ${errorMsg}`);
    // Continue anyway - audio errors will be shown when user tries to record
  }

  // Step 4: Set up cleanup handlers
  const cleanup = async () => {
    await pvRecorderAudioService.release();
  };

  process.on('SIGINT', async () => {
    await cleanup();
    process.exit(0);
  });

  process.on('SIGTERM', async () => {
    await cleanup();
    process.exit(0);
  });

  // Step 5: Now import the rest of the app
  const React = await import('react');
  const { render } = await import('ink');
  const { App } = await import('./App.js');

  // Step 6: Render with initial profile
  render(React.createElement(App, { initialProfile }));
}

bootstrap().catch(err => {
  // This goes to LogService if installed, otherwise to real console
  console.error('[bootstrap] Failed to start TUI:', err);
  process.exit(1);
});
