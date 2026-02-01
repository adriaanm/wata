/**
 * Unit tests for Ogg container format utilities
 *
 * Tests cover:
 * - oggCrc32 function
 * - createOpusHead function
 * - createOpusTags function
 * - createOggPage function
 * - OggDemuxer class
 * - OggOpusMuxer class
 * - Integration-style mux/demux roundtrip test
 */

import { Buffer } from 'buffer';

import {
  oggCrc32,
  createOpusHead,
  createOpusTags,
  createOggPage,
  OggDemuxer,
  OggOpusMuxer,
} from '@shared/lib/ogg';
import type { Logger } from '@shared/lib/wata-client/types';

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
// oggCrc32 Tests
// ============================================================================

describe('oggCrc32', () => {
  it('should return 0 for empty input', () => {
    const result = oggCrc32(new Uint8Array([]));
    expect(result).toBe(0);
  });

  it('should compute correct CRC32 for known values', () => {
    // Test with "Hello World"
    const input = new TextEncoder().encode('Hello World');
    const result = oggCrc32(input);
    // Pre-computed CRC32 using Ogg polynomial (0x04C11DB7)
    // Value 835807244 is the correct Ogg CRC32 for "Hello World"
    expect(result).toBe(835807244);
  });

  it('should compute consistent CRC32 for repeated inputs', () => {
    const input = new Uint8Array([1, 2, 3, 4, 5]);
    const result1 = oggCrc32(input);
    const result2 = oggCrc32(input);
    expect(result1).toBe(result2);
  });

  it('should produce different values for different inputs', () => {
    const input1 = new Uint8Array([1, 2, 3]);
    const input2 = new Uint8Array([1, 2, 4]);
    expect(oggCrc32(input1)).not.toBe(oggCrc32(input2));
  });

  it('should handle single byte inputs', () => {
    expect(oggCrc32(new Uint8Array([0]))).toBe(0);
    expect(oggCrc32(new Uint8Array([255]))).not.toBe(0);
  });

  it('should handle larger inputs (1KB)', () => {
    const input = new Uint8Array(1024);
    for (let i = 0; i < 1024; i++) {
      // eslint-disable-next-line no-bitwise
      input[i] = i & 0xff;
    }
    const result = oggCrc32(input);
    expect(result).toBeGreaterThan(0);
     
    expect(result).toBeLessThan(2 ** 32);
  });
});

// ============================================================================
// createOpusHead Tests
// ============================================================================

describe('createOpusHead', () => {
  it('should create header with correct magic "OpusHead"', () => {
    const head = createOpusHead(1, 312, 16000);
    const magic = String.fromCharCode(...head.slice(0, 8));
    expect(magic).toBe('OpusHead');
  });

  it('should set correct version (1)', () => {
    const head = createOpusHead(1, 312, 16000);
    expect(head[8]).toBe(1);
  });

  it('should correctly encode channel count', () => {
    const mono = createOpusHead(1, 312, 16000);
    expect(mono[9]).toBe(1);

    const stereo = createOpusHead(2, 312, 48000);
    expect(stereo[9]).toBe(2);
  });

  it('should correctly encode pre-skip in little-endian', () => {
    const head = createOpusHead(1, 312, 16000);
    const view = new DataView(head.buffer);
    expect(view.getUint16(10, true)).toBe(312);
  });

  it('should correctly encode input sample rate in little-endian', () => {
    const head = createOpusHead(1, 312, 16000);
    const view = new DataView(head.buffer);
    expect(view.getUint32(12, true)).toBe(16000);

    const head48k = createOpusHead(1, 312, 48000);
    expect(new DataView(head48k.buffer).getUint32(12, true)).toBe(48000);
  });

  it('should set output gain to 0 dB', () => {
    const head = createOpusHead(1, 312, 16000);
    const view = new DataView(head.buffer);
    expect(view.getInt16(16, true)).toBe(0);
  });

  it('should set mapping family to 0 (mono/stereo)', () => {
    const head = createOpusHead(1, 312, 16000);
    expect(head[18]).toBe(0);
  });

  it('should create header of exactly 19 bytes', () => {
    const head = createOpusHead(1, 312, 16000);
    expect(head.length).toBe(19);
  });
});

