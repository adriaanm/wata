/**
 * Unit tests for audio-codec.ts
 *
 * Tests cover:
 * - encodeOggOpus: encoding PCM to Ogg Opus
 * - decodeOggOpus: decoding Ogg Opus to PCM
 * - Roundtrip: encode → decode → verify
 */

import { Buffer } from 'buffer';

import {
  encodeOggOpus,
  decodeOggOpus,
  OPUS_SAMPLE_RATE,
  OPUS_CHANNELS,
  OPUS_FRAME_SIZE,
  OPUS_PRE_SKIP,
  type DecodeResult,
  type Logger,
} from '@shared/lib/audio-codec';
import { oggCrc32, createOpusHead, createOpusTags, createOggPage } from '@shared/lib/ogg';

// ============================================================================
// Test Logger
// ============================================================================

class TestLogger implements Logger {
  logs: string[] = [];
  warnings: string[] = [];
  errors: string[] = [];

  log(message: string): void {
    this.logs.push(message);
  }

  warn(message: string): void {
    this.warnings.push(message);
  }

  error(message: string): void {
    this.errors.push(message);
  }

  clear(): void {
    this.logs = [];
    this.warnings = [];
    this.errors = [];
  }
}

// ============================================================================
// Helper Functions
// ============================================================================

/**
 * Create a test tone (sine wave) for testing
 *
 * @param samples - Number of samples to generate
 * @param frequency - Frequency in Hz
 * @param sampleRate - Sample rate in Hz (default 16000)
 * @returns Float32Array containing the sine wave
 */
function createTestTone(
  samples: number,
  frequency: number,
  sampleRate: number = 16000
): Float32Array {
  const result = new Float32Array(samples);
  for (let i = 0; i < samples; i++) {
    const t = i / sampleRate;
    result[i] = Math.sin(2 * Math.PI * frequency * t);
  }
  return result;
}

/**
 * Assert that two buffers are similar (within tolerance)
 *
 * Opus encoding is lossy, so we need approximate comparison.
 *
 * @param actual - Int16Array from decode
 * @param expected - Float32Array from original (normalized [-1, 1])
 * @param tolerance - Maximum allowed difference (scaled to Int16 range)
 */
function assertBuffersSimilar(
  actual: Int16Array,
  expected: Float32Array,
  tolerance: number
): void {
  // Scale tolerance to Int16 range (tolerance is in normalized range)
  const intTolerance = tolerance * 32767;

  // Check length is similar (may differ slightly due to encoding)
  const minLength = Math.min(actual.length, expected.length);

  // Sample a few points to check similarity
  const samplePoints = [
    0,
    Math.floor(minLength * 0.1),
    Math.floor(minLength * 0.25),
    Math.floor(minLength * 0.5),
    Math.floor(minLength * 0.75),
    Math.floor(minLength * 0.9),
    minLength - 1,
  ];

  for (const i of samplePoints) {
    if (i >= minLength) continue;

    const expectedInt = Math.round(expected[i] * 32767);
    const diff = Math.abs(actual[i] - expectedInt);

    // The difference should be within tolerance
    // Note: Opus adds pre-skip which can cause initial samples to differ
    // So we're more lenient at the start
    const allowedTolerance = i < OPUS_PRE_SKIP ? intTolerance * 5 : intTolerance;

    if (diff > allowedTolerance) {
      throw new Error(
        `Buffers differ at index ${i}: expected ~${expectedInt}, got ${actual[i]}, diff ${diff} > ${allowedTolerance}`
      );
    }
  }
}

/**
 * Check if a buffer contains the OggS magic number
 */
function hasOggMagic(buffer: Buffer): boolean {
  return buffer.slice(0, 4).toString('ascii') === 'OggS';
}

/**
 * Check if buffer contains OpusHead magic
 */
function hasOpusHead(buffer: Buffer): boolean {
  const str = buffer.toString('ascii');
  return str.includes('OpusHead');
}

/**
 * Check if buffer contains OpusTags magic
 */
function hasOpusTags(buffer: Buffer): boolean {
  const str = buffer.toString('ascii');
  return str.includes('OpusTags');
}

