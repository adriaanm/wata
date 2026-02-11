/**
 * Type declarations for @evan/wasm opus module
 * Imported from @evan/wasm/target/opus/deno.js
 */

declare module '@evan/wasm/target/opus/deno.js' {
  export interface EncoderOptions {
    channels?: number;
    application?: 'voip' | 'audio' | 'restricted_lowdelay';
    sample_rate?: number;
  }

  export interface DecoderOptions {
    channels?: number;
    sample_rate?: number;
  }

  export class Encoder {
    constructor(options: EncoderOptions);
    readonly channels: number;
    encode(buffer: Int16Array | Float32Array): Uint8Array;
    reset(): void;
    ctl(cmd: number, arg?: number): number | void;
    // Getter/setter properties
    vbr: boolean;
    dtx: boolean;
    bitrate: number;
    lookahead: number;
    lsb_depth: number;
    complexity: number;
    inband_fec: boolean;
    signal: 'auto' | 'voice' | 'music';
    packet_loss: number;
    vbr_constraint: boolean;
    application: 'voip' | 'audio' | 'restricted_lowdelay';
    max_bandwidth:
      | 'auto'
      | 'narrowband'
      | 'mediumband'
      | 'wideband'
      | 'superwideband'
      | 'fullband';
    force_channels: 1 | 2 | 'auto';
    prediction_disabled: boolean;
    expert_frame_duration: number | 'arg';
    in_dtx: boolean;
    sample_rate: number;
    bandwidth:
      | 'auto'
      | 'narrowband'
      | 'mediumband'
      | 'wideband'
      | 'superwideband'
      | 'fullband';
    phase_inversion_disabled: boolean;
    set bitrate(arg: number | 'auto' | 'max'): void;
    set vbr(bool: boolean): void;
    set dtx(bool: boolean): void;
    set signal(arg: 'auto' | 'voice' | 'music'): void;
    set inband_fec(bool: boolean): void;
    set lsb_depth(int: number): void;
    set bandwidth(
      arg:
        | 'auto'
        | 'narrowband'
        | 'mediumband'
        | 'wideband'
        | 'superwideband'
        | 'fullband',
    ): void;
    set complexity(int: number): void;
    set vbr_constraint(bool: boolean): void;
    set application(arg: 'voip' | 'audio' | 'restricted_lowdelay'): void;
    set force_channels(arg: 1 | 2 | 'auto'): void;
    set packet_loss(int: number): void;
    set prediction_disabled(bool: boolean): void;
    set expert_frame_duration(arg: number): void;
    set max_bandwidth(
      arg:
        | 'auto'
        | 'narrowband'
        | 'mediumband'
        | 'wideband'
        | 'superwideband'
        | 'fullband',
    ): void;
    set phase_inversion_disabled(bool: boolean): void;
  }

  export class Decoder {
    constructor(options: DecoderOptions);
    readonly channels: number;
    decode(buffer: Uint8Array): Uint8Array;
    reset(): void;
    ctl(cmd: number, arg?: number): number | void;
    // Getter/setter properties
    gain: number;
    pitch: number | null;
    last_packet_duration: number;
    in_dtx: boolean;
    sample_rate: number;
    bandwidth:
      | 'auto'
      | 'narrowband'
      | 'mediumband'
      | 'wideband'
      | 'superwideband'
      | 'fullband';
    phase_inversion_disabled: boolean;
    set gain(int: number): void;
    set phase_inversion_disabled(bool: boolean): void;
  }

  export const ctl: {
    auto: number;
    bitrate_max: number;
    reset_state: number;
    signal: {
      auto: number;
      voice: number;
      music: number;
    };
    application: {
      voip: number;
      audio: number;
      restricted_lowdelay: number;
    };
    bandwidth: {
      auto: number;
      wideband: number;
      fullband: number;
      narrowband: number;
      mediumband: number;
      superwideband: number;
    };
    framesize: {
      [key: number]: number;
      arg: number;
    };
    set: {
      [key: string]: number;
    };
    get: {
      [key: string]: number;
    };
  };
}