// ============================================================================
// createOpusTags Tests
// ============================================================================

describe('createOpusTags', () => {
  it('should create tags with correct magic "OpusTags"', () => {
    const tags = createOpusTags();
    const magic = String.fromCharCode(...tags.slice(0, 8));
    expect(magic).toBe('OpusTags');
  });

  it('should use default vendor string "libopus"', () => {
    const tags = createOpusTags();
    const view = new DataView(tags.buffer);
    const vendorLength = view.getUint32(8, true);
    expect(vendorLength).toBe(7); // "libopus"
    const vendor = String.fromCharCode(...tags.slice(12, 12 + 7));
    expect(vendor).toBe('libopus');
  });

  it('should use custom vendor string', () => {
    const tags = createOpusTags('wata');
    const view = new DataView(tags.buffer);
    const vendorLength = view.getUint32(8, true);
    expect(vendorLength).toBe(4); // "wata"
    const vendor = String.fromCharCode(...tags.slice(12, 12 + 4));
    expect(vendor).toBe('wata');
  });

  it('should set comment count to 0', () => {
    const tags = createOpusTags('test');
    const view = new DataView(tags.buffer);
    const vendorLength = view.getUint32(8, true);
    // Comment count is after vendor string
    const commentCountOffset = 12 + vendorLength;
    expect(view.getUint32(commentCountOffset, true)).toBe(0);
  });

  it('should calculate correct size for custom vendor', () => {
    const tags = createOpusTags('custom-vendor');
    // 8 (magic) + 4 (vendor length) + 13 (vendor "custom-vendor") + 4 (comment count) = 29
    expect(tags.length).toBe(29);
  });
});

// ============================================================================
// createOggPage Tests
// ============================================================================

describe('createOggPage', () => {
  it('should create page with correct magic "OggS"', () => {
    const page = createOggPage(
      new Uint8Array([1, 2, 3]),
      BigInt(0),
      12345,
      0,
      0,
    );
    const magic = String.fromCharCode(...page.slice(0, 4));
    expect(magic).toBe('OggS');
  });

  it('should set version to 0', () => {
    const page = createOggPage(new Uint8Array([1]), BigInt(0), 1, 0, 0);
    expect(page[4]).toBe(0);
  });

  it('should set flags correctly', () => {
    const pageBos = createOggPage(new Uint8Array([1]), BigInt(0), 1, 0, 0x02);
    expect(pageBos[5]).toBe(0x02);

    const pageEos = createOggPage(new Uint8Array([1]), BigInt(0), 1, 0, 0x04);
    expect(pageEos[5]).toBe(0x04);
  });

  it('should encode granule position in little-endian', () => {
    const granule = BigInt(1234567890);
    const page = createOggPage(new Uint8Array([1]), granule, 1, 0, 0);
    const view = new DataView(page.buffer);
    expect(view.getBigUint64(6, true)).toBe(granule);
  });

  it('should encode serial number', () => {
    const page = createOggPage(new Uint8Array([1]), BigInt(0), 0xDEADBEEF, 0, 0);
    const view = new DataView(page.buffer);
    expect(view.getUint32(14, true)).toBe(0xDEADBEEF);
  });

  it('should encode page sequence number', () => {
    const page = createOggPage(new Uint8Array([1]), BigInt(0), 1, 42, 0);
    const view = new DataView(page.buffer);
    expect(view.getUint32(18, true)).toBe(42);
  });

  it('should calculate and write valid CRC32', () => {
    const payload = new Uint8Array([1, 2, 3, 4, 5]);
    const page = createOggPage(payload, BigInt(0), 1, 0, 0);
    const view = new DataView(page.buffer);

    // Extract CRC from page
    const storedCrc = view.getUint32(22, true);

    // Calculate CRC of page with CRC field set to 0
    const pageForCrc = new Uint8Array(page);
    new DataView(pageForCrc.buffer).setUint32(22, 0, true);
    const calculatedCrc = oggCrc32(pageForCrc);

    expect(storedCrc).toBe(calculatedCrc);
  });

  it('should create correct segment table for small payload (< 255 bytes)', () => {
    const payload = new Uint8Array(100);
    const page = createOggPage(payload, BigInt(0), 1, 0, 0);
    const numSegments = page[26];
    expect(numSegments).toBe(1);
    expect(page[27]).toBe(100);
  });

  it('should create correct segment table for 255 byte payload', () => {
    const payload = new Uint8Array(255);
    const page = createOggPage(payload, BigInt(0), 1, 0, 0);
    const numSegments = page[26];
    expect(numSegments).toBe(2); // [255, 0]
    expect(page[27]).toBe(255);
    expect(page[28]).toBe(0);
  });

  it('should create correct segment table for 256 byte payload', () => {
    const payload = new Uint8Array(256);
    const page = createOggPage(payload, BigInt(0), 1, 0, 0);
    const numSegments = page[26];
    expect(numSegments).toBe(2); // [255, 1]
    expect(page[27]).toBe(255);
    expect(page[28]).toBe(1);
  });

  it('should create correct segment table for large payload (> 510 bytes)', () => {
    const payload = new Uint8Array(600);
    const page = createOggPage(payload, BigInt(0), 1, 0, 0);
    const numSegments = page[26];
    expect(numSegments).toBe(3); // [255, 255, 90]
    expect(page[27]).toBe(255);
    expect(page[28]).toBe(255);
    expect(page[29]).toBe(90);
  });

  it('should place payload after segment table', () => {
    const payload = new Uint8Array([0xAB, 0xCD, 0xEF]);
    const page = createOggPage(payload, BigInt(0), 1, 0, 0);
    const numSegments = page[26];
    const headerSize = 27 + numSegments;
    expect(page[headerSize]).toBe(0xAB);
    expect(page[headerSize + 1]).toBe(0xCD);
    expect(page[headerSize + 2]).toBe(0xEF);
  });
});

