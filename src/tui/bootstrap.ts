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
 *   --help               Show this help message
 *   --profile <name>     Start with the specified profile (e.g., alice, bob)
 *   --send-credentials   Encode and send onboarding credentials via AudioCode
 *   --receive-credentials Record and decode onboarding credentials via AudioCode
 *   --debug              Disable LogService and enable verbose console logging
 */

// Parse CLI arguments
const args = process.argv.slice(2);
const showHelp = args.includes('--help');
const profileIndex = args.indexOf('--profile');
const initialProfile =
  profileIndex !== -1 && args[profileIndex + 1] ? args[profileIndex + 1] : null;
const sendCredentials = args.includes('--send-credentials');
const receiveCredentials = args.includes('--receive-credentials');
const debugMode = args.includes('--debug');

// Show help and exit
if (showHelp) {
  console.log('WATA - Voice Messaging TUI\n');
  console.log('Usage: pnpm tui [options]\n');
  console.log('Options:');
  console.log('  --help               Show this help message');
  console.log('  --profile <name>     Start with the specified profile (e.g., alice, bob)');
  console.log('  --send-credentials   Encode and send onboarding credentials via AudioCode');
  console.log('  --receive-credentials Record and decode onboarding credentials via AudioCode');
  console.log('  --debug              Disable LogService and enable verbose console logging\n');
  console.log('Profiles:');
  console.log('  alice                Alice (default)');
  console.log('  bob                  Bob');
  console.log('  charlie              Charlie\n');
  console.log('Examples:');
  console.log('  pnpm tui                              Start with default profile (alice)');
  console.log('  pnpm tui -- --profile bob             Start as bob');
  console.log('  pnpm tui -- --debug                   Enable debug logging');
  console.log('  pnpm tui -- --help                    Show this help\n');
  process.exit(0);
}

// Export debug mode for other modules to check
export const isDebugMode = debugMode;

// Enable alternate screen buffer (should disable scrollback)
// Note: This works in most terminals but NOT in macOS Terminal.app
// TODO: Terminal.app doesn't respect DECSET 1049/47 - investigate alternatives
// See: https://github.com/derv92/wata/issues
process.stdout.write('\x1b[?1049h');

// Cleanup function to restore terminal state
const restoreTerminal = () => {
  // Disable alternate screen buffer
  process.stdout.write('\x1b[?1049l');
};

// Ensure terminal is restored on exit
process.on('exit', restoreTerminal);

async function bootstrap() {
  // Step 1: Import LogService (but skip installation in debug mode)
  const { LogService: _LogService } = await import('./services/LogService.js');

  if (debugMode) {
    // In debug mode, immediately uninstall LogService to get direct console output
    _LogService.getInstance().uninstall();
    console.log('[bootstrap] DEBUG MODE: LogService disabled, using direct console');
  } else {
    // Normal mode: verify LogService is installed
    console.log(
      '[bootstrap] LogService installed, this message should be captured',
    );
  }
  if (initialProfile) {
    console.log(`[bootstrap] Initial profile from CLI: ${initialProfile}`);
  }

  // Step 2: Initialize audio service
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

  // Step 3: Set up cleanup handlers
  const cleanup = async () => {
    await pvRecorderAudioService.release();
    restoreTerminal(); // Restore normal screen buffer with scrollback
  };

  process.on('SIGINT', async () => {
    await cleanup();
    process.exit(0);
  });

  process.on('SIGTERM', async () => {
    await cleanup();
    process.exit(0);
  });

  // Step 4: Check for AudioCode CLI commands (skip TUI if specified)
  if (sendCredentials || receiveCredentials) {
    // Restore terminal for AudioCode mode (no alternate screen buffer)
    restoreTerminal();
    // Uninstall LogService so console output goes directly to terminal
    _LogService.getInstance().uninstall();

    await runCredentialsCommand(sendCredentials ? 'send' : 'receive');
    await cleanup();
    process.exit(0);
    return; // Unreachable but TypeScript needs it
  }

  // Step 5: Now import the rest of the app
  const React = await import('react');
  const { render } = await import('ink');
  const { App } = await import('./App.js');

  // Step 6: Render with initial profile (pass debug mode through)
  // Ink will now render to the alternate screen buffer (no scrollback)
  render(React.createElement(App, { initialProfile, debugMode }));
}

/**
 * Run audio onboarding command directly (send or receive)
 *
 * Send mode: Encode data → Play tones → (Optionally wait for ACK)
 * Receive mode: Record → Decode → Play ACK tones
 */
