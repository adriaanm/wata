/// <reference types="node" />

import { spawn, ChildProcess } from 'child_process';
import { writeFile, unlink } from 'fs/promises';
import { tmpdir } from 'os';
import { join } from 'path';

import { Encoder as OpusEncoder, Decoder as OpusDecoder } from '@evan/opus';
import { PvRecorder } from '@picovoice/pvrecorder-node';

import { encodeWav } from '@shared/lib/wav.js';
import { LogService } from './LogService.js';

/**
 * Ogg page structure for parsing:
 *
 * Offset  Size  Field
 * 0       4     "OggS" magic
 * 4       1     version (0)
 * 5       1     flags (0x01=continued, 0x02=first, 0x04=last)
 * 6       8     granule position (samples)
 * 14      4     serial number
 * 18      4     page sequence number
 * 22      4     CRC32
 * 26      1     number of segments
 * 27      N     segment table (N bytes, where N = number of segments)
 * 27+N    ...   page data
 */

/**
 * Represents a parsed Ogg page
 */
interface OggPage {
  version: number;
  flags: number;
  granulePosition: bigint;
  serialNumber: number;
  pageSequence: number;
  segmentTable: number[];
  data: Buffer;
}

/**
 * Ogg demuxer for extracting Opus packets from Ogg container
 *
 * Usage:
 *   const demuxer = new OggDemuxer();
 *   const opusPackets = demuxer.demux(oggBuffer);
 *   // opusPackets is an array of Buffers, each containing one Opus packet
 */
class OggDemuxer {
  private static readonly OGG_MAGIC = 'OggS';
  private static readonly HEADER_SIZE = 27; // Fixed header size before segment table

  /**
   * Demux an Ogg Opus buffer and extract audio packets
   * Skips OpusHead (first packet) and OpusTags (second packet)
   *
   * @param oggBuffer - Complete Ogg Opus file as a Buffer
   * @returns Array of Opus audio packet Buffers
   */
  demux(oggBuffer: Buffer): Buffer[] {
    const pages = this.parsePages(oggBuffer);
    const packets = this.extractPackets(pages);

    // Skip first two packets (OpusHead and OpusTags)
    // OpusHead starts with "OpusHead" magic
    // OpusTags starts with "OpusTags" magic
    if (packets.length < 2) {
      LogService.getInstance().addEntry(
        'warn',
        `Ogg demuxer: Expected at least 2 header packets, got ${packets.length}`,
      );
      return [];
    }

    // Validate OpusHead
    const opusHead = packets[0];
    if (
      opusHead.length < 8 ||
      opusHead.toString('ascii', 0, 8) !== 'OpusHead'
    ) {
      LogService.getInstance().addEntry(
        'warn',
        `Ogg demuxer: Invalid OpusHead packet`,
      );
    }

    // Validate OpusTags
    const opusTags = packets[1];
    if (
      opusTags.length < 8 ||
      opusTags.toString('ascii', 0, 8) !== 'OpusTags'
    ) {
      LogService.getInstance().addEntry(
        'warn',
        `Ogg demuxer: Invalid OpusTags packet`,
      );
    }

    // Return audio packets only (skip headers)
    const audioPackets = packets.slice(2);
    LogService.getInstance().addEntry(
      'log',
      `Ogg demuxer: Extracted ${audioPackets.length} audio packets from ${pages.length} pages`,
    );

    return audioPackets;
  }

  /**
   * Parse all Ogg pages from a buffer
   */
  private parsePages(buffer: Buffer): OggPage[] {
    const pages: OggPage[] = [];
    let offset = 0;

    while (offset < buffer.length) {
      const page = this.parsePage(buffer, offset);
      if (!page) {
        break;
      }
      pages.push(page.page);
      offset = page.nextOffset;
    }

    return pages;
  }

