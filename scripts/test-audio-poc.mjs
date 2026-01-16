#!/usr/bin/env node

/**
 * POC Test for PvRecorder + @evan/opus (16kHz)
 *
 * Records real audio from microphone and encodes to Opus at 16kHz.
 */

import { PvRecorder } from '@picovoice/pvrecorder-node';
import { Encoder, Decoder } from '@evan/opus/lib.mjs';
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

/**
 * Frame accumulator to handle PvRecorder → Opus frame size conversion
 */
class FrameAccumulator {
  constructor(targetSize) {
    this.buffer = new Int16Array(0);
    this.targetSize = targetSize;
  }

  add(samples) {
    const combined = new Int16Array(this.buffer.length + samples.length);
    combined.set(this.buffer);
    combined.set(samples, this.buffer.length);
    this.buffer = combined;

    const frames = [];
    while (this.buffer.length >= this.targetSize) {
      const frame = this.buffer.slice(0, this.targetSize);
      frames.push(frame);
      this.buffer = this.buffer.slice(this.targetSize);
    }

    return frames;
  }

  flush() {
    if (this.buffer.length === 0) return null;

    if (this.buffer.length < this.targetSize) {
      const padded = new Int16Array(this.targetSize);
      padded.set(this.buffer);
      this.buffer = padded;
    }

    const result = this.buffer;
    this.buffer = new Int16Array(0);
    return result;
  }

  get pendingSamples() {
    return this.buffer.length;
  }

  reset() {
    this.buffer = new Int16Array(0);
  }
}

