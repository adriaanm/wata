/**
 * WAV Audio Format Utilities
 *
 * Pure JS WAV encoder/decoder for TUI audio I/O.
 * No external dependencies.
 */

import { Buffer } from 'buffer';

export interface WavHeader {
  sampleRate: number;
  numChannels: number;
  bitsPerSample: number;
  dataLength: number;
}

/**
 * Encode Float32Array samples to WAV format
 * @param samples - Audio samples as Float32Array (-1.0 to 1.0)
 * @param sampleRate - Sample rate in Hz
 * @returns WAV file as Buffer
 */
export function encodeWav(samples: Float32Array, sampleRate: number): Buffer {
  const numChannels = 1;
  const bitsPerSample = 16;
  const bytesPerSample = bitsPerSample / 8;
  const dataLength = samples.length * numChannels * bytesPerSample;
  const headerSize = 44;
  const totalSize = headerSize + dataLength;

  const wav = Buffer.alloc(totalSize);

  // RIFF chunk
  wav.write('RIFF', 0);
  wav.writeUInt32LE(totalSize - 8, 4); // File size - 8
  wav.write('WAVE', 8);

  // fmt chunk
  wav.write('fmt ', 12);
  wav.writeUInt32LE(16, 16); // Chunk size
  wav.writeUInt16LE(1, 20); // Audio format (PCM)
  wav.writeUInt16LE(numChannels, 22);
  wav.writeUInt32LE(sampleRate, 24);
  wav.writeUInt32LE(sampleRate * numChannels * bytesPerSample, 28); // Byte rate
  wav.writeUInt16LE(numChannels * bytesPerSample, 32); // Block align
  wav.writeUInt16LE(bitsPerSample, 34);

  // data chunk
  wav.write('data', 36);
  wav.writeUInt32LE(dataLength, 40);

  // Write sample data (convert float -1.0..1.0 to int16)
  let offset = headerSize;
  for (let i = 0; i < samples.length; i++) {
    // Clamp to -1.0..1.0 and scale to int16 range
    const clamped = Math.max(-1, Math.min(1, samples[i]));
    const int16 = Math.round(clamped * 32767);
    wav.writeInt16LE(int16, offset);
    offset += 2;
  }

  return wav;
}

/**
 * Decode WAV file to Float32Array samples
 * @param wavBuffer - WAV file as Buffer
 * @returns Float32Array of samples and header info
 */
export function decodeWav(wavBuffer: Buffer): {
  samples: Float32Array;
  header: WavHeader;
} {
  // Verify RIFF header
  if (wavBuffer.readUInt32LE(0) !== 0x46464952) {
    // 'RIFF' little-endian
    throw new Error('Invalid WAV: Missing RIFF header');
  }

  // Verify WAVE format
  if (wavBuffer.readUInt32LE(8) !== 0x45564157) {
    // 'WAVE' little-endian
    throw new Error('Invalid WAV: Missing WAVE format');
  }

  // Read fmt chunk
  const fmtChunkId = wavBuffer.toString('ascii', 12, 16);
  if (fmtChunkId !== 'fmt ') {
    throw new Error('Invalid WAV: Missing fmt chunk');
  }

  const audioFormat = wavBuffer.readUInt16LE(20);
  if (audioFormat !== 1) {
    throw new Error(
      `Unsupported WAV format: ${audioFormat} (only PCM supported)`,
    );
  }

  const numChannels = wavBuffer.readUInt16LE(22);
  const sampleRate = wavBuffer.readUInt32LE(24);
  const bitsPerSample = wavBuffer.readUInt16LE(34);
  const bytesPerSample = bitsPerSample / 8;

  // Find data chunk
  let dataOffset = 12;
  while (dataOffset < wavBuffer.length - 8) {
    const chunkId = wavBuffer.toString('ascii', dataOffset, dataOffset + 4);
    const chunkSize = wavBuffer.readUInt32LE(dataOffset + 4);

    if (chunkId === 'data') {
      dataOffset += 8;
      break;
    }

    dataOffset += 8 + chunkSize;
  }

  if (dataOffset >= wavBuffer.length - 8) {
    throw new Error('Invalid WAV: Missing data chunk');
  }

  const dataLength = wavBuffer.readUInt32LE(dataOffset - 4);
  const numSamples = dataLength / bytesPerSample;

  // Convert samples to Float32Array
  const samples = new Float32Array(numSamples);

  for (let i = 0; i < numSamples; i++) {
    const offset = dataOffset + i * bytesPerSample;

    let intSample: number;
    if (bytesPerSample === 1) {
      intSample = wavBuffer.readUInt8(offset);
      samples[i] = (intSample - 128) / 128;
    } else if (bytesPerSample === 2) {
      intSample = wavBuffer.readInt16LE(offset);
      samples[i] = intSample / 32768;
    } else if (bytesPerSample === 3) {
      intSample =
        wavBuffer.readUInt8(offset) |
        (wavBuffer.readUInt8(offset + 1) << 8) |
        (wavBuffer.readUInt8(offset + 2) << 16);
      // Convert 24-bit signed to float
      if (intSample & 0x800000) {
        intSample |= 0xff000000;
      }
      samples[i] = intSample / 8388608;
    } else if (bytesPerSample === 4) {
      intSample = wavBuffer.readInt32LE(offset);
      samples[i] = intSample / 2147483648;
    } else {
      throw new Error(`Unsupported bits per sample: ${bitsPerSample}`);
    }
  }

  const header: WavHeader = {
    sampleRate,
    numChannels,
    bitsPerSample,
    dataLength,
  };

  return { samples, header };
}

/**
 * Write WAV buffer to temporary file
 */
export async function writeWavTempFile(wavBuffer: Buffer): Promise<string> {
  const { tmpdir } = await import('os');
  const { writeFile } = await import('fs/promises');
  const { join } = await import('path');

  const tempPath = join(tmpdir(), `wata-afsk-${Date.now()}.wav`);
  await writeFile(tempPath, wavBuffer);
  return tempPath;
}

/**
 * Read WAV file from path
 */
export async function readWavFile(filePath: string): Promise<Buffer> {
  const { readFile } = await import('fs/promises');
  return await readFile(filePath);
}