  /**
   * Parse a single Ogg page at the given offset
   * @returns The parsed page and the offset of the next page, or null if invalid
   */
  private parsePage(
    buffer: Buffer,
    offset: number,
  ): { page: OggPage; nextOffset: number } | null {
    // Check if we have enough bytes for the fixed header
    if (offset + OggDemuxer.HEADER_SIZE > buffer.length) {
      return null;
    }

    // Validate magic number
    const magic = buffer.toString('ascii', offset, offset + 4);
    if (magic !== OggDemuxer.OGG_MAGIC) {
      LogService.getInstance().addEntry(
        'warn',
        `Ogg demuxer: Invalid magic at offset ${offset}: "${magic}"`,
      );
      return null;
    }

    // Parse fixed header fields
    const version = buffer.readUInt8(offset + 4);
    const flags = buffer.readUInt8(offset + 5);
    const granulePosition = buffer.readBigUInt64LE(offset + 6);
    const serialNumber = buffer.readUInt32LE(offset + 14);
    const pageSequence = buffer.readUInt32LE(offset + 18);
    // CRC32 at offset + 22 (we skip validation for simplicity)
    const numSegments = buffer.readUInt8(offset + 26);

    // Check if we have enough bytes for segment table
    if (offset + OggDemuxer.HEADER_SIZE + numSegments > buffer.length) {
      LogService.getInstance().addEntry(
        'warn',
        `Ogg demuxer: Truncated segment table at offset ${offset}`,
      );
      return null;
    }

    // Read segment table
    const segmentTable: number[] = [];
    let dataSize = 0;
    for (let i = 0; i < numSegments; i++) {
      const segmentSize = buffer.readUInt8(
        offset + OggDemuxer.HEADER_SIZE + i,
      );
      segmentTable.push(segmentSize);
      dataSize += segmentSize;
    }

    const dataOffset = offset + OggDemuxer.HEADER_SIZE + numSegments;

    // Check if we have enough bytes for page data
    if (dataOffset + dataSize > buffer.length) {
      LogService.getInstance().addEntry(
        'warn',
        `Ogg demuxer: Truncated page data at offset ${offset}`,
      );
      return null;
    }

    // Extract page data
    const data = buffer.subarray(dataOffset, dataOffset + dataSize);

    return {
      page: {
        version,
        flags,
        granulePosition,
        serialNumber,
        pageSequence,
        segmentTable,
        data,
      },
      nextOffset: dataOffset + dataSize,
    };
  }

  /**
   * Extract packets from parsed Ogg pages
   *
   * Segment table rules:
   * - Each segment can be 0-255 bytes
   * - Segment size 255 means the packet continues in the next segment
   * - Segment size < 255 marks the end of a packet
   * - A packet can span multiple segments and even multiple pages
   */
  private extractPackets(pages: OggPage[]): Buffer[] {
    const packets: Buffer[] = [];
    let pendingPacket: Buffer[] = []; // Accumulated segments for current packet

    for (const page of pages) {
      let dataOffset = 0;

      for (const segmentSize of page.segmentTable) {
        // Extract this segment's data
        const segmentData = page.data.subarray(
          dataOffset,
          dataOffset + segmentSize,
        );
        dataOffset += segmentSize;

        // Add segment to current packet
        pendingPacket.push(segmentData);

        // If segment size < 255, the packet is complete
        if (segmentSize < 255) {
          // Concatenate all segments into one packet
          const packet = Buffer.concat(pendingPacket);
          // Don't add empty packets (can occur with size-0 segments)
          if (packet.length > 0) {
            packets.push(packet);
          }
          pendingPacket = [];
        }
        // If segment size == 255, packet continues in next segment
      }
    }

    // Handle any remaining incomplete packet (shouldn't happen in valid files)
    if (pendingPacket.length > 0) {
      const packet = Buffer.concat(pendingPacket);
      if (packet.length > 0) {
        LogService.getInstance().addEntry(
          'warn',
          `Ogg demuxer: Incomplete packet at end of stream (${packet.length} bytes)`,
        );
        packets.push(packet);
      }
    }

    return packets;
  }
}

// ============================================================================
// Ogg Opus Muxer
// ============================================================================

/**
 * CRC32 lookup table for Ogg's polynomial (0x04C11DB7)
 * Ogg uses CRC-32 with polynomial 0x04C11DB7 in normal (non-reflected) form.
 * This is different from the common "reflected" CRC-32 used in zlib/gzip.
 */
const OGG_CRC32_TABLE: number[] = (() => {
  const table: number[] = new Array(256);
  const polynomial = 0x04c11db7;

  for (let i = 0; i < 256; i++) {
    let crc = i << 24;
    for (let j = 0; j < 8; j++) {
      if (crc & 0x80000000) {
        crc = ((crc << 1) ^ polynomial) >>> 0;
      } else {
        crc = (crc << 1) >>> 0;
      }
    }
    table[i] = crc >>> 0;
  }
  return table;
})();

/**
 * Calculate CRC32 for Ogg pages using Ogg's polynomial
 */
function oggCrc32(data: Uint8Array): number {
  let crc = 0;
  for (let i = 0; i < data.length; i++) {
    const tableIndex = ((crc >>> 24) ^ data[i]) & 0xff;
    crc = ((crc << 8) ^ OGG_CRC32_TABLE[tableIndex]) >>> 0;
  }
  return crc >>> 0;
}

/**
 * Ogg page flags
 */
