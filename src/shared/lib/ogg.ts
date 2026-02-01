/**
 * Ogg container format utilities for muxing and demuxing Ogg Opus audio
 *
 * This module provides:
 * - OggDemuxer: Extract Opus packets from Ogg container
 * - OggOpusMuxer: Combine Opus packets into Ogg container
 * - Helper functions for Ogg page creation and Opus header/tag generation
 *
 * References:
 * - Ogg spec: https://xiph.org/ogg/doc/framing.html
 * - Ogg Opus spec: https://wiki.xiph.org/OggOpus
 */

import type { Logger } from './wata-client/types.js';

// ============================================================================
// Ogg Page Structure
// ============================================================================

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
export interface OggPage {
  version: number;
  flags: number;
  granulePosition: bigint;
  serialNumber: number;
  pageSequence: number;
  segmentTable: number[];
  data: Buffer;
}

// ============================================================================
// Ogg Demuxer
// ============================================================================

/**
 * Ogg demuxer for extracting Opus packets from Ogg container
 *
 * Usage:
 *   const demuxer = new OggDemuxer(logger);
 *   const opusPackets = demuxer.demux(oggBuffer);
 *   // opusPackets is an array of Buffers, each containing one Opus packet
 */
export class OggDemuxer {
  private static readonly OGG_MAGIC = 'OggS';
  private static readonly HEADER_SIZE = 27; // Fixed header size before segment table

  constructor(private readonly logger?: Logger) {}

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
      this.logger?.warn(
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
      this.logger?.warn(`Ogg demuxer: Invalid OpusHead packet`);
    }

    // Validate OpusTags
    const opusTags = packets[1];
    if (
      opusTags.length < 8 ||
      opusTags.toString('ascii', 0, 8) !== 'OpusTags'
    ) {
      this.logger?.warn(`Ogg demuxer: Invalid OpusTags packet`);
    }

    // Return audio packets only (skip headers)
    const audioPackets = packets.slice(2);
    this.logger?.log(
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
      this.logger?.warn(
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
      this.logger?.warn(
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
      this.logger?.warn(
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
        this.logger?.warn(
          `Ogg demuxer: Incomplete packet at end of stream (${packet.length} bytes)`,
        );
        packets.push(packet);
      }
    }

    return packets;
  }
}

// ============================================================================
// Ogg Muxer Utilities
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
export function oggCrc32(data: Uint8Array): number {
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
export function createOggPage(
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
export function createOpusHead(
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
export function createOpusTags(vendor: string = 'libopus'): Uint8Array {
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

// ============================================================================
// Ogg Opus Muxer
// ============================================================================

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
export class OggOpusMuxer {
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