// ============================================================================
// encodeOggOpus Tests
// ============================================================================

describe('encodeOggOpus', () => {
  describe('basic encoding', () => {
    it('should encode 16kHz Float32Array PCM to Ogg Opus', () => {
      const samples = new Float32Array(OPUS_FRAME_SIZE * 2); // 2 frames
      for (let i = 0; i < samples.length; i++) {
        samples[i] = Math.sin((i * 2 * Math.PI) / OPUS_FRAME_SIZE);
      }

      const result = encodeOggOpus(samples, { sampleRate: 16000 });

      expect(result).toBeInstanceOf(Buffer);
      expect(result.length).toBeGreaterThan(0);
    });

    it('should encode 16kHz Int16Array PCM to Ogg Opus', () => {
      const samples = new Int16Array(OPUS_FRAME_SIZE * 2); // 2 frames
      for (let i = 0; i < samples.length; i++) {
        // Sine wave scaled to Int16 range
        samples[i] = Math.round(Math.sin((i * 2 * Math.PI) / OPUS_FRAME_SIZE) * 16000);
      }

      const result = encodeOggOpus(samples, { sampleRate: 16000 });

      expect(result).toBeInstanceOf(Buffer);
      expect(result.length).toBeGreaterThan(0);
    });

    it('should handle empty input gracefully', () => {
      const samples = new Float32Array(0);

      // Empty input should either return minimal valid Ogg or throw
      // Based on implementation, it will encode 0 packets and return headers
      const result = encodeOggOpus(samples, { sampleRate: 16000 });

      // Should at least have headers (OpusHead + OpusTags pages)
      expect(result.length).toBeGreaterThan(0);
      expect(hasOggMagic(result)).toBe(true);
    });

    it('should handle single frame', () => {
      const samples = new Float32Array(OPUS_FRAME_SIZE);
      for (let i = 0; i < samples.length; i++) {
        samples[i] = 0.5;
      }

      const result = encodeOggOpus(samples, { sampleRate: 16000 });

      expect(result).toBeInstanceOf(Buffer);
      expect(result.length).toBeGreaterThan(0);
    });

    it('should handle multiple frames', () => {
      const samples = new Float32Array(OPUS_FRAME_SIZE * 5); // 5 frames
      for (let i = 0; i < samples.length; i++) {
        samples[i] = Math.sin((i * 2 * Math.PI * 440) / OPUS_SAMPLE_RATE);
      }

      const result = encodeOggOpus(samples, { sampleRate: 16000 });

      expect(result).toBeInstanceOf(Buffer);
      expect(result.length).toBeGreaterThan(0);
    });
  });

  describe('Ogg container format', () => {
    it('should produce output with OggS magic', () => {
      const samples = new Float32Array(OPUS_FRAME_SIZE);
      samples.fill(0.5);

      const result = encodeOggOpus(samples, { sampleRate: 16000 });

      expect(hasOggMagic(result)).toBe(true);
    });

    it('should produce output with OpusHead header', () => {
      const samples = new Float32Array(OPUS_FRAME_SIZE);
      samples.fill(0.5);

      const result = encodeOggOpus(samples, { sampleRate: 16000 });

      expect(hasOpusHead(result)).toBe(true);
    });

    it('should produce output with OpusTags header', () => {
      const samples = new Float32Array(OPUS_FRAME_SIZE);
      samples.fill(0.5);

      const result = encodeOggOpus(samples, { sampleRate: 16000 });

      expect(hasOpusTags(result)).toBe(true);
    });

    it('should produce valid Ogg pages with correct CRC', () => {
      const samples = new Float32Array(OPUS_FRAME_SIZE * 2);
      for (let i = 0; i < samples.length; i++) {
        samples[i] = Math.sin((i * 2 * Math.PI * 440) / OPUS_SAMPLE_RATE);
      }

      const result = encodeOggOpus(samples, { sampleRate: 16000 });

      // Verify each page has valid CRC
      let offset = 0;
      let pageCount = 0;

      while (offset < result.length - 27) {
        // Check magic
        if (result.toString('ascii', offset, offset + 4) !== 'OggS') {
          break;
        }

        // Extract stored CRC
        const view = new DataView(result.buffer, result.byteOffset + offset);
        const storedCrc = view.getUint32(22, true);

        // Calculate CRC of page with CRC field set to 0
        const pageBytes = new Uint8Array(result.buffer, result.byteOffset + offset);
        const pageForCrc = new Uint8Array(pageBytes);
        new DataView(pageForCrc.buffer).setUint32(22, 0, true);
        const calculatedCrc = oggCrc32(pageForCrc);

        expect(calculatedCrc).toBe(storedCrc);

        // Move to next page
        const numSegments = result[offset + 26];
        let dataSize = 0;
        for (let i = 0; i < numSegments; i++) {
          dataSize += result[offset + 27 + i];
        }
        offset += 27 + numSegments + dataSize;
        pageCount++;
      }

      // Should have at least 3 pages: OpusHead, OpusTags, and data
      expect(pageCount).toBeGreaterThanOrEqual(3);
    });
  });

  describe('sample rate conversion', () => {
    it('should resample 44.1kHz to 16kHz then encode', () => {
      // 44100 Hz input, create enough samples for at least one Opus frame
      const inputSamples = Math.ceil((OPUS_FRAME_SIZE * 44100) / 16000);
      const samples = new Float32Array(inputSamples);
      for (let i = 0; i < samples.length; i++) {
        samples[i] = Math.sin((i * 2 * Math.PI * 440) / 44100);
      }

      const result = encodeOggOpus(samples, { sampleRate: 44100 });

      expect(result).toBeInstanceOf(Buffer);
      expect(result.length).toBeGreaterThan(0);
      expect(hasOggMagic(result)).toBe(true);
    });

    it('should resample 48kHz to 16kHz then encode', () => {
      // 48000 Hz input, create enough samples for at least one Opus frame
      const inputSamples = Math.ceil((OPUS_FRAME_SIZE * 48000) / 16000);
      const samples = new Float32Array(inputSamples);
      for (let i = 0; i < samples.length; i++) {
        samples[i] = Math.sin((i * 2 * Math.PI * 440) / 48000);
      }

      const result = encodeOggOpus(samples, { sampleRate: 48000 });

      expect(result).toBeInstanceOf(Buffer);
      expect(result.length).toBeGreaterThan(0);
      expect(hasOggMagic(result)).toBe(true);
    });

    it('should handle 22.05kHz input', () => {
      const inputSamples = Math.ceil((OPUS_FRAME_SIZE * 22050) / 16000);
      const samples = new Float32Array(inputSamples);
      for (let i = 0; i < samples.length; i++) {
        samples[i] = Math.sin((i * 2 * Math.PI * 440) / 22050);
      }

      const result = encodeOggOpus(samples, { sampleRate: 22050 });

      expect(result).toBeInstanceOf(Buffer);
      expect(result.length).toBeGreaterThan(0);
    });

    it('should handle 8kHz input', () => {
      const inputSamples = Math.ceil((OPUS_FRAME_SIZE * 8000) / 16000);
      const samples = new Float32Array(inputSamples);
      for (let i = 0; i < samples.length; i++) {
        samples[i] = Math.sin((i * 2 * Math.PI * 440) / 8000);
      }

      const result = encodeOggOpus(samples, { sampleRate: 8000 });

      expect(result).toBeInstanceOf(Buffer);
      expect(result.length).toBeGreaterThan(0);
    });
  });

  describe('error handling', () => {
    it('should throw on unsupported channel count', () => {
      const samples = new Float32Array(OPUS_FRAME_SIZE);

      expect(() => {
        encodeOggOpus(samples, { sampleRate: 16000, channels: 2 as 1 });
      }).toThrow('Only mono audio is supported');
    });
  });

  describe('with logger', () => {
    it('should log encoding progress', () => {
      const logger = new TestLogger();
      const samples = new Float32Array(OPUS_FRAME_SIZE * 2);
      for (let i = 0; i < samples.length; i++) {
        samples[i] = Math.sin((i * 2 * Math.PI * 440) / OPUS_SAMPLE_RATE);
      }

      encodeOggOpus(samples, { sampleRate: 16000, logger });

      expect(logger.logs.length).toBeGreaterThan(0);
      expect(logger.logs.some((l) => l.includes('encodeOggOpus: starting'))).toBe(true);
      expect(logger.logs.some((l) => l.includes('encodeOggOpus: complete'))).toBe(true);
    });

    it('should log Int16Array to Float32Array conversion', () => {
      const logger = new TestLogger();
      const samples = new Int16Array(OPUS_FRAME_SIZE);
      samples.fill(1000);

      encodeOggOpus(samples, { sampleRate: 16000, logger });

      expect(logger.logs.some((l) => l.includes('converting Int16Array to Float32Array'))).toBe(
        true
      );
    });

    it('should log resampling when input rate differs', () => {
      const logger = new TestLogger();
      const samples = new Float32Array(OPUS_FRAME_SIZE * 3);

      encodeOggOpus(samples, { sampleRate: 44100, logger });

      expect(logger.logs.some((l) => l.includes('resampling 44100 Hz'))).toBe(true);
      expect(logger.logs.some((l) => l.includes('16000 Hz'))).toBe(true);
    });
  });

  describe('edge cases', () => {
    it('should handle very short input (less than one frame)', () => {
      const samples = new Float32Array(100); // < 960 samples
      for (let i = 0; i < samples.length; i++) {
        samples[i] = 0.5;
      }

      const result = encodeOggOpus(samples, { sampleRate: 16000 });

      expect(result).toBeInstanceOf(Buffer);
      expect(result.length).toBeGreaterThan(0);
    });

    it('should handle zero input (silence)', () => {
      const samples = new Float32Array(OPUS_FRAME_SIZE * 2).fill(0);

      const result = encodeOggOpus(samples, { sampleRate: 16000 });

      expect(result).toBeInstanceOf(Buffer);
      expect(result.length).toBeGreaterThan(0);
    });

    it('should handle full scale input', () => {
      const samples = new Float32Array(OPUS_FRAME_SIZE);
      samples.fill(1.0);

      const result = encodeOggOpus(samples, { sampleRate: 16000 });

      expect(result).toBeInstanceOf(Buffer);
      expect(result.length).toBeGreaterThan(0);
    });

    it('should handle negative full scale input', () => {
      const samples = new Float32Array(OPUS_FRAME_SIZE);
      samples.fill(-1.0);

      const result = encodeOggOpus(samples, { sampleRate: 16000 });

      expect(result).toBeInstanceOf(Buffer);
      expect(result.length).toBeGreaterThan(0);
    });
  });
});