const OGG_FLAG_BOS = 0x02; // Beginning of stream (first page)
const OGG_FLAG_EOS = 0x04; // End of stream (last page)

/**
 * Create an Ogg page header
 *
 * Page structure (27+ bytes):
 * - "OggS" magic (4 bytes)
 * - Version (1 byte, always 0)
 * - Flags (1 byte: BOS=0x02, EOS=0x04, continued=0x01)
 * - Granule position (8 bytes, little-endian)
 * - Serial number (4 bytes, little-endian)
 * - Page sequence number (4 bytes, little-endian)
 * - CRC32 (4 bytes, little-endian) - calculated over entire page with CRC field set to 0
 * - Number of segments (1 byte)
 * - Segment table (n bytes, each segment size 0-255)
 */
function createOggPage(
  payload: Uint8Array,
  granulePosition: bigint,
  serialNumber: number,
  pageSequence: number,
  flags: number,
): Uint8Array {
  // Calculate segment table
  // Each segment can be 0-255 bytes. Segments of 255 bytes indicate continuation.
  // A segment < 255 bytes marks the end of a packet.
  const segments: number[] = [];
  let remaining = payload.length;

  while (remaining >= 255) {
    segments.push(255);
    remaining -= 255;
  }
  segments.push(remaining); // Final segment (can be 0 if payload is multiple of 255)

  // Header size: 27 bytes fixed + segment table
  const headerSize = 27 + segments.length;
  const pageSize = headerSize + payload.length;
  const page = new Uint8Array(pageSize);
  const view = new DataView(page.buffer);

  // Write header
  page[0] = 0x4f; // 'O'
  page[1] = 0x67; // 'g'
  page[2] = 0x67; // 'g'
  page[3] = 0x53; // 'S'
  page[4] = 0; // Version (always 0)
  page[5] = flags;

  // Granule position (64-bit little-endian)
  view.setBigUint64(6, granulePosition, true);

  // Serial number
  view.setUint32(14, serialNumber, true);

  // Page sequence number
  view.setUint32(18, pageSequence, true);

  // CRC32 placeholder (will be calculated later)
  view.setUint32(22, 0, true);

  // Number of segments
  page[26] = segments.length;

  // Segment table
  for (let i = 0; i < segments.length; i++) {
    page[27 + i] = segments[i];
  }

  // Payload
  page.set(payload, headerSize);

  // Calculate and write CRC32
  const crc = oggCrc32(page);
  view.setUint32(22, crc, true);

  return page;
}

/**
 * Create OpusHead packet (19 bytes for mono, no channel mapping)
 *
 * Structure:
 * - "OpusHead" magic (8 bytes)
 * - Version (1 byte, must be 1)
 * - Channel count (1 byte)
 * - Pre-skip (2 bytes, little-endian) - samples to skip at start
 * - Input sample rate (4 bytes, little-endian) - original sample rate
 * - Output gain (2 bytes, little-endian, signed) - dB gain adjustment
 * - Mapping family (1 byte, 0 = mono/stereo, no mapping table)
 */
function createOpusHead(
  channels: number,
  preSkip: number,
  inputSampleRate: number,
): Uint8Array {
  const head = new Uint8Array(19);
  const view = new DataView(head.buffer);

  // Magic "OpusHead"
  head[0] = 0x4f; // 'O'
  head[1] = 0x70; // 'p'
  head[2] = 0x75; // 'u'
  head[3] = 0x73; // 's'
  head[4] = 0x48; // 'H'
  head[5] = 0x65; // 'e'
  head[6] = 0x61; // 'a'
  head[7] = 0x64; // 'd'

  head[8] = 1; // Version (must be 1)
  head[9] = channels; // Channel count

  view.setUint16(10, preSkip, true); // Pre-skip
  view.setUint32(12, inputSampleRate, true); // Input sample rate
  view.setInt16(16, 0, true); // Output gain (0 dB)
  head[18] = 0; // Mapping family (0 = mono/stereo, no table)

  return head;
}

/**
 * Create OpusTags packet
 *
 * Structure:
 * - "OpusTags" magic (8 bytes)
 * - Vendor string length (4 bytes, little-endian)
 * - Vendor string (variable)
 * - Comment count (4 bytes, little-endian)
 * - Comments (variable, each: length (4) + string)
 */
