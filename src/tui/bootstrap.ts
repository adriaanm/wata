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
 *   --profile <name>   Start with the specified profile (e.g., alice, bob)
 *   --afsk-send        Encode and send AFSK onboarding tones
 *   --afsk-receive     Record and decode AFSK onboarding tones
 */

// Parse CLI arguments
const args = process.argv.slice(2);
const profileIndex = args.indexOf('--profile');
const initialProfile =
  profileIndex !== -1 && args[profileIndex + 1] ? args[profileIndex + 1] : null;
const afskSend = args.includes('--afsk-send');
const afskReceive = args.includes('--afsk-receive');

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

  // Step 5: Check for AFSK CLI commands (skip TUI if specified)
  if (afskSend || afskReceive) {
    // Restore terminal for AFSK mode (no alternate screen buffer)
    restoreTerminal();
    // Uninstall LogService so console output goes directly to terminal
    _LogService.getInstance().uninstall();

    await runAfskCommand(afskSend ? 'send' : 'receive');
    await cleanup();
    process.exit(0);
    return; // Unreachable but TypeScript needs it
  }

  // Step 6: Now import the rest of the app
  const React = await import('react');
  const { render } = await import('ink');
  const { App } = await import('./App.js');

  // Step 7: Render with initial profile
  // Ink will now render to the alternate screen buffer (no scrollback)
  render(React.createElement(App, { initialProfile }));
}

/**
 * Run AFSK command directly (send or receive)
 *
 * Send mode: Encode data → Play tones → (Optionally wait for ACK)
 * Receive mode: Record → Decode → Play ACK tones
 */
async function runAfskCommand(command: 'send' | 'receive') {
  const { encodeAfsk, decodeAfsk, DEFAULT_CONFIG, getAfskDebugLog } =
    await import('../shared/lib/afsk.js');
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

  const RECORDING_DURATION = 10000; // 10 seconds
  const PLAYBACK_DELAY = 500; // 500ms delay after playback

  if (command === 'send') {
    console.log('\n=== AFSK SEND MODE ===');
    console.log('[1/4] Encoding onboarding data...');
    const samples = encodeAfsk(EXAMPLE_ONBOARDING_DATA, DEFAULT_CONFIG);
    const duration = samples.length / DEFAULT_CONFIG.sampleRate;
    console.log(
      `      Encoded ${samples.length} samples (${duration.toFixed(2)}s at ${DEFAULT_CONFIG.sampleRate}Hz)`,
    );
    console.log(`      Data: ${JSON.stringify(EXAMPLE_ONBOARDING_DATA)}`);

    console.log('\n[2/4] Converting to WAV format...');
    const wavBuffer = encodeWav(samples, DEFAULT_CONFIG.sampleRate);
    console.log(`      WAV size: ${wavBuffer.length} bytes`);
    const wavPath = await writeWavTempFile(wavBuffer);
    console.log(`      Temp file: ${wavPath}`);

    console.log('\n[3/4] Playing AFSK tones...');
    console.log('      ▼ Start receiver now! ▼');
    await tuiAudioService.playWav(wavPath);

    // Wait for playback to complete
    await new Promise(resolve =>
      setTimeout(resolve, Math.ceil(duration * 1000) + PLAYBACK_DELAY),
    );
    console.log(`      Playback complete (${(duration + 1).toFixed(1)}s)`);

    // Clean up temp file
    await unlink(wavPath).catch(() => {});

    console.log('\n[4/4] Send complete! Receiver should acknowledge.');
    console.log('\n=== END ===\n');
  } else {
    console.log('\n=== AFSK RECEIVE MODE ===');
    console.log(
      `[1/5] Starting ${RECORDING_DURATION / 1000}s recording window...`,
    );
    console.log('      ▼ Start sender now! ▼');

    const startTime = Date.now();
    const samples = await tuiAudioService.recordRawPcm(RECORDING_DURATION);
    const recordTime = (Date.now() - startTime) / 1000;

    console.log(
      `\n[2/5] Recording complete: ${samples.length} samples (${recordTime.toFixed(2)}s)`,
    );

    console.log('\n[3/5] Decoding AFSK tones...');
    console.log(
      `      Samples per bit: ${DEFAULT_CONFIG.sampleRate / DEFAULT_CONFIG.baudRate}`,
    );
    console.log(
      `      Expected bits: ~${Math.floor(samples.length / (DEFAULT_CONFIG.sampleRate / DEFAULT_CONFIG.baudRate))}`,
    );

    try {
      const data = await decodeAfsk(samples, DEFAULT_CONFIG);
      console.log('\n[4/5] ✓ DECODE SUCCESSFUL!');
      console.log('\n      Received data:');
      console.log(
        '      ' + JSON.stringify(data, null, 2).split('\n').join('\n      '),
      );

      // Send ACK back to sender
      console.log('\n[5/5] Sending ACK acknowledgment...');
      const ackSamples = encodeAfsk(ACK_DATA, DEFAULT_CONFIG);
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
      console.error('\n      Debug info:');
      const debugLogs = getAfskDebugLog();
      for (const log of debugLogs) {
        console.error(`      - ${log}`);
      }
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