// ============================================================================
// OggDemuxer Tests
// ============================================================================

describe('OggDemuxer', () => {
  let logger: TestLogger;

  beforeEach(() => {
    logger = new TestLogger();
  });

  describe('parsePage', () => {
    it('should parse a simple valid Ogg page', () => {
      const demuxer = new OggDemuxer(logger);
      const payload = new Uint8Array([1, 2, 3]);
      const page = createOggPage(payload, BigInt(100), 0x12345678, 5, 0x02);

      const oggBuffer = Buffer.from(page);
      const packets = demuxer.demux(oggBuffer);

      // Since this is just header pages, we get empty audio packets
      expect(packets).toEqual([]);
      // Should have warned about invalid OpusHead/OpusTags
      expect(logger.warnings.length).toBeGreaterThan(0);
    });

    it('should handle BOS flag correctly', () => {
      const demuxer = new OggDemuxer(logger);
      const payload = new Uint8Array([1, 2, 3]);
      const page = createOggPage(payload, BigInt(0), 1, 0, 0x02); // BOS flag

      const oggBuffer = Buffer.from(page);
      demuxer.demux(oggBuffer);

      // Check that page was parsed (would have logged if valid OpusHead)
      expect(logger.warnings.length).toBeGreaterThan(0);
    });

    it('should gracefully handle invalid magic', () => {
      const demuxer = new OggDemuxer(logger);
      // Create a buffer that's at least HEADER_SIZE (27) bytes but has invalid magic
      const invalidBuffer = Buffer.alloc(30, 0xFF);

      const packets = demuxer.demux(invalidBuffer);

      expect(packets).toEqual([]);
      expect(logger.warnings.some((w) => w.includes('Invalid magic at offset'))).toBe(true);
    });

    it('should handle pages with multiple segments', () => {
      const demuxer = new OggDemuxer(logger);
      const payload = new Uint8Array(300); // Creates [255, 45] segment table
      const page = createOggPage(payload, BigInt(0), 1, 0, 0);

      const oggBuffer = Buffer.from(page);
      demuxer.demux(oggBuffer);

      // Should parse without errors (though will warn about invalid headers)
      expect(logger.errors.length).toBe(0);
    });

    it('should skip OpusHead and OpusTags packets', () => {
      const demuxer = new OggDemuxer(logger);

      // Create valid OpusHead page
      const opusHead = createOpusHead(1, 312, 16000);
      const headPage = createOggPage(opusHead, BigInt(0), 1, 0, 0x02);

      // Create valid OpusTags page
      const opusTags = createOpusTags('wata');
      const tagsPage = createOggPage(opusTags, BigInt(0), 1, 1, 0);

      // Create audio data page
      const audioData = new Uint8Array([0xFF, 0xFE, 0xFD]);
      const audioPage = createOggPage(audioData, BigInt(960), 1, 2, 0x04);

      // Concatenate all pages
      const oggBuffer = Buffer.concat([
        Buffer.from(headPage),
        Buffer.from(tagsPage),
        Buffer.from(audioPage),
      ]);

      const packets = demuxer.demux(oggBuffer);

      // Should get 1 audio packet (skipping OpusHead and OpusTags)
      expect(packets.length).toBe(1);
      expect(Buffer.from(packets[0])).toEqual(Buffer.from(audioData));
      expect(logger.logs.some((l) => l.includes('1 audio packets'))).toBe(true);
    });

    it('should handle empty buffer', () => {
      const demuxer = new OggDemuxer(logger);
      const packets = demuxer.demux(Buffer.from([]));

      expect(packets).toEqual([]);
      expect(logger.warnings.length).toBeGreaterThan(0);
    });

    it('should warn about insufficient packets', () => {
      const demuxer = new OggDemuxer(logger);
      const opusHead = createOpusHead(1, 312, 16000);
      const headPage = createOggPage(opusHead, BigInt(0), 1, 0, 0x02);

      const packets = demuxer.demux(Buffer.from(headPage));

      expect(packets).toEqual([]);
      expect(
        logger.warnings.some((w) => w.includes('Expected at least 2 header packets')),
      ).toBe(true);
    });
  });

  describe('extractPackets', () => {
    it('should handle packet spanning multiple segments', () => {
      // Create a 300-byte payload which will have segments [255, 45]
      // But wait - segment 45 < 255, so it marks END of packet
      // So this is still one complete packet
      const demuxer = new OggDemuxer(logger);

      const opusHead = createOpusHead(1, 312, 16000);
      const headPage = createOggPage(opusHead, BigInt(0), 1, 0, 0x02);

      const opusTags = createOpusTags('wata');
      const tagsPage = createOggPage(opusTags, BigInt(0), 1, 1, 0);

      // 300 byte audio packet
      const audioData = new Uint8Array(300);
      for (let i = 0; i < 300; i++) audioData[i] = i;
      const audioPage = createOggPage(audioData, BigInt(960), 1, 2, 0x04);

      const oggBuffer = Buffer.concat([
        Buffer.from(headPage),
        Buffer.from(tagsPage),
        Buffer.from(audioPage),
      ]);

      const packets = demuxer.demux(oggBuffer);

      expect(packets.length).toBe(1);
      expect(packets[0].length).toBe(300);
    });
  });
});