function createOpusTags(vendor: string = 'libopus'): Uint8Array {
  const vendorBytes = new TextEncoder().encode(vendor);
  const size = 8 + 4 + vendorBytes.length + 4; // magic + vendor length + vendor + comment count
  const tags = new Uint8Array(size);
  const view = new DataView(tags.buffer);

  // Magic "OpusTags"
  tags[0] = 0x4f; // 'O'
  tags[1] = 0x70; // 'p'
  tags[2] = 0x75; // 'u'
  tags[3] = 0x73; // 's'
  tags[4] = 0x54; // 'T'
  tags[5] = 0x61; // 'a'
  tags[6] = 0x67; // 'g'
  tags[7] = 0x73; // 's'

  // Vendor string length + vendor string
  view.setUint32(8, vendorBytes.length, true);
  tags.set(vendorBytes, 12);

  // Comment count (0)
  view.setUint32(12 + vendorBytes.length, 0, true);

  return tags;
}

/**
 * Ogg Opus muxer - combines Opus packets into a valid Ogg Opus file
 *
 * The muxer creates:
 * 1. OpusHead page (BOS flag, granule = 0)
 * 2. OpusTags page (granule = 0)
 * 3. Audio data pages (granule = cumulative sample count at 48kHz)
 *
 * Note: Ogg Opus always uses 48kHz for granule positions regardless of
 * the actual input sample rate. This is part of the Ogg Opus spec.
 */
class OggOpusMuxer {
  private serialNumber: number;
  private pageSequence: number = 0;
  private granulePosition: bigint = BigInt(0);
  private pages: Uint8Array[] = [];
  private readonly preSkip: number;
  private readonly inputSampleRate: number;
  private readonly channels: number;

  /**
   * Create a new Ogg Opus muxer
   * @param inputSampleRate - Original sample rate (e.g., 16000)
   * @param channels - Number of channels (1 for mono)
   * @param preSkip - Samples to skip at start (312 is standard for Opus)
   */
  constructor(
    inputSampleRate: number = 16000,
    channels: number = 1,
    preSkip: number = 312,
  ) {
    this.serialNumber = Math.floor(Math.random() * 0xffffffff);
    this.inputSampleRate = inputSampleRate;
    this.channels = channels;
    this.preSkip = preSkip;
  }

  /**
   * Initialize the muxer by writing header pages
   * Must be called before adding audio packets
   */
  writeHeaders(): void {
    // Page 0: OpusHead (BOS)
    const opusHead = createOpusHead(
      this.channels,
      this.preSkip,
      this.inputSampleRate,
    );
    const headPage = createOggPage(
      opusHead,
      BigInt(0), // Granule position 0 for header
      this.serialNumber,
      this.pageSequence++,
      OGG_FLAG_BOS,
    );
    this.pages.push(headPage);

    // Page 1: OpusTags
    const opusTags = createOpusTags('wata');
    const tagsPage = createOggPage(
      opusTags,
      BigInt(0), // Granule position 0 for header
      this.serialNumber,
      this.pageSequence++,
      0, // No flags
    );
    this.pages.push(tagsPage);
  }

  /**
   * Add an Opus packet to the muxer
   * @param packet - Encoded Opus packet
   * @param samplesAtInputRate - Number of samples in this packet at input sample rate
   */
  addPacket(packet: Uint8Array, samplesAtInputRate: number): void {
    // Opus internally operates at 48kHz, so we need to scale the granule position
    // Granule = cumulative samples at 48kHz
    const samplesAt48k = Math.round(
      (samplesAtInputRate * 48000) / this.inputSampleRate,
    );
    this.granulePosition += BigInt(samplesAt48k);

    // Create audio page (one packet per page for simplicity)
    // In a more sophisticated muxer, we could combine multiple packets per page
    const audioPage = createOggPage(
      packet,
      this.granulePosition,
      this.serialNumber,
      this.pageSequence++,
      0, // No flags for middle pages
    );
    this.pages.push(audioPage);
  }

  /**
   * Finalize the Ogg stream and return the complete file
   * @param lastPacket - Optional final packet (with EOS flag)
   * @param samplesAtInputRate - Samples in last packet at input rate
   */
  finalize(lastPacket?: Uint8Array, samplesAtInputRate?: number): Uint8Array {
    if (lastPacket && samplesAtInputRate !== undefined) {
      // Scale to 48kHz for granule position
      const samplesAt48k = Math.round(
        (samplesAtInputRate * 48000) / this.inputSampleRate,
      );
      this.granulePosition += BigInt(samplesAt48k);

      // Create final page with EOS flag
      const finalPage = createOggPage(
        lastPacket,
        this.granulePosition,
        this.serialNumber,
        this.pageSequence++,
        OGG_FLAG_EOS,
      );
      this.pages.push(finalPage);
    } else if (this.pages.length > 2) {
      // No final packet provided, but we have audio pages
      // We need to rewrite the last audio page with EOS flag
      // Pop the last page and recreate it with EOS
      const lastPage = this.pages.pop()!;
      // Extract the payload from the last page (skip header)
      const numSegments = lastPage[26];
      const headerSize = 27 + numSegments;
      const payload = lastPage.slice(headerSize);

      // Recreate with EOS flag
      const eosPage = createOggPage(
        payload,
        this.granulePosition,
        this.serialNumber,
        this.pageSequence - 1, // Same sequence number
        OGG_FLAG_EOS,
      );
      this.pages.push(eosPage);
    }

    // Concatenate all pages
    const totalSize = this.pages.reduce((sum, page) => sum + page.length, 0);
    const result = new Uint8Array(totalSize);
    let offset = 0;
    for (const page of this.pages) {
      result.set(page, offset);
      offset += page.length;
    }

    return result;
  }