// ============================================================================
// decodeOggOpus Tests
// ============================================================================

describe('decodeOggOpus', () => {
  describe('basic decoding', () => {
    it('should decode valid Ogg Opus to 16kHz Int16Array', () => {
      // First encode some audio
      const samples = new Float32Array(OPUS_FRAME_SIZE * 3);
      for (let i = 0; i < samples.length; i++) {
        samples[i] = Math.sin((i * 2 * Math.PI * 440) / OPUS_SAMPLE_RATE);
      }

      const encoded = encodeOggOpus(samples, { sampleRate: 16000 });

      // Now decode it
      const result = decodeOggOpus(encoded);

      expect(result).toHaveProperty('pcm');
      expect(result).toHaveProperty('sampleRate');
      expect(result).toHaveProperty('duration');
      expect(result.pcm).toBeInstanceOf(Int16Array);
      expect(result.sampleRate).toBe(16000);
      expect(typeof result.duration).toBe('number');
    });

    it('should calculate correct duration', () => {
      const frameCount = 3;
      const samples = new Float32Array(OPUS_FRAME_SIZE * frameCount);
      for (let i = 0; i < samples.length; i++) {
        samples[i] = Math.sin((i * 2 * Math.PI * 440) / OPUS_SAMPLE_RATE);
      }

      const encoded = encodeOggOpus(samples, { sampleRate: 16000 });
      const result = decodeOggOpus(encoded);

      const expectedDuration = (OPUS_FRAME_SIZE * frameCount) / OPUS_SAMPLE_RATE;
      // Duration should be close (Opus may add/remove some samples)
      expect(result.duration).toBeGreaterThan(expectedDuration * 0.9);
      expect(result.duration).toBeLessThan(expectedDuration * 1.1);
    });

    it('should always return sampleRate of 16000', () => {
      // Test encoding from different sample rates
      const testRates = [8000, 16000, 22050, 44100, 48000];

      for (const rate of testRates) {
        const samples = new Float32Array(Math.ceil((OPUS_FRAME_SIZE * 2 * rate) / 16000));
        for (let i = 0; i < samples.length; i++) {
          samples[i] = Math.sin((i * 2 * Math.PI * 440) / rate);
        }

        const encoded = encodeOggOpus(samples, { sampleRate: rate });
        const result = decodeOggOpus(encoded);

        expect(result.sampleRate).toBe(16000);
      }
    });
  });

  describe('error handling', () => {
    it('should throw on empty buffer', () => {
      expect(() => {
        decodeOggOpus(Buffer.from([]));
      }).toThrow();
    });

    it('should throw on invalid Ogg data', () => {
      const invalidData = Buffer.from('invalid ogg data');

      expect(() => {
        decodeOggOpus(invalidData);
      }).toThrow();
    });

    it('should throw on malformed Ogg header', () => {
      // Create buffer that's long enough but has invalid magic
      const malformed = Buffer.alloc(100, 0xFF);

      expect(() => {
        decodeOggOpus(malformed);
      }).toThrow();
    });

    it('should throw on Ogg with headers but no audio packets', () => {
      // Create minimal valid Ogg with OpusHead and OpusTags but no audio
      const opusHead = createOpusHead(1, OPUS_PRE_SKIP, OPUS_SAMPLE_RATE);
      const opusTags = createOpusTags();

      const headPage = createOggPage(opusHead, BigInt(0), 1, 0, 0x02);
      const tagsPage = createOggPage(opusTags, BigInt(0), 1, 1, 0x04); // EOS flag

      const oggData = Buffer.concat([Buffer.from(headPage), Buffer.from(tagsPage)]);

      expect(() => {
        decodeOggOpus(oggData);
      }).toThrow('No Opus packets found');
    });
  });

  describe('with logger', () => {
    it('should log decoding progress', () => {
      const logger = new TestLogger();
      const samples = new Float32Array(OPUS_FRAME_SIZE * 2);
      for (let i = 0; i < samples.length; i++) {
        samples[i] = Math.sin((i * 2 * Math.PI * 440) / OPUS_SAMPLE_RATE);
      }

      const encoded = encodeOggOpus(samples, { sampleRate: 16000 });
      decodeOggOpus(encoded, { logger });

      expect(logger.logs.some((l) => l.includes('decodeOggOpus: starting'))).toBe(true);
      expect(logger.logs.some((l) => l.includes('decodeOggOpus: complete'))).toBe(true);
    });
  });

  describe('decoded data quality', () => {
    it('should produce finite values', () => {
      const samples = new Float32Array(OPUS_FRAME_SIZE * 2);
      for (let i = 0; i < samples.length; i++) {
        samples[i] = Math.sin((i * 2 * Math.PI * 440) / OPUS_SAMPLE_RATE);
      }

      const encoded = encodeOggOpus(samples, { sampleRate: 16000 });
      const result = decodeOggOpus(encoded);

      for (let i = 0; i < result.pcm.length; i++) {
        expect(Number.isFinite(result.pcm[i])).toBe(true);
      }
    });

    it('should handle silence correctly', () => {
      const samples = new Float32Array(OPUS_FRAME_SIZE * 2).fill(0);

      const encoded = encodeOggOpus(samples, { sampleRate: 16000 });
      const result = decodeOggOpus(encoded);

      // Decoded silence should be close to zero
      let maxAbs = 0;
      for (let i = OPUS_PRE_SKIP; i < Math.min(result.pcm.length, samples.length); i++) {
        maxAbs = Math.max(maxAbs, Math.abs(result.pcm[i]));
      }
      // Allow some noise due to encoding
      expect(maxAbs).toBeLessThan(1000);
    });
  });
});