async function main() {
  logSection('PvRecorder + @evan/opus POC (16kHz Voice)');

  const PV_FRAME_LENGTH = 512;
  const RECORDING_DURATION_MS = 5000;
  const SAMPLE_RATE = 16000; // Both PvRecorder and Opus at 16kHz!
  const OPUS_FRAME_SIZE = 960; // 60ms at 16kHz
  const CHANNELS = 1;

  try {
    // 1. List devices
    logSection('1. Available Audio Devices');
    const devices = PvRecorder.getAvailableDevices();
    devices.forEach((d, i) => log(`  [${i}] ${d}`, colors.green));

    // 2. Initialize PvRecorder
    logSection('2. Initialize PvRecorder');
    const recorder = new PvRecorder(PV_FRAME_LENGTH);
    log(`  Sample rate: ${recorder.sampleRate} Hz`, colors.green);
    log(`  Frame length: ${PV_FRAME_LENGTH} samples`, colors.green);

    // 3. Initialize Opus encoder/decoder
    logSection('3. Initialize Opus (@evan/opus)');
    const encoder = new Encoder(SAMPLE_RATE, CHANNELS);
    const decoder = new Decoder(SAMPLE_RATE, CHANNELS);
    const accumulator = new FrameAccumulator(OPUS_FRAME_SIZE);
    log(`  Encoder: ${SAMPLE_RATE} Hz, ${CHANNELS} channel`, colors.green);
    log(`  Frame size: ${OPUS_FRAME_SIZE} samples (60ms)`, colors.green);

    // 4. Start recording
    logSection('4. Recording Audio (5 seconds)');
    log('  Speak into your microphone...', colors.yellow);
    recorder.start();

    const opusFrames = [];
    const frameSizes = [];
    const startTime = Date.now();
    let frameCount = 0;
    let opusFrameCount = 0;

    while (Date.now() - startTime < RECORDING_DURATION_MS) {
      const pcmFrame = await recorder.read();
      frameCount++;

      const opusFramesReady = accumulator.add(pcmFrame);

      for (const frame of opusFramesReady) {
        const pcmBuffer = Buffer.from(frame.buffer);
        const opusPacket = encoder.encode(pcmBuffer);
        opusFrames.push(opusPacket);
        frameSizes.push(opusPacket.length);
        opusFrameCount++;
      }

      const elapsed = Date.now() - startTime;
      const progress = Math.min(100, (elapsed / RECORDING_DURATION_MS) * 100);
      const barWidth = 20;
      const filled = Math.floor((progress / 100) * barWidth);
      const empty = barWidth - filled;
      process.stdout.write(`\r  Progress: [${'#'.repeat(filled)}${'.'.repeat(empty)}] ${progress.toFixed(0)}%`);
    }

    process.stdout.write('\r');

    recorder.stop();
    log('  Stopped!', colors.green);

    // Flush remaining samples
    const finalFrame = accumulator.flush();
    if (finalFrame) {
      const pcmBuffer = Buffer.from(finalFrame.buffer);
      const opusPacket = encoder.encode(pcmBuffer);
      opusFrames.push(opusPacket);
      frameSizes.push(opusPacket.length);
      opusFrameCount++;
    }

    const duration = Date.now() - startTime;

    // 5. Statistics
    logSection('5. Recording Statistics');
    log(`  Duration: ${duration} ms`, colors.green);
    log(`  PvRecorder frames: ${frameCount}`, colors.green);
    log(`  Opus frames: ${opusFrameCount}`, colors.green);

    const totalOpusBytes = opusFrames.reduce((sum, frame) => sum + frame.length, 0);
    const avgFrameSize = frameSizes.reduce((sum, size) => sum + size, 0) / frameSizes.length;
    const totalPcmBytes = opusFrameCount * OPUS_FRAME_SIZE * 2;

    log(`  Total PCM: ${(totalPcmBytes / 1024).toFixed(2)} KB`, colors.green);
    log(`  Total Opus: ${(totalOpusBytes / 1024).toFixed(2)} KB`, colors.green);
    log(`  Compression: ${(totalOpusBytes / totalPcmBytes * 100).toFixed(1)}%`, colors.green);
    log(`  Avg Opus frame: ${avgFrameSize.toFixed(1)} bytes`, colors.green);

    // 6. Decode test
    logSection('6. Decode Test (Round-trip)');
    const testDecodeCount = Math.min(5, opusFrames.length);
    let decodeSuccess = 0;

    for (let i = 0; i < testDecodeCount; i++) {
      try {
        const decodedPcm = decoder.decode(opusFrames[i]);
        decodeSuccess++;
      } catch (error) {
        log(`  Frame ${i} decode failed: ${error.message}`, colors.red);
      }
    }

    log(`  Decoded ${decodeSuccess}/${testDecodeCount} test frames`, colors.green);

    // 7. Save output
    logSection('7. Save Output');
    const timestamp = Date.now();
    const opusPath = join(tmpdir(), `poc-opus-${timestamp}.bin`);
    const infoPath = join(tmpdir(), `poc-info-${timestamp}.json`);

    const opusBuffer = Buffer.concat(opusFrames);
    await writeFile(opusPath, opusBuffer);

    const metadata = {
      timestamp,
      duration,
      sampleRate: SAMPLE_RATE,
      channels: CHANNELS,
      pvFrames: frameCount,
      opusFrames: opusFrameCount,
      totalPcmBytes,
      totalOpusBytes,
      compressionRatio: (totalOpusBytes / totalPcmBytes * 100).toFixed(1) + '%',
      avgFrameSize: avgFrameSize.toFixed(1),
      opusPath,
    };

    await writeFile(infoPath, JSON.stringify(metadata, null, 2));

    log(`  Opus data: ${opusPath}`, colors.green);
    log(`  Metadata: ${infoPath}`, colors.green);

    // 8. Summary
    logSection('Complete!');
    log('  Summary:', colors.magenta);
    log(`    - PvRecorder: OK (${recorder.sampleRate} Hz)`, colors.green);
    log(`    - Frame accumulation: OK (512 -> 960 samples)`, colors.green);
    log(`    - @evan/opus: OK (16kHz encode/decode)`, colors.green);
    log(`    - Quality: 16kHz voice (60ms frames)`, colors.green);
    log('');
    log('  To play the Opus file:', colors.yellow);
    log(`    ffmpeg -f opus -ar ${SAMPLE_RATE} -ac ${CHANNELS} -i ${opusPath} -f wav - | ffplay -`, colors.blue);

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