  /**
   * Add multiple packets and finalize in one call
   * @param packets - Array of {data: Uint8Array, samples: number}
   */
  muxPackets(
    packets: Array<{ data: Uint8Array; samples: number }>,
  ): Uint8Array {
    this.writeHeaders();

    for (let i = 0; i < packets.length - 1; i++) {
      this.addPacket(packets[i].data, packets[i].samples);
    }

    // Last packet with EOS
    if (packets.length > 0) {
      const last = packets[packets.length - 1];
      return this.finalize(last.data, last.samples);
    }

    return this.finalize();
  }
}

// ============================================================================
// End Ogg Opus Muxer
// ============================================================================

export interface RecordingResult {
  buffer: Buffer;
  duration: number;
  size: number;
  mimeType: string;
}

/**
 * Frame accumulator to handle PvRecorder → Opus frame size conversion
 *
 * PvRecorder: 512 samples/frame at 16kHz
 * Opus (16kHz): 320 (20ms), 640 (40ms), or 960 (60ms) samples
 *
 * We accumulate 512-sample frames to reach 960-sample Opus frames.
 */
class FrameAccumulator {
  private buffer: Int16Array = new Int16Array(0);
  private readonly targetSize: number;

  constructor(targetSize: number) {
    this.targetSize = targetSize;
  }

  /**
   * Add samples and return complete Opus frames
   * @returns Array of complete frames (may be empty if not enough samples accumulated)
   */
  add(samples: Int16Array): Int16Array[] {
    // Concatenate new samples with buffer
    const combined = new Int16Array(this.buffer.length + samples.length);
    combined.set(this.buffer);
    combined.set(samples, this.buffer.length);
    this.buffer = combined;

    const frames: Int16Array[] = [];

    // Extract complete frames
    while (this.buffer.length >= this.targetSize) {
      const frame = this.buffer.slice(0, this.targetSize);
      frames.push(frame);

      // Keep remaining samples
      this.buffer = this.buffer.slice(this.targetSize);
    }

    return frames;
  }

  /**
   * Get any remaining samples (for end of recording)
   * Pad with zeros if needed
   */
  flush(): Int16Array | null {
    if (this.buffer.length === 0) {
      return null;
    }

    // Pad to target size with zeros
    if (this.buffer.length < this.targetSize) {
      const padded = new Int16Array(this.targetSize);
      padded.set(this.buffer);
      // Rest is already zeros
      this.buffer = padded;
    }

    const result = this.buffer;
    this.buffer = new Int16Array(0);
    return result;
  }

  /**
   * Get current buffer size
   */
  get pendingSamples(): number {
    return this.buffer.length;
  }

  /**
   * Reset accumulator
   */
  reset(): void {
    this.buffer = new Int16Array(0);
  }
}

/**
 * Audio service using PvRecorder + @evan/opus (no FFmpeg dependency)
 *
 * Architecture:
 * - PvRecorder captures PCM audio (Int16Array frames, 16kHz, 512 samples/frame)
 * - Raw PCM samples are accumulated during recording
 * - @evan/opus encodes to Opus packets, wrapped in Ogg container
 *
 * Benefits of 16kHz:
 * - Lower bandwidth than 48kHz (3x less data)
 * - Optimized for voice/speech
 * - Sufficient for walkie-talkie audio quality
 */
export class PvRecorderAudioService {
  private recorder: PvRecorder | null = null;
  private accumulator: FrameAccumulator | null = null;

  private isRecording: boolean = false;
  private isStopping: boolean = false; // True while stopRecording() is in progress
  private recordingStartTime: number = 0;
  private pcmSamples: Int16Array[] = []; // Accumulate raw PCM for Opus encoding

  private isPlaying: boolean = false;
  private playProcess: ChildProcess | null = null;
  private currentAudioPath: string | null = null;