// ============================================================================
// OggOpusMuxer Tests
// ============================================================================

describe('OggOpusMuxer', () => {
  it('should create muxer with default parameters', () => {
    const muxer = new OggOpusMuxer();
    expect(muxer).toBeInstanceOf(OggOpusMuxer);
  });

  it('should create muxer with custom parameters', () => {
    const muxer = new OggOpusMuxer(48000, 2, 0);
    expect(muxer).toBeInstanceOf(OggOpusMuxer);
  });

  describe('writeHeaders', () => {
    it('should create OpusHead page with BOS flag', () => {
      const muxer = new OggOpusMuxer(16000, 1, 312);
      muxer.writeHeaders();

      const result = muxer.finalize();
      const buffer = Buffer.from(result);

      // First page should have BOS flag
      expect(buffer.toString('ascii', 0, 4)).toBe('OggS');
      expect(buffer[5]).toBe(0x02); // BOS flag
    });

    it('should create OpusHead with correct parameters', () => {
      const muxer = new OggOpusMuxer(16000, 1, 312);
      muxer.writeHeaders();

      const result = muxer.finalize();
      const buffer = Buffer.from(result);

      // Skip to OpusHead data (after Ogg page header)
      // Page 0 header: 27 + segments
      const numSegments = buffer[26];
      const headerSize = 27 + numSegments;

      // Check OpusHead magic
      expect(buffer.toString('ascii', headerSize, headerSize + 8)).toBe('OpusHead');
    });

    it('should create OpusTags with vendor string', () => {
      const muxer = new OggOpusMuxer();
      muxer.writeHeaders();

      const result = muxer.finalize();
      const buffer = Buffer.from(result);

      // Find second page
      let offset = 0;
      // Skip first page
      const numSegments = buffer[26];
      offset = 27 + numSegments;
      const firstSegmentTotal = buffer[27];
      offset += firstSegmentTotal;

      // Second page starts at offset
      const secondPageNumSegments = buffer[offset + 26];
      const secondHeaderSize = 27 + secondPageNumSegments;

      // Check OpusTags magic
      expect(
        buffer.toString('ascii', offset + secondHeaderSize, offset + secondHeaderSize + 8),
      ).toBe('OpusTags');
    });
  });

  describe('addPacket', () => {
    it('should add audio packet and increment granule position', () => {
      const muxer = new OggOpusMuxer(16000, 1, 312);
      muxer.writeHeaders();

      const packet = new Uint8Array([1, 2, 3]);
      muxer.addPacket(packet, 320); // 320 samples at 16kHz = 20ms

      const result = muxer.finalize();
      const buffer = Buffer.from(result);

      // Should have 3 pages (OpusHead, OpusTags, audio)
      let pageCount = 0;
      let offset = 0;
      while (offset < buffer.length) {
        expect(buffer.toString('ascii', offset, offset + 4)).toBe('OggS');
        const numSegments = buffer[offset + 26];
        let dataSize = 0;
        for (let i = 0; i < numSegments; i++) {
          dataSize += buffer[offset + 27 + i];
        }
        offset += 27 + numSegments + dataSize;
        pageCount++;
      }

      expect(pageCount).toBe(3);
    });

    it('should scale granule position to 48kHz', () => {
      const muxer = new OggOpusMuxer(16000, 1, 312);
      muxer.writeHeaders();

      const packet = new Uint8Array([1, 2, 3]);
      // 320 samples at 16kHz = 960 samples at 48kHz
      muxer.addPacket(packet, 320);

      const result = muxer.finalize();
      const buffer = Buffer.from(result);

      // Find audio page (third page)
      let offset = 0;
      for (let i = 0; i < 2; i++) {
        // Skip first two pages
        const numSegments = buffer[offset + 26];
        let dataSize = 0;
        for (let j = 0; j < numSegments; j++) {
          dataSize += buffer[offset + 27 + j];
        }
        offset += 27 + numSegments + dataSize;
      }

      // Read granule position from audio page
      const view = new DataView(buffer.buffer, buffer.byteOffset);
      const granule = view.getBigUint64(offset + 6, true);
      expect(granule).toBe(BigInt(960)); // Scaled from 16kHz to 48kHz
    });
  });

  describe('finalize', () => {
    it('should set EOS flag on last page', () => {
      const muxer = new OggOpusMuxer();
      muxer.writeHeaders();

      const packet = new Uint8Array([1, 2, 3]);
      muxer.addPacket(packet, 320);

      const result = muxer.finalize();
      const buffer = Buffer.from(result);

      // Parse all pages to find the last one
      const pages: number[] = [];
      let offset = 0;
      while (offset < buffer.length) {
        if (buffer.toString('ascii', offset, offset + 4) === 'OggS') {
          pages.push(offset);
          const numSegments = buffer[offset + 26];
          let dataSize = 0;
          for (let i = 0; i < numSegments; i++) {
            dataSize += buffer[offset + 27 + i];
          }
          offset += 27 + numSegments + dataSize;
        } else {
          break;
        }
      }

      // Should have 3 pages: OpusHead, OpusTags, audio
      expect(pages.length).toBe(3);
      // Last page (audio) should have EOS flag
      const lastPageOffset = pages[2];
      expect(buffer[lastPageOffset + 5]).toBe(0x04); // EOS flag
    });

    it('should concatenate all pages into single buffer', () => {
      const muxer = new OggOpusMuxer();
      muxer.writeHeaders();

      const packet = new Uint8Array([1, 2, 3]);
      muxer.addPacket(packet, 320);

      const result = muxer.finalize();

      expect(result).toBeInstanceOf(Uint8Array);
      expect(result.length).toBeGreaterThan(0);
    });

    it('should handle finalize with last packet', () => {
      const muxer = new OggOpusMuxer();
      muxer.writeHeaders();

      const packet = new Uint8Array([1, 2, 3]);
      const result = muxer.finalize(packet, 320);

      const buffer = Buffer.from(result);

      // Parse all pages to find the last one
      const pages: number[] = [];
      let offset = 0;
      while (offset < buffer.length) {
        if (buffer.toString('ascii', offset, offset + 4) === 'OggS') {
          pages.push(offset);
          const numSegments = buffer[offset + 26];
          let dataSize = 0;
          for (let i = 0; i < numSegments; i++) {
            dataSize += buffer[offset + 27 + i];
          }
          offset += 27 + numSegments + dataSize;
        } else {
          break;
        }
      }

      // Should have 3 pages: OpusHead, OpusTags, audio
      expect(pages.length).toBe(3);
      // Last page (audio) should have EOS flag
      const lastPageOffset = pages[2];
      expect(buffer[lastPageOffset + 5]).toBe(0x04); // EOS flag
    });
  });

  describe('muxPackets', () => {
    it('should mux single packet', () => {
      const muxer = new OggOpusMuxer();
      const packets = [{ data: new Uint8Array([1, 2, 3]), samples: 320 }];

      const result = muxer.muxPackets(packets);

      expect(result).toBeInstanceOf(Uint8Array);
      expect(result.length).toBeGreaterThan(0);
    });

    it('should mux multiple packets', () => {
      const muxer = new OggOpusMuxer();
      const packets = [
        { data: new Uint8Array([1, 2, 3]), samples: 320 },
        { data: new Uint8Array([4, 5, 6]), samples: 320 },
        { data: new Uint8Array([7, 8, 9]), samples: 320 },
      ];

      const result = muxer.muxPackets(packets);

      expect(result).toBeInstanceOf(Uint8Array);

      // Count pages
      const buffer = Buffer.from(result);
      let pageCount = 0;
      let offset = 0;
      while (offset < buffer.length) {
        if (buffer.toString('ascii', offset, offset + 4) !== 'OggS') break;
        const numSegments = buffer[offset + 26];
        let dataSize = 0;
        for (let i = 0; i < numSegments; i++) {
          dataSize += buffer[offset + 27 + i];
        }
        offset += 27 + numSegments + dataSize;
        pageCount++;
      }

      // Should have: OpusHead, OpusTags, 3 audio pages = 5 pages
      expect(pageCount).toBe(5);
    });

    it('should handle empty packet array', () => {
      const muxer = new OggOpusMuxer();
      const result = muxer.muxPackets([]);

      expect(result).toBeInstanceOf(Uint8Array);
      // Should still have headers
      const buffer = Buffer.from(result);
      expect(buffer.toString('ascii', 0, 4)).toBe('OggS');
    });
  });
});

