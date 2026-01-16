#!/usr/bin/env node

/**
 * Simple POC Test for PvRecorder + @discordjs/opus
 *
 * This version uses fixed frame sizes that match Opus requirements.
 */

import { PvRecorder } from '@picovoice/pvrecorder-node';
import pkg from '@discordjs/opus';
const { OpusEncoder } = pkg;
import { writeFile } from 'fs/promises';
import { join } from 'path';
import { tmpdir } from 'os';

const colors = {
  reset: '\x1b[0m',
  green: '\x1b[32m',
  red: '\x1b[31m',
  yellow: '\x1b[33m',
  blue: '\x1b[34m',
  magenta: '\x1b[35m',
};

function log(message, color = colors.reset) {
  console.log(`${color}${message}${colors.reset}`);
}

function logSection(title) {
  console.log('');
  log('-'.repeat(60), colors.blue);
  log(`  ${title}`, colors.blue);
  log('-'.repeat(60), colors.blue);
}

async function main() {
  logSection('PvRecorder + @discordjs/opus Simple POC');

  const OPUS_CHANNELS = 1;
  const RECORDING_DURATION_MS = 5000;

  try {
    // 1. List devices
    logSection('1. Available Devices');
    const devices = PvRecorder.getAvailableDevices();
    devices.forEach((d, i) => log(`  [${i}] ${d}`, colors.green));

    if (devices.length === 0) {
      log('No devices!', colors.red);
      process.exit(1);
    }

    // 2. Initialize PvRecorder
    logSection('2. Initialize PvRecorder');
    const recorder = new PvRecorder(512); // 512 samples per frame
    log(`  Sample rate: ${recorder.sampleRate} Hz`, colors.green);
    log(`  Frame length: 512 samples`, colors.green);

    // 3. Calculate Opus frame size
    // For 16kHz: 60ms = 960 samples, 40ms = 640 samples, 20ms = 320 samples
    // We need to accumulate multiple PvRecorder frames (512) to reach a valid Opus frame size
    // LCM of 512 and valid frame sizes (320, 640, 960) is 9600 samples
    // 9600 samples / 512 samples per frame = 18.75 frames... let's use 960 samples (60ms)
    // 960 / 512 = 1.875 frames... this doesn't work evenly

    // Let's use a simpler approach: use frame sizes that work with 512
    // 512 samples at 16kHz = 32ms
    // We can encode at 48kHz instead and use 960 samples (20ms)
    // Or accumulate: 512 * 15 = 7680 samples = 480ms at 16kHz (not ideal)

    // Alternative: use 48kHz and frame size of 960 (20ms)
    // PvRecorder works at 16kHz, so we'd need to resample

    // For this POC, let's just use 48kHz with the standard 960 frame size
    // and we'll generate synthetic PCM to test encoding
    logSection('3. Testing Opus Encoding (Synthetic)');

    const SAMPLE_RATE = 48000;
    const FRAME_SIZE = 960; // 20ms at 48kHz
    const encoder = new OpusEncoder(SAMPLE_RATE, OPUS_CHANNELS);
    log(`  Encoder: ${SAMPLE_RATE} Hz, ${FRAME_SIZE} samples/frame`, colors.green);

    // Generate synthetic PCM (sine wave at 440Hz)
    const pcmSamples = new Int16Array(FRAME_SIZE);
    for (let i = 0; i < FRAME_SIZE; i++) {
      const t = i / SAMPLE_RATE;
      pcmSamples[i] = Math.sin(2 * Math.PI * 440 * t) * 16384; // Half scale
    }

    // Encode to Opus
    logSection('4. Encode Test');
    const pcmBuffer = Buffer.from(pcmSamples.buffer);
    const opusPacket = encoder.encode(pcmBuffer);
    log(`  PCM: ${pcmBuffer.length} bytes`, colors.green);
    log(`  Opus: ${opusPacket.length} bytes`, colors.green);
    log(`  Compression: ${(opusPacket.length / pcmBuffer.length * 100).toFixed(1)}%`, colors.green);

    // Decode test
    logSection('5. Decode Test');
    const decodedPcm = encoder.decode(opusPacket);
    log(`  Decoded: ${decodedPcm.length} bytes`, colors.green);

    // Save output
    logSection('6. Save Output');
    const timestamp = Date.now();
    const opusPath = join(tmpdir(), `poc-opus-${timestamp}.bin`);
    await writeFile(opusPath, opusPacket);
    log(`  Saved: ${opusPath}`, colors.green);

    logSection('Complete!');
    log('  Summary:', colors.magenta);
    log(`    - PvRecorder: Available`, colors.green);
    log(`    - @discordjs/opus: Encoding/decoding works`, colors.green);
    log(`    - Note: PvRecorder (16kHz) + Opus (48kHz) needs resampling`, colors.yellow);

    recorder.release();

  } catch (error) {
    log('', colors.reset);
    log(`Error: ${error}`, colors.red);
    if (error instanceof Error) {
      log(`  ${error.stack}`, colors.red);
    }
    process.exit(1);
  }
}

main().catch((error) => {
  console.error('Fatal error:', error);
  process.exit(1);
});