  // PvRecorder configuration
  private readonly PV_FRAME_LENGTH = 512; // PvRecorder samples per frame
  private readonly PV_SAMPLE_RATE = 16000; // PvRecorder sample rate (fixed)
  private readonly OPUS_SAMPLE_RATE = 16000; // 16kHz (voice quality)
  private readonly OPUS_FRAME_SIZE = 960; // 60ms at 16kHz
  private readonly OPUS_CHANNELS = 1; // Mono

  /**
   * Initialize audio recorder
   */
  async initialize(deviceIndex: number = -1): Promise<void> {
    try {
      // Initialize PvRecorder (outputs 16kHz)
      this.recorder = new PvRecorder(this.PV_FRAME_LENGTH, deviceIndex);

      // Initialize frame accumulator
      this.accumulator = new FrameAccumulator(this.OPUS_FRAME_SIZE);

      LogService.getInstance().addEntry(
        'log',
        `Audio initialized: PvRecorder v${this.recorder.version}, Opus @ ${this.OPUS_SAMPLE_RATE}Hz`,
      );
    } catch (error) {
      const errorMsg = error instanceof Error ? error.message : String(error);
      LogService.getInstance().addEntry(
        'error',
        `Failed to initialize audio: ${errorMsg}`,
      );
      throw error;
    }
  }

  /**
   * Get available audio devices
   */
  getAvailableDevices(): string[] {
    try {
      return PvRecorder.getAvailableDevices();
    } catch (error) {
      const errorMsg = error instanceof Error ? error.message : String(error);
      LogService.getInstance().addEntry(
        'error',
        `Failed to get devices: ${errorMsg}`,
      );
      return [];
    }
  }

  /**
   * Start recording audio
   */
  async startRecording(): Promise<void> {
    if (!this.recorder || !this.accumulator) {
      throw new Error('Audio not initialized. Call initialize() first.');
    }

    if (this.isRecording) {
      throw new Error('Already recording');
    }

    this.pcmSamples = [];
    this.accumulator.reset();
    this.recordingStartTime = Date.now();

    try {
      this.recorder.start();
      // Small delay to let PvRecorder fully initialize before reading
      await new Promise(resolve => setTimeout(resolve, 50));
      this.isRecording = true;
      LogService.getInstance().addEntry('log', 'Recording started');
    } catch (error) {
      this.isRecording = false;
      throw error;
    }
  }

  /**
   * Record continuously and accumulate PCM samples
   * Call this repeatedly while recording to capture audio frames
   */
  async recordFrame(): Promise<boolean> {
    if (!this.isRecording || !this.recorder || !this.accumulator) {
      return false;
    }

    try {
      // Read a frame of PCM audio (Int16Array, 512 samples at 16kHz)
      const pcmFrame = await this.recorder.read();

      // Store raw PCM for Opus encoding later
      this.pcmSamples.push(new Int16Array(pcmFrame));

      return true;
    } catch (error) {
      // If recording was stopped or is being stopped, the read() will fail - this is expected
      if (!this.isRecording || this.isStopping) {
        return false;
      }
      // Only log unexpected errors
      const errorMsg = error instanceof Error ? error.message : String(error);
      LogService.getInstance().addEntry(
        'error',
        `Frame capture error: ${errorMsg}`,
      );
      return false;
    }
  }

  /**
   * Stop recording and return the encoded audio as Ogg Opus
   */
  async stopRecording(): Promise<RecordingResult> {
    if (!this.isRecording) {
      throw new Error('Not recording');
    }

    this.isStopping = true;
    this.isRecording = false;

    try {
      // Stop the recorder
      if (this.recorder) {
        this.recorder.stop();
      }

      const duration = Date.now() - this.recordingStartTime;

      // Concatenate all PCM samples into a single buffer
      const totalSamples = this.pcmSamples.reduce(
        (sum, arr) => sum + arr.length,
        0,
      );
      const pcmBuffer = Buffer.alloc(totalSamples * 2); // 2 bytes per Int16 sample
      let offset = 0;
      for (const samples of this.pcmSamples) {
        for (let i = 0; i < samples.length; i++) {
          pcmBuffer.writeInt16LE(samples[i], offset);
          offset += 2;
        }
      }

      // Encode to Ogg Opus using @evan/opus
      const oggBuffer = await this.encodeToOggOpus(pcmBuffer);

      const result: RecordingResult = {
        buffer: oggBuffer,
        duration,
        size: oggBuffer.length,
        mimeType: 'audio/ogg; codecs=opus',
      };

      LogService.getInstance().addEntry(
        'log',
        `Recording stopped: ${duration}ms, ${totalSamples} samples, ${oggBuffer.length} bytes`,
      );

      this.pcmSamples = [];
      this.isStopping = false;
      return result;
    } catch (error) {
      this.pcmSamples = [];
      this.isStopping = false;
      throw error;
    }
  }