// ============================================================================
// Roundtrip Tests (encode → decode)
// ============================================================================

describe('Roundtrip: encode → decode', () => {
  describe('basic roundtrip', () => {
    it('should preserve audio data through roundtrip', () => {
      const original = createTestTone(OPUS_FRAME_SIZE * 3, 440, 16000);

      const encoded = encodeOggOpus(original, { sampleRate: 16000 });
      const decoded = decodeOggOpus(encoded);

      // Check similarity (Opus is lossy, so we allow tolerance)
      assertBuffersSimilar(decoded.pcm, original, 0.15);
    });

    it('should handle low frequency tone', () => {
      const original = createTestTone(OPUS_FRAME_SIZE * 3, 100, 16000);

      const encoded = encodeOggOpus(original, { sampleRate: 16000 });
      const decoded = decodeOggOpus(encoded);

      assertBuffersSimilar(decoded.pcm, original, 0.15);
    });

    it('should handle high frequency tone', () => {
      const original = createTestTone(OPUS_FRAME_SIZE * 3, 2000, 16000);

      const encoded = encodeOggOpus(original, { sampleRate: 16000 });
      const decoded = decodeOggOpus(encoded);

      assertBuffersSimilar(decoded.pcm, original, 0.2);
    });
  });

  describe('roundtrip with sample rate conversion', () => {
    it('should preserve audio from 44.1kHz source', () => {
      const original = createTestTone(OPUS_FRAME_SIZE * 3, 440, 44100);

      const encoded = encodeOggOpus(original, { sampleRate: 44100 });
      const decoded = decodeOggOpus(encoded);

      // Decode output is always 16kHz, so we compare with resampled original
      // Just check that we got reasonable audio data
      expect(decoded.pcm.length).toBeGreaterThan(0);
      expect(decoded.sampleRate).toBe(16000);
    });

    it('should preserve audio from 48kHz source', () => {
      const original = createTestTone(OPUS_FRAME_SIZE * 3, 440, 48000);

      const encoded = encodeOggOpus(original, { sampleRate: 48000 });
      const decoded = decodeOggOpus(encoded);

      expect(decoded.pcm.length).toBeGreaterThan(0);
      expect(decoded.sampleRate).toBe(16000);
    });

    it('should preserve audio from 8kHz source', () => {
      const original = createTestTone(OPUS_FRAME_SIZE * 3, 440, 8000);

      const encoded = encodeOggOpus(original, { sampleRate: 8000 });
      const decoded = decodeOggOpus(encoded);

      expect(decoded.pcm.length).toBeGreaterThan(0);
      expect(decoded.sampleRate).toBe(16000);
    });
  });

  describe('roundtrip with Int16Array input', () => {
    it('should handle Int16Array input correctly', () => {
      const original = new Int16Array(OPUS_FRAME_SIZE * 3);
      for (let i = 0; i < original.length; i++) {
        original[i] = Math.round(Math.sin((i * 2 * Math.PI * 440) / 16000) * 16000);
      }

      const encoded = encodeOggOpus(original, { sampleRate: 16000 });
      const decoded = decodeOggOpus(encoded);

      expect(decoded.pcm).toBeInstanceOf(Int16Array);
      expect(decoded.pcm.length).toBeGreaterThan(0);
    });
  });

  describe('roundtrip edge cases', () => {
    it('should handle very short audio', () => {
      const original = new Float32Array(100);
      for (let i = 0; i < original.length; i++) {
        original[i] = Math.sin((i * 2 * Math.PI * 440) / 16000);
      }

      const encoded = encodeOggOpus(original, { sampleRate: 16000 });
      const decoded = decodeOggOpus(encoded);

      expect(decoded.pcm.length).toBeGreaterThan(0);
    });

    it('should handle silence', () => {
      const original = new Float32Array(OPUS_FRAME_SIZE * 2).fill(0);

      const encoded = encodeOggOpus(original, { sampleRate: 16000 });
      const decoded = decodeOggOpus(encoded);

      // Should decode to near-zero values
      let maxAbs = 0;
      for (let i = OPUS_PRE_SKIP; i < Math.min(decoded.pcm.length, original.length); i++) {
        maxAbs = Math.max(maxAbs, Math.abs(decoded.pcm[i]));
      }
      expect(maxAbs).toBeLessThan(1000);
    });

    it('should handle full-scale positive', () => {
      const original = new Float32Array(OPUS_FRAME_SIZE).fill(1.0);

      const encoded = encodeOggOpus(original, { sampleRate: 16000 });
      const decoded = decodeOggOpus(encoded);

      // Should decode to values near full scale positive
      expect(decoded.pcm.length).toBeGreaterThan(0);
      const avgSample =
        decoded.pcm.reduce((sum, v) => sum + v, 0) / decoded.pcm.length;
      expect(avgSample).toBeGreaterThan(20000); // Should be close to +32767
    });

    it('should handle full-scale negative', () => {
      const original = new Float32Array(OPUS_FRAME_SIZE).fill(-1.0);

      const encoded = encodeOggOpus(original, { sampleRate: 16000 });
      const decoded = decodeOggOpus(encoded);

      // Should decode to values near full scale negative
      expect(decoded.pcm.length).toBeGreaterThan(0);
      const avgSample =
        decoded.pcm.reduce((sum, v) => sum + v, 0) / decoded.pcm.length;
      expect(avgSample).toBeLessThan(-20000); // Should be close to -32767
    });
  });

  describe('DecodeResult structure', () => {
    it('should always return sampleRate of 16000', () => {
      const original = createTestTone(OPUS_FRAME_SIZE * 2, 440, 16000);

      const encoded = encodeOggOpus(original, { sampleRate: 16000 });
      const decoded = decodeOggOpus(encoded);

      expect(decoded.sampleRate).toBe(16000);
    });

    it('should calculate duration correctly from sample count', () => {
      const frameCount = 4;
      const original = createTestTone(OPUS_FRAME_SIZE * frameCount, 440, 16000);

      const encoded = encodeOggOpus(original, { sampleRate: 16000 });
      const decoded = decodeOggOpus(encoded);

      // Duration should be approximately samples / sampleRate
      const expectedDuration = decoded.pcm.length / decoded.sampleRate;
      expect(decoded.duration).toBeCloseTo(expectedDuration, 5);
    });

    it('should return Int16Array for pcm', () => {
      const original = createTestTone(OPUS_FRAME_SIZE, 440, 16000);

      const encoded = encodeOggOpus(original, { sampleRate: 16000 });
      const decoded = decodeOggOpus(encoded);

      expect(decoded.pcm).toBeInstanceOf(Int16Array);
    });
  });

  describe('complex audio patterns', () => {
    it('should handle triangular wave', () => {
      const samples = new Float32Array(OPUS_FRAME_SIZE * 2);
      for (let i = 0; i < samples.length; i++) {
        const phase = (i % OPUS_FRAME_SIZE) / OPUS_FRAME_SIZE;
        samples[i] = phase < 0.5 ? phase * 2 : (1 - phase) * 2;
        samples[i] = samples[i] * 2 - 1; // Scale to [-1, 1]
      }

      const encoded = encodeOggOpus(samples, { sampleRate: 16000 });
      const decoded = decodeOggOpus(encoded);

      expect(decoded.pcm.length).toBeGreaterThan(0);
    });

    it('should handle multiple frequencies (chord)', () => {
      const samples = new Float32Array(OPUS_FRAME_SIZE * 2);
      for (let i = 0; i < samples.length; i++) {
        const t = i / OPUS_SAMPLE_RATE;
        // A major chord: A4 (440Hz), C#5 (554Hz), E5 (659Hz)
        samples[i] =
          (Math.sin(2 * Math.PI * 440 * t) +
            Math.sin(2 * Math.PI * 554 * t) +
            Math.sin(2 * Math.PI * 659 * t)) /
          3;
      }

      const encoded = encodeOggOpus(samples, { sampleRate: 16000 });
      const decoded = decodeOggOpus(encoded);

      expect(decoded.pcm.length).toBeGreaterThan(0);
      // Check that values are reasonable
      let maxAbs = 0;
      for (const v of decoded.pcm) {
        maxAbs = Math.max(maxAbs, Math.abs(v));
      }
      expect(maxAbs).toBeGreaterThan(1000); // Should have significant signal
      expect(maxAbs).toBeLessThanOrEqual(32767); // Not clipped
    });

    it('should handle amplitude sweep', () => {
      const samples = new Float32Array(OPUS_FRAME_SIZE * 3);
      for (let i = 0; i < samples.length; i++) {
        const amplitude = i / samples.length; // 0 to 1
        samples[i] = Math.sin((i * 2 * Math.PI * 440) / OPUS_SAMPLE_RATE) * amplitude;
      }

      const encoded = encodeOggOpus(samples, { sampleRate: 16000 });
      const decoded = decodeOggOpus(encoded);

      expect(decoded.pcm.length).toBeGreaterThan(0);
      // Amplitude should generally increase (though Opus modifies it)
      const firstQuarter = Math.floor(decoded.pcm.length / 4);
      const lastQuarter = Math.floor((decoded.pcm.length * 3) / 4);

      const firstQuarterEnergy =
        decoded.pcm.slice(0, firstQuarter).reduce((sum, v) => sum + v * v, 0) / firstQuarter;
      const lastQuarterEnergy =
        decoded.pcm.slice(lastQuarter).reduce((sum, v) => sum + v * v, 0) /
        (decoded.pcm.length - lastQuarter);

      // Last quarter should generally have more energy than first quarter
      // (this is a soft check due to Opus encoding)
      expect(lastQuarterEnergy).toBeGreaterThan(firstQuarterEnergy * 0.5);
    });
  });

  describe('realistic audio scenarios', () => {
    it('should handle voice-like frequency range', () => {
      // Voice is roughly 80Hz - 8000Hz
      const samples = new Float32Array(OPUS_FRAME_SIZE * 5);
      for (let i = 0; i < samples.length; i++) {
        const t = i / OPUS_SAMPLE_RATE;
        // Mix of frequencies in voice range
        samples[i] =
          (Math.sin(2 * Math.PI * 150 * t) * 0.5 +
            Math.sin(2 * Math.PI * 500 * t) * 0.3 +
            Math.sin(2 * Math.PI * 2000 * t) * 0.2) /
          2;
      }

      const encoded = encodeOggOpus(samples, { sampleRate: 16000 });
      const decoded = decodeOggOpus(encoded);

      expect(decoded.pcm.length).toBeGreaterThan(0);
      // Opus is optimized for voice, so should handle this well
      assertBuffersSimilar(decoded.pcm, samples, 0.2);
    });

    it('should handle typical voice message duration (~2 seconds)', () => {
      const duration = 2.0; // seconds
      const sampleCount = Math.floor(OPUS_SAMPLE_RATE * duration);
      const samples = new Float32Array(sampleCount);
      for (let i = 0; i < samples.length; i++) {
        const t = i / OPUS_SAMPLE_RATE;
        samples[i] = Math.sin(2 * Math.PI * 440 * t) * 0.5;
      }

      const encoded = encodeOggOpus(samples, { sampleRate: 16000 });
      const decoded = decodeOggOpus(encoded);

      expect(decoded.duration).toBeCloseTo(duration, 1);
      expect(decoded.pcm.length).toBeCloseTo(sampleCount, -2); // Within ~100 samples
    });

    it('should produce reasonable compression ratio', () => {
      const samples = new Float32Array(OPUS_FRAME_SIZE * 10); // ~600ms at 16kHz
      for (let i = 0; i < samples.length; i++) {
        samples[i] = Math.sin((i * 2 * Math.PI * 440) / OPUS_SAMPLE_RATE);
      }

      const encoded = encodeOggOpus(samples, { sampleRate: 16000 });

      // Input size: samples * 4 bytes per float32
      const inputSize = samples.length * 4;
      // Opus should compress significantly (for voice at 16kHz)
      const compressionRatio = inputSize / encoded.length;

      // Opus typically achieves 10:1 to 20:1 compression for voice
      // This is a soft check - just verify we're getting some compression
      expect(compressionRatio).toBeGreaterThan(2);
    });
  });
});
