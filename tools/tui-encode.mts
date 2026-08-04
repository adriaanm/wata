// M8 chunk 7 — reproduce the TUI's Ogg/Opus container to test the device
// decode path (the chunk-6 "VOICEPLAY FAIL on TUI-sent audio" flag). Uses the
// TUI's OWN encoder (@evan/wasm opus, 16kHz/60ms) + Ogg muxer (one packet/page,
// EOS-carries-audio, random serial) via shared/audio-codec.encodeOggOpus — the
// exact bytes the TUI uploads. Writes a 1.5s 440Hz tone to argv[2].
import { Encoder } from '../node_modules/.pnpm/@evan+wasm@0.0.95/node_modules/@evan/wasm/target/opus/node.mjs';
import { encodeOggOpus } from '../src/shared/lib/audio-codec.ts';
import { writeFileSync } from 'node:fs';

const mkEncoder = (sampleRate: number, channels: number, application: string) =>
  new Encoder({ sample_rate: sampleRate, channels, application } as any) as any;

const SR = 16000;
const secs = 1.5;
const n = Math.floor(SR * secs);
const pcm = new Int16Array(n);
for (let i = 0; i < n; i++) pcm[i] = Math.round(16000 * Math.sin((2 * Math.PI * 440 * i) / SR));

const ogg = encodeOggOpus(pcm, { sampleRate: SR, channels: 1 }, mkEncoder as any);
const out = process.argv[2] || '/tmp/tui-tone.ogg';
writeFileSync(out, ogg);
console.log(`wrote ${ogg.length} bytes to ${out} (TUI container: 16kHz/60ms opus, one packet/page)`);