  /**
   * Encode raw PCM buffer to Ogg Opus using @evan/opus
   */
  private async encodeToOggOpus(pcmBuffer: Buffer): Promise<Buffer> {
    // Create Opus encoder (16kHz, mono)
    const encoder = new OpusEncoder({
      sample_rate: this.OPUS_SAMPLE_RATE as 16000,
      channels: this.OPUS_CHANNELS as 1,
      application: 'voip',
    });

    // Create Ogg muxer
    const muxer = new OggOpusMuxer(
      this.OPUS_SAMPLE_RATE,
      this.OPUS_CHANNELS,
      312, // Standard pre-skip for Opus
    );
    muxer.writeHeaders();

    // Break PCM into frames and encode
    const totalSamples = pcmBuffer.length / 2; // 2 bytes per Int16 sample
    const packets: Array<{ data: Uint8Array; samples: number }> = [];

    for (let offset = 0; offset < totalSamples; offset += this.OPUS_FRAME_SIZE) {
      const frameLength = Math.min(this.OPUS_FRAME_SIZE, totalSamples - offset);

      // Extract frame as Int16Array
      const frameBuffer = pcmBuffer.subarray(offset * 2, (offset + frameLength) * 2);

      // Pad if last frame is smaller than OPUS_FRAME_SIZE
      let pcmFrame: Buffer;
      if (frameLength < this.OPUS_FRAME_SIZE) {
        pcmFrame = Buffer.alloc(this.OPUS_FRAME_SIZE * 2);
        frameBuffer.copy(pcmFrame);
      } else {
        pcmFrame = frameBuffer as Buffer;
      }

      // Encode frame to Opus packet
      const opusPacket = encoder.encode(pcmFrame);

      packets.push({
        data: new Uint8Array(opusPacket),
        samples: this.OPUS_FRAME_SIZE, // Always full frame size for granule calculation
      });
    }

    // Add all packets except last
    for (let i = 0; i < packets.length - 1; i++) {
      muxer.addPacket(packets[i].data, packets[i].samples);
    }

    // Finalize with last packet (EOS flag)
    let oggData: Uint8Array;
    if (packets.length > 0) {
      const last = packets[packets.length - 1];
      oggData = muxer.finalize(last.data, last.samples);
    } else {
      oggData = muxer.finalize();
    }

    LogService.getInstance().addEntry(
      'log',
      `Encoded ${packets.length} Opus packets (${oggData.length} bytes)`,
    );

    return Buffer.from(oggData);
  }

  /**
   * Cancel recording without saving
   */
  async cancelRecording(): Promise<void> {
    if (!this.isRecording) return;

    this.isStopping = true;
    this.isRecording = false;

    try {
      if (this.recorder) {
        this.recorder.stop();
      }
    } catch (_error) {
      // Ignore stop errors
    }

    if (this.accumulator) {
      this.accumulator.reset();
    }

    this.pcmSamples = [];
    this.isStopping = false;
    LogService.getInstance().addEntry('log', 'Recording cancelled');
  }

  /**
   * Get recording state
   */
  getIsRecording(): boolean {
    return this.isRecording;
  }

  /**
   * Get recording duration in milliseconds
   */
  getRecordingDuration(): number {
    if (!this.isRecording) return 0;
    return Date.now() - this.recordingStartTime;
  }

  /**
   * Get number of pending samples in accumulator
   */
  getPendingSamples(): number {
    return this.accumulator?.pendingSamples ?? 0;
  }

  /**
   * Release resources
   */
  async release(): Promise<void> {
    if (this.isRecording) {
      await this.cancelRecording();
    }

    if (this.isPlaying) {
      await this.stopPlayback();
    }

    if (this.recorder) {
      this.recorder.release();
      this.recorder = null;
    }

    this.accumulator = null;

    LogService.getInstance().addEntry('log', 'Audio resources released');
  }