// ============================================================================
// Integration: Mux/Demux Roundtrip
// ============================================================================

describe('Integration: Mux/Demux Roundtrip', () => {
  let logger: TestLogger;

  beforeEach(() => {
    logger = new TestLogger();
  });

  it('should mux and demux single packet', () => {
    const muxer = new OggOpusMuxer(16000, 1, 312);
    const originalPacket = new Uint8Array([0x12, 0x34, 0x56, 0x78, 0x9A]);

    const oggData = muxer.muxPackets([{ data: originalPacket, samples: 320 }]);

    const demuxer = new OggDemuxer(logger);
    const demuxedPackets = demuxer.demux(Buffer.from(oggData));

    expect(demuxedPackets.length).toBe(1);
    expect(Buffer.from(demuxedPackets[0])).toEqual(Buffer.from(originalPacket));
  });

  it('should mux and demux multiple packets', () => {
    const muxer = new OggOpusMuxer(16000, 1, 312);
    const originalPackets = [
      new Uint8Array([0x11, 0x11, 0x11]),
      new Uint8Array([0x22, 0x22, 0x22]),
      new Uint8Array([0x33, 0x33, 0x33]),
    ];

    const oggData = muxer.muxPackets(
      originalPackets.map((data) => ({ data, samples: 320 })),
    );

    const demuxer = new OggDemuxer(logger);
    const demuxedPackets = demuxer.demux(Buffer.from(oggData));

    expect(demuxedPackets.length).toBe(3);
    for (let i = 0; i < originalPackets.length; i++) {
      expect(Buffer.from(demuxedPackets[i])).toEqual(Buffer.from(originalPackets[i]));
    }
  });

  it('should handle larger packets', () => {
    const muxer = new OggOpusMuxer(16000, 1, 312);

    // Create a 500-byte packet (will create [255, 245] segment table)
    const largePacket = new Uint8Array(500);
    for (let i = 0; i < 500; i++) {
      // eslint-disable-next-line no-bitwise
      largePacket[i] = i & 0xff;
    }

    const oggData = muxer.muxPackets([{ data: largePacket, samples: 320 }]);

    const demuxer = new OggDemuxer(logger);
    const demuxedPackets = demuxer.demux(Buffer.from(oggData));

    expect(demuxedPackets.length).toBe(1);
    expect(demuxedPackets[0].length).toBe(500);
    expect(Buffer.from(demuxedPackets[0])).toEqual(Buffer.from(largePacket));
  });

  it('should handle packets of varying sizes', () => {
    const muxer = new OggOpusMuxer(16000, 1, 312);
    const packets = [
      new Uint8Array(50).fill(0x11),
      new Uint8Array(200).fill(0x22),
      new Uint8Array(300).fill(0x33),
      new Uint8Array(10).fill(0x44),
    ];

    const oggData = muxer.muxPackets(packets.map((data) => ({ data, samples: 160 })));

    const demuxer = new OggDemuxer(logger);
    const demuxedPackets = demuxer.demux(Buffer.from(oggData));

    expect(demuxedPackets.length).toBe(4);
    for (let i = 0; i < packets.length; i++) {
      expect(demuxedPackets[i].length).toBe(packets[i].length);
      expect(Buffer.from(demuxedPackets[i])).toEqual(Buffer.from(packets[i]));
    }
  });

  it('should preserve audio data integrity through roundtrip', () => {
    const muxer = new OggOpusMuxer(48000, 2, 0);

    // Simulate encoded Opus data (realistic-looking bytes)
    const encodedData = new Uint8Array([
      0x00, 0xff, 0x42, 0x12, 0x80, 0x01, 0x00, 0x84, 0xfe, 0x18, 0x20, 0x40, 0x9c,
      0x00, 0x08, 0x00, 0x5c, 0x00, 0x00, 0x22, 0xf4, 0x20, 0x00, 0x00, 0x00,
    ]);

    const oggData = muxer.muxPackets([{ data: encodedData, samples: 960 }]);

    const demuxer = new OggDemuxer(logger);
    const demuxedPackets = demuxer.demux(Buffer.from(oggData));

    expect(demuxedPackets.length).toBe(1);
    expect(Buffer.from(demuxedPackets[0])).toEqual(Buffer.from(encodedData));
  });
});
