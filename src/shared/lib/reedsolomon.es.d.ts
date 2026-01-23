/**
 * Type declarations for reedsolomon.es library
 */
declare module 'reedsolomon.es/ReedSolomon.js' {
  export class ReedSolomonES {
    static encode(
      u8a: Uint8Array,
      presetName: string,
      errorCorrectionRetio: number,
    ): Uint8Array;

    static decode(
      u8a: Uint8Array,
      presetName: string,
      errorCorrectionRetio: number,
      isSloppy?: boolean,
    ): Uint8Array;

    static encodeRaw(
      i32a: Int32Array,
      errorCorrectionRedundantUnitCount: number,
      primitive: number,
      bitNum: number,
      b: number,
    ): Int32Array;

    static decodeRaw(
      i32a: Int32Array,
      errorCorrectionRedundantUnitCount: number,
      primitive: number,
      bitNum: number,
      b: number,
      isSloppy?: boolean,
    ): Int32Array;

    static copyToI32a(
      u8a: Uint8Array,
      bitNum: number,
      fill?: boolean,
    ): Int32Array;

    static copyToU8a(i32a: Int32Array, bitNum: number): Uint8Array;
  }
}