  /**
   * Start playback of audio from a URL
   * Supports both Ogg Opus and M4A/AAC formats
   * @param audioUrl - URL of the audio file
   * @param accessToken - Optional access token for authenticated downloads
   */
  async startPlayback(audioUrl: string, accessToken?: string): Promise<void> {
    if (this.isPlaying) {
      await this.stopPlayback();
    }

    this.currentAudioPath = audioUrl;
    this.isPlaying = true;

    // Log the URL being fetched for debugging
    LogService.getInstance().addEntry(
      'log',
      `Playback: Fetching audio from ${audioUrl}`,
    );

    try {
      // Build fetch headers with authentication if token is provided
      const headers: Record<string, string> = {};
      if (accessToken) {
        headers.Authorization = `Bearer ${accessToken}`;
        LogService.getInstance().addEntry(
          'log',
          `Playback: Using authenticated download`,
        );
      }

      // Download the audio file
      const response = await fetch(audioUrl, { headers });
      if (!response.ok) {
        throw new Error(
          `Failed to download audio: ${response.statusText} (${response.status})`,
        );
      }

      const arrayBuffer = await response.arrayBuffer();
      const audioBuffer = Buffer.from(arrayBuffer);

      // Detect format from magic bytes
      const isOgg =
        audioBuffer.length >= 4 &&
        audioBuffer.toString('ascii', 0, 4) === 'OggS';
      const contentType = response.headers.get('content-type') || '';
      const needsConversion =
        isOgg || contentType.includes('ogg') || contentType.includes('opus');

      const timestamp = Date.now();
      let playPath: string;

      if (needsConversion) {
        // Decode Ogg Opus to WAV using @evan/opus
        const wavPath = join(tmpdir(), `wata-play-${timestamp}.wav`);

        // Extract Opus packets from Ogg container
        const demuxer = new OggDemuxer();
        const opusPackets = demuxer.demux(audioBuffer);

        if (opusPackets.length === 0) {
          throw new Error('No audio packets found in Ogg file');
        }

        // Create Opus decoder (16kHz, mono)
        const decoder = new OpusDecoder({
          sample_rate: this.OPUS_SAMPLE_RATE as 16000,
          channels: this.OPUS_CHANNELS as 1,
        });

        // Decode all packets and concatenate PCM
        const pcmFrames: Int16Array[] = [];
        for (const packet of opusPackets) {
          const pcm = decoder.decode(packet);
          pcmFrames.push(new Int16Array(pcm.buffer, pcm.byteOffset, pcm.length / 2));
        }

        // Concatenate all PCM frames
        const totalSamples = pcmFrames.reduce((sum, frame) => sum + frame.length, 0);
        const pcmData = new Float32Array(totalSamples);
        let offset = 0;
        for (const frame of pcmFrames) {
          // Convert Int16 to Float32 (-1.0 to 1.0)
          for (let i = 0; i < frame.length; i++) {
            pcmData[offset++] = frame[i] / 32768;
          }
        }

        // Encode to WAV and write to file
        const wavBuffer = encodeWav(pcmData, this.OPUS_SAMPLE_RATE);
        await writeFile(wavPath, wavBuffer);

        LogService.getInstance().addEntry(
          'log',
          `Decoded ${opusPackets.length} Opus packets to WAV (${totalSamples} samples)`,
        );

        playPath = wavPath;
      } else {
        // M4A/AAC can be played directly by afplay
        playPath = join(tmpdir(), `wata-play-${timestamp}.m4a`);
        await writeFile(playPath, audioBuffer);
      }

      // Play using afplay (macOS built-in)
      // Use 'ignore' for stdio to prevent any output from interfering with terminal input
      this.playProcess = spawn('afplay', [playPath], { stdio: 'ignore' });

      this.playProcess.on('close', () => {
        this.isPlaying = false;
        this.currentAudioPath = null;
        this.playProcess = null;
        // Clean up temp file
        unlink(playPath).catch(() => {});
      });

      this.playProcess.on('error', err => {
        const errorMsg = err instanceof Error ? err.message : String(err);
        LogService.getInstance().addEntry(
          'error',
          `Playback error: ${errorMsg}`,
        );
        this.isPlaying = false;
        this.currentAudioPath = null;
        this.playProcess = null;
        unlink(playPath).catch(() => {});
      });

      LogService.getInstance().addEntry(
        'log',
        `Playback started: ${needsConversion ? 'Ogg Opus' : 'M4A'}`,
      );
    } catch (error) {
      this.isPlaying = false;
      this.currentAudioPath = null;
      throw error;
    }
  }

  /**
   * Stop current playback
   */
  async stopPlayback(): Promise<void> {
    if (this.playProcess) {
      this.playProcess.kill();
      this.playProcess = null;
    }
    this.isPlaying = false;
    this.currentAudioPath = null;
  }

  /**
   * Get playback state
   */
  getIsPlaying(): boolean {
    return this.isPlaying;
  }

  /**
   * Get current playback URL
   */
  getCurrentAudioUrl(): string | null {
    return this.currentAudioPath;
  }
}

// Export singleton instance
export const pvRecorderAudioService = new PvRecorderAudioService();
