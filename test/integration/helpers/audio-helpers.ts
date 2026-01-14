/**
 * Audio test helpers
 *
 * Utilities for creating fake audio data for testing voice messages.
 */

import { Buffer } from 'buffer';

/**
 * Create a fake audio buffer for testing
 *
 * This creates a buffer with fake audio data. It's not a valid audio file,
 * but Matrix will accept it as binary content and serve it back.
 *
 * For more realistic testing, you could use a library to generate actual
 * audio files (e.g., using ffmpeg or a pure JS audio generator).
 */
export function createFakeAudioBuffer(
  durationMs: number,
  options: {
    prefix?: string;
    includeMetadata?: boolean;
  } = {},
): Buffer {
  const prefix = options.prefix || 'FAKE_AUDIO';
  const includeMetadata = options.includeMetadata ?? true;

  // Create a buffer with some identifiable content
  let content = `${prefix}_${durationMs}ms`;

  if (includeMetadata) {
    content += `_timestamp_${Date.now()}`;
  }

  // Pad to simulate file size (rough approximation: 16kbps AAC)
  const bytesPerMs = 16000 / 8 / 1000; // 16 kbps = 2 bytes/ms
  const targetSize = Math.floor(durationMs * bytesPerMs);
  const padding = 'x'.repeat(Math.max(0, targetSize - content.length));

  return Buffer.from(content + padding);
}

/**
 * Create multiple unique audio buffers
 */
export function createAudioBuffers(
  count: number,
  durationMs = 5000,
): Buffer[] {
  return Array.from({ length: count }, (_, i) =>
    createFakeAudioBuffer(durationMs, {
      prefix: `AUDIO_${i + 1}`,
    }),
  );
}

/**
 * Create audio buffer with specific content for identification
 */
export function createIdentifiableAudioBuffer(
  id: string,
  durationMs = 5000,
): Buffer {
  return createFakeAudioBuffer(durationMs, {
    prefix: `AUDIO_ID_${id}`,
  });
}

/**
 * Helper to simulate various audio durations
 */
export const AudioDurations = {
  SHORT: 1000, // 1 second
  NORMAL: 5000, // 5 seconds
  MEDIUM: 15000, // 15 seconds
  LONG: 60000, // 60 seconds
  VERY_LONG: 180000, // 3 minutes
};

/**
 * Create a set of audio buffers with different durations for testing
 */
export function createVariedDurationBuffers(): {
  short: Buffer;
  normal: Buffer;
  medium: Buffer;
  long: Buffer;
} {
  return {
    short: createFakeAudioBuffer(AudioDurations.SHORT, { prefix: 'SHORT' }),
    normal: createFakeAudioBuffer(AudioDurations.NORMAL, { prefix: 'NORMAL' }),
    medium: createFakeAudioBuffer(AudioDurations.MEDIUM, { prefix: 'MEDIUM' }),
    long: createFakeAudioBuffer(AudioDurations.LONG, { prefix: 'LONG' }),
  };
}