async function runCredentialsCommand(command: 'send' | 'receive') {
  const { encodeAudioCode, decodeAudioCode, DEFAULT_CONFIG } =
    await import('../shared/lib/audiocode.js');
  const { encodeWav, writeWavTempFile } = await import('../shared/lib/wav.js');
  const { tuiAudioService } = await import('./services/TuiAudioService.js');
  const { unlink } = await import('fs/promises');

  // Example onboarding data
  const EXAMPLE_ONBOARDING_DATA = {
    homeserver: 'https://matrix.org',
    username: 'alice',
    password: 'walkietalkie123',
    room: '!family:matrix.org',
  };

  // ACK message for receiver to send back
  const ACK_DATA = {
    type: 'ack',
    message: 'Credentials received!',
  };

  const RECORDING_DURATION = 16000; // 16 seconds (MFSK with 100% RS redundancy)
  const PLAYBACK_DELAY = 500; // 500ms delay after playback

  if (command === 'send') {
    console.log('\n=== AUDIO ONBOARDING SEND MODE (AudioCode) ===');
    console.log('[1/4] Encoding onboarding data...');
    const samples = encodeAudioCode(EXAMPLE_ONBOARDING_DATA, DEFAULT_CONFIG);
    const duration = samples.length / DEFAULT_CONFIG.sampleRate;
    console.log(
      `      Encoded ${samples.length} samples (${duration.toFixed(1)}s at ${DEFAULT_CONFIG.sampleRate}Hz)`,
    );
    console.log(`      Data: ${JSON.stringify(EXAMPLE_ONBOARDING_DATA)}`);

    console.log('\n[2/4] Converting to WAV format...');
    const wavBuffer = encodeWav(samples, DEFAULT_CONFIG.sampleRate);
    console.log(`      WAV size: ${wavBuffer.length} bytes`);
    const wavPath = await writeWavTempFile(wavBuffer);
    console.log(`      Temp file: ${wavPath}`);

    console.log('\n[3/4] Playing AudioCode tones...');
    console.log('      ▼ Start receiver now! ▼');
    await tuiAudioService.playWav(wavPath);

    // Wait for playback to complete
    await new Promise(resolve =>
      setTimeout(resolve, Math.ceil(duration * 1000) + PLAYBACK_DELAY),
    );
    console.log(`      Playback complete (${duration.toFixed(1)}s)`);

    // Clean up temp file
    await unlink(wavPath).catch(() => {});

    console.log('\n[4/4] Send complete! Receiver should acknowledge.');
    console.log('\n=== END ===\n');
  } else {
    console.log('\n=== AUDIO ONBOARDING RECEIVE MODE (AudioCode) ===');
    console.log(
      `[1/5] Starting ${RECORDING_DURATION / 1000}s recording window...`,
    );
    console.log('      ▼ Start sender now! ▼');

    const startTime = Date.now();
    const samples = await tuiAudioService.recordRawPcm(RECORDING_DURATION);
    const recordTime = (Date.now() - startTime) / 1000;

    console.log(
      `\n[2/5] Recording complete: ${samples.length} samples (${recordTime.toFixed(1)}s)`,
    );

    const samplesPerSymbol = Math.round(
      (DEFAULT_CONFIG.sampleRate * DEFAULT_CONFIG.symbolDuration) / 1000,
    );
    console.log('\n[3/5] Decoding AudioCode tones...');
    console.log(`      Samples per symbol: ${samplesPerSymbol}`);
    console.log(
      `      Expected symbols: ~${Math.floor(samples.length / samplesPerSymbol)}`,
    );

    try {
      const data = await decodeAudioCode(samples, DEFAULT_CONFIG);
      console.log('\n[4/5] ✓ DECODE SUCCESSFUL!');
      console.log('\n      Received data:');
      console.log(
        '      ' + JSON.stringify(data, null, 2).split('\n').join('\n      '),
      );

      // Send ACK back to sender
      console.log('\n[5/5] Sending ACK acknowledgment...');
      const ackSamples = encodeAudioCode(ACK_DATA, DEFAULT_CONFIG);
      const ackDuration = ackSamples.length / DEFAULT_CONFIG.sampleRate;
      const ackWav = encodeWav(ackSamples, DEFAULT_CONFIG.sampleRate);
      const ackPath = await writeWavTempFile(ackWav);

      console.log('      Playing ACK tones (sender should hear them)...');
      await tuiAudioService.playWav(ackPath);
      await new Promise(resolve =>
        setTimeout(resolve, Math.ceil(ackDuration * 1000) + PLAYBACK_DELAY),
      );

      await unlink(ackPath).catch(() => {});
      console.log('      ACK sent!');

      console.log('\n=== RECEIVE SUCCESSFUL ===\n');
    } catch (error) {
      const msg = error instanceof Error ? error.message : String(error);
      console.error(`\n[4/5] ✗ DECODE FAILED: ${msg}`);
      console.error('\n      Possible issues:');
      console.error('      - Audio too quiet (move mic closer to speaker)');
      console.error('      - Background noise (use a quiet room)');
      console.error('      - Wrong sample rate (check recording device)');
      console.error('      - Timing mismatch (start receiver before sender)');
      console.error('\n=== RECEIVE FAILED ===\n');
      throw error;
    }
  }
}

bootstrap().catch(err => {
  // This goes to LogService if installed, otherwise to real console
  console.error('[bootstrap] Failed to start TUI:', err);
  process.exit(1);
});
