/**
 * Type declarations for @evan/wasm opus module (Node.js target)
 */
declare module '@evan/wasm/target/opus/node.mjs' {
  export class Encoder {
    constructor({ channels = 2, application, sample_rate = 48000 });

    encode(pcm: Int16Array | Float32Array): Uint8Array;
    reset(): void;
  }

  export class Decoder {
    constructor({ channels = 2, sample_rate = 48000 });
    decode(packet: Uint8Array): Uint8Array;
    reset(): void;
  }
}
