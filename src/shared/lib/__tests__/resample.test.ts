/**
 * Unit tests for resample.ts
 *
 * Tests audio resampling using linear interpolation.
 */

import { jest } from '@jest/globals';
import { resample } from '../resample';

describe('resample', () => {
  describe('basic functionality', () => {
    test('resample 44100 Hz -> 16000 Hz (downsampling)', () => {
      const inputSamples = new Float32Array(441); // 441 samples for easier math
      for (let i = 0; i < inputSamples.length; i++) {
        inputSamples[i] = i * 0.01; // Simple ramp signal
      }

      const output = resample(inputSamples, 44100, 16000);

      // Expected output length: ceil(441 * 16000 / 44100) = ceil(160) = 160
      expect(output).toBeInstanceOf(Float32Array);
      expect(output.length).toBe(160);

      // First sample should match (or be very close to) input first sample
      expect(output[0]).toBeCloseTo(inputSamples[0], 10);

      // Last sample should be within input range
      expect(output[output.length - 1]).toBeGreaterThanOrEqual(inputSamples[0]);
      expect(output[output.length - 1]).toBeLessThanOrEqual(inputSamples[inputSamples.length - 1]);
    });

    test('resample 48000 Hz -> 16000 Hz (3:1 downsampling)', () => {
      const inputSamples = new Float32Array(480);
      for (let i = 0; i < inputSamples.length; i++) {
        inputSamples[i] = Math.sin(i * 0.1); // Sine wave
      }

      const output = resample(inputSamples, 48000, 16000);

      // Expected output length: ceil(480 * 16000 / 48000) = ceil(160) = 160
      expect(output.length).toBe(160);

      // First sample should match input first sample
      expect(output[0]).toBe(inputSamples[0]);
    });

    test('resample 22050 Hz -> 16000 Hz (downsampling)', () => {
      const inputSamples = new Float32Array(441);
      for (let i = 0; i < inputSamples.length; i++) {
        inputSamples[i] = i * 0.01;
      }

      const output = resample(inputSamples, 22050, 16000);

      // Expected output length: ceil(441 * 16000 / 22050) = ceil(320) = 320
      expect(output.length).toBe(320);
    });

    test('same rate returns copy with same values', () => {
      const inputSamples = new Float32Array([0.1, 0.2, 0.3, 0.4, 0.5]);
      const output = resample(inputSamples, 44100, 44100);

      // Should return a copy, not the same reference
      expect(output).not.toBe(inputSamples);

      // But with the same values
      expect(output).toEqual(inputSamples);
      expect(output.length).toBe(inputSamples.length);
    });

    test('upsampling 16000 Hz -> 48000 Hz', () => {
      const inputSamples = new Float32Array([0, 0.5, 1, 0.5, 0]);
      const output = resample(inputSamples, 16000, 48000);

      // Expected output length: ceil(5 * 48000 / 16000) = ceil(15) = 15
      expect(output.length).toBe(15);

      // First and last samples should match
      expect(output[0]).toBe(inputSamples[0]);
      expect(output[output.length - 1]).toBeCloseTo(inputSamples[inputSamples.length - 1], 5);
    });
  });

  describe('edge cases', () => {
    test('empty input array returns empty array', () => {
      const inputSamples = new Float32Array(0);
      const output = resample(inputSamples, 44100, 16000);

      expect(output).toBeInstanceOf(Float32Array);
      expect(output.length).toBe(0);
    });

    test('single sample input', () => {
      const inputSamples = new Float32Array([0.5]);
      const output = resample(inputSamples, 44100, 16000);

      // Single sample should output single sample
      expect(output.length).toBe(1);
      expect(output[0]).toBe(0.5);
    });

    test('two sample input (very short array)', () => {
      const inputSamples = new Float32Array([0, 1]);
      const output = resample(inputSamples, 44100, 16000);

      // Expected output length: ceil(2 * 16000 / 44100) = ceil(0.7256) = 1
      expect(output.length).toBe(1);

      // Output should be interpolated between 0 and 1
      expect(output[0]).toBeGreaterThanOrEqual(0);
      expect(output[0]).toBeLessThanOrEqual(1);
    });

    test('three sample input', () => {
      const inputSamples = new Float32Array([0, 0.5, 1]);
      const output = resample(inputSamples, 44100, 16000);

      // Expected output length: ceil(3 * 16000 / 44100) = ceil(1.088) = 2
      expect(output.length).toBe(2);

      // First sample should match input first sample
      expect(output[0]).toBe(inputSamples[0]);
    });

    test('throws on invalid fromRate', () => {
      const inputSamples = new Float32Array([0, 1, 2]);
      expect(() => resample(inputSamples, 0, 16000)).toThrow('Invalid sample rates');
      expect(() => resample(inputSamples, -44100, 16000)).toThrow('Invalid sample rates');
    });

    test('throws on invalid toRate', () => {
      const inputSamples = new Float32Array([0, 1, 2]);
      expect(() => resample(inputSamples, 44100, 0)).toThrow('Invalid sample rates');
      expect(() => resample(inputSamples, 44100, -16000)).toThrow('Invalid sample rates');
    });
  });

  describe('correctness', () => {
    test('first output sample is within input range', () => {
      const inputSamples = new Float32Array([0.1, 0.2, 0.3, 0.4, 0.5]);
      const output = resample(inputSamples, 44100, 16000);

      expect(output[0]).toBe(inputSamples[0]);
    });

    test('last output sample is within input range', () => {
      const inputSamples = new Float32Array(100);
      for (let i = 0; i < inputSamples.length; i++) {
        inputSamples[i] = Math.sin(i * 0.1);
      }

      const output = resample(inputSamples, 44100, 16000);

      const minInput = Math.min(...inputSamples);
      const maxInput = Math.max(...inputSamples);

      expect(output[output.length - 1]).toBeGreaterThanOrEqual(minInput - 0.01);
      expect(output[output.length - 1]).toBeLessThanOrEqual(maxInput + 0.01);
    });

    test('no NaN or Infinity values', () => {
      const inputSamples = new Float32Array(100);
      for (let i = 0; i < inputSamples.length; i++) {
        inputSamples[i] = Math.random() * 2 - 1; // Random values between -1 and 1
      }

      const output = resample(inputSamples, 44100, 16000);

      for (let i = 0; i < output.length; i++) {
        expect(Number.isFinite(output[i])).toBe(true);
        expect(isNaN(output[i])).toBe(false);
      }
    });

    test('all values are finite numbers', () => {
      const inputSamples = new Float32Array([-1, -0.5, 0, 0.5, 1]);
      const output = resample(inputSamples, 48000, 16000);

      for (const sample of output) {
        expect(typeof sample).toBe('number');
        expect(isFinite(sample)).toBe(true);
      }
    });

    test('linear interpolation correctness', () => {
      // Test with simple values we can calculate manually
      const inputSamples = new Float32Array([0, 1, 2, 3, 4]);
      const output = resample(inputSamples, 5, 2); // Downsample 2.5x

      // Input: [0, 1, 2, 3, 4], 5 samples at 5 Hz
      // Output at 2 Hz: 2 samples
      // step = 5/2 = 2.5
      // i=0: pos=0, floor=0, frac=0, output[0]=0*1 + 1*0 = 0
      // i=1: pos=2.5, floor=2, frac=0.5, output[1]=2*0.5 + 3*0.5 = 2.5

      expect(output.length).toBe(2); // ceil(5 * 2 / 5) = ceil(2) = 2
      expect(output[0]).toBe(0);
      expect(output[1]).toBe(2.5);
    });
  });

  describe('quality tests', () => {
    test('simple test signal resampling', () => {
      // Create a simple triangular wave
      const inputSamples = new Float32Array(100);
      for (let i = 0; i < 100; i++) {
        inputSamples[i] = i < 50 ? i / 50 : (100 - i) / 50;
      }

      // Should not crash and should produce reasonable output
      expect(() => resample(inputSamples, 44100, 16000)).not.toThrow();
      expect(() => resample(inputSamples, 16000, 44100)).not.toThrow();
    });

    test('sine wave resampling produces smooth output', () => {
      // Create a sine wave
      const inputSamples = new Float32Array(441);
      for (let i = 0; i < inputSamples.length; i++) {
        inputSamples[i] = Math.sin((i * 2 * Math.PI) / 441); // 1 cycle
      }

      const output = resample(inputSamples, 44100, 16000);

      // Check that output is reasonably smooth (no abrupt changes)
      let maxChange = 0;
      for (let i = 1; i < output.length; i++) {
        const change = Math.abs(output[i] - output[i - 1]);
        maxChange = Math.max(maxChange, change);
      }

      // For a smooth sine wave, adjacent samples shouldn't change too much
      // This is a soft check - just ensures we don't have artifacts
      expect(maxChange).toBeLessThan(0.5);
    });

    test('constant signal remains constant', () => {
      const constantValue = 0.5;
      const inputSamples = new Float32Array(100).fill(constantValue);

      const output = resample(inputSamples, 44100, 16000);

      // All output samples should be the constant value
      for (const sample of output) {
        expect(sample).toBeCloseTo(constantValue, 10);
      }
    });

    test('zero signal remains zero', () => {
      const inputSamples = new Float32Array(100).fill(0);

      const output = resample(inputSamples, 44100, 16000);

      // All output samples should be zero
      for (const sample of output) {
        expect(sample).toBe(0);
      }
    });
  });

  describe('with custom logger', () => {
    test('resample with custom logger', () => {
      const mockLogger = {
        log: jest.fn(),
        warn: jest.fn(),
        error: jest.fn(),
      };

      const inputSamples = new Float32Array([0, 1, 2, 3, 4]);
      resample(inputSamples, 44100, 16000, mockLogger);

      // Logger should have been called
      expect(mockLogger.log).toHaveBeenCalled();
    });

    test('empty array triggers warn on custom logger', () => {
      const mockLogger = {
        log: jest.fn(),
        warn: jest.fn(),
        error: jest.fn(),
      };

      const inputSamples = new Float32Array(0);
      resample(inputSamples, 44100, 16000, mockLogger);

      expect(mockLogger.warn).toHaveBeenCalledWith('resample: empty input array, returning empty output');
    });

    test('same rate triggers log on custom logger', () => {
      const mockLogger = {
        log: jest.fn(),
        warn: jest.fn(),
        error: jest.fn(),
      };

      const inputSamples = new Float32Array([0, 1, 2, 3, 4]);
      resample(inputSamples, 44100, 44100, mockLogger);

      expect(mockLogger.log).toHaveBeenCalledWith('resample: rates are equal (44100 Hz), returning copy');
    });
  });

  describe('common sample rate conversions', () => {
    test('8000 Hz to 16000 Hz (upsampling for telephony to wideband)', () => {
      const inputSamples = new Float32Array(80);
      for (let i = 0; i < inputSamples.length; i++) {
        inputSamples[i] = i * 0.01;
      }

      const output = resample(inputSamples, 8000, 16000);

      // Expected output length: ceil(80 * 16000 / 8000) = ceil(160) = 160
      expect(output.length).toBe(160);
    });

    test('48000 Hz to 8000 Hz (professional to telephony)', () => {
      const inputSamples = new Float32Array(480);
      for (let i = 0; i < inputSamples.length; i++) {
        inputSamples[i] = Math.sin(i * 0.01);
      }

      const output = resample(inputSamples, 48000, 8000);

      // Expected output length: ceil(480 * 8000 / 48000) = ceil(80) = 80
      expect(output.length).toBe(80);
    });
  });
});
