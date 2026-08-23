const __root = @This();
pub const __builtin = @import("std").zig.c_translation.builtins;
pub const __helpers = @import("std").zig.c_translation.helpers;

pub const intmax_t = c_longlong;
pub const uintmax_t = c_ulonglong;
pub const int_fast8_t = i8;
pub const int_fast64_t = i64;
pub const int_least8_t = i8;
pub const int_least16_t = i16;
pub const int_least32_t = i32;
pub const int_least64_t = i64;
pub const uint_fast8_t = u8;
pub const uint_fast64_t = u64;
pub const uint_least8_t = u8;
pub const uint_least16_t = u16;
pub const uint_least32_t = u32;
pub const uint_least64_t = u64;
pub const int_fast16_t = i32;
pub const int_fast32_t = i32;
pub const uint_fast16_t = u32;
pub const uint_fast32_t = u32;
pub const opus_int8 = i8;
pub const opus_uint8 = u8;
pub const opus_int16 = i16;
pub const opus_uint16 = u16;
pub const opus_int32 = i32;
pub const opus_uint32 = u32;
pub const opus_int64 = i64;
pub const opus_uint64 = u64;
pub extern fn opus_strerror(@"error": c_int) [*c]const u8;
pub extern fn opus_get_version_string() [*c]const u8;
pub const struct_OpusEncoder = opaque {
    pub const opus_encoder_init = __root.opus_encoder_init;
    pub const opus_encode = __root.opus_encode;
    pub const opus_encode_float = __root.opus_encode_float;
    pub const opus_encoder_destroy = __root.opus_encoder_destroy;
    pub const opus_encoder_ctl = __root.opus_encoder_ctl;
    pub const init = __root.opus_encoder_init;
    pub const encode = __root.opus_encode;
    pub const float = __root.opus_encode_float;
    pub const destroy = __root.opus_encoder_destroy;
    pub const ctl = __root.opus_encoder_ctl;
};
pub const OpusEncoder = struct_OpusEncoder;
pub extern fn opus_encoder_get_size(channels: c_int) c_int;
pub extern fn opus_encoder_create(Fs: opus_int32, channels: c_int, application: c_int, @"error": [*c]c_int) ?*OpusEncoder;
pub extern fn opus_encoder_init(st: ?*OpusEncoder, Fs: opus_int32, channels: c_int, application: c_int) c_int;
pub extern fn opus_encode(st: ?*OpusEncoder, pcm: [*c]const opus_int16, frame_size: c_int, data: [*c]u8, max_data_bytes: opus_int32) opus_int32;
pub extern fn opus_encode_float(st: ?*OpusEncoder, pcm: [*c]const f32, frame_size: c_int, data: [*c]u8, max_data_bytes: opus_int32) opus_int32;
pub extern fn opus_encoder_destroy(st: ?*OpusEncoder) void;
pub extern fn opus_encoder_ctl(st: ?*OpusEncoder, request: c_int, ...) c_int;
pub const struct_OpusDecoder = opaque {
    pub const opus_decoder_init = __root.opus_decoder_init;
    pub const opus_decode = __root.opus_decode;
    pub const opus_decode_float = __root.opus_decode_float;
    pub const opus_decoder_ctl = __root.opus_decoder_ctl;
    pub const opus_decoder_destroy = __root.opus_decoder_destroy;
    pub const opus_decoder_dred_decode = __root.opus_decoder_dred_decode;
    pub const opus_decoder_dred_decode_float = __root.opus_decoder_dred_decode_float;
    pub const opus_decoder_get_nb_samples = __root.opus_decoder_get_nb_samples;
    pub const init = __root.opus_decoder_init;
    pub const decode = __root.opus_decode;
    pub const float = __root.opus_decode_float;
    pub const ctl = __root.opus_decoder_ctl;
    pub const destroy = __root.opus_decoder_destroy;
    pub const samples = __root.opus_decoder_get_nb_samples;
};
pub const OpusDecoder = struct_OpusDecoder;
pub const struct_OpusDREDDecoder = opaque {
    pub const opus_dred_decoder_init = __root.opus_dred_decoder_init;
    pub const opus_dred_decoder_destroy = __root.opus_dred_decoder_destroy;
    pub const opus_dred_decoder_ctl = __root.opus_dred_decoder_ctl;
    pub const opus_dred_parse = __root.opus_dred_parse;
    pub const opus_dred_process = __root.opus_dred_process;
    pub const init = __root.opus_dred_decoder_init;
    pub const destroy = __root.opus_dred_decoder_destroy;
    pub const ctl = __root.opus_dred_decoder_ctl;
    pub const parse = __root.opus_dred_parse;
    pub const process = __root.opus_dred_process;
};
pub const OpusDREDDecoder = struct_OpusDREDDecoder;
pub const struct_OpusDRED = opaque {
    pub const opus_dred_free = __root.opus_dred_free;
    pub const free = __root.opus_dred_free;
};
pub const OpusDRED = struct_OpusDRED;
pub extern fn opus_decoder_get_size(channels: c_int) c_int;
pub extern fn opus_decoder_create(Fs: opus_int32, channels: c_int, @"error": [*c]c_int) ?*OpusDecoder;
pub extern fn opus_decoder_init(st: ?*OpusDecoder, Fs: opus_int32, channels: c_int) c_int;
pub extern fn opus_decode(st: ?*OpusDecoder, data: [*c]const u8, len: opus_int32, pcm: [*c]opus_int16, frame_size: c_int, decode_fec: c_int) c_int;
pub extern fn opus_decode_float(st: ?*OpusDecoder, data: [*c]const u8, len: opus_int32, pcm: [*c]f32, frame_size: c_int, decode_fec: c_int) c_int;
pub extern fn opus_decoder_ctl(st: ?*OpusDecoder, request: c_int, ...) c_int;
pub extern fn opus_decoder_destroy(st: ?*OpusDecoder) void;
pub extern fn opus_dred_decoder_get_size() c_int;
pub extern fn opus_dred_decoder_create(@"error": [*c]c_int) ?*OpusDREDDecoder;
pub extern fn opus_dred_decoder_init(dec: ?*OpusDREDDecoder) c_int;
pub extern fn opus_dred_decoder_destroy(dec: ?*OpusDREDDecoder) void;
pub extern fn opus_dred_decoder_ctl(dred_dec: ?*OpusDREDDecoder, request: c_int, ...) c_int;
pub extern fn opus_dred_get_size() c_int;
pub extern fn opus_dred_alloc(@"error": [*c]c_int) ?*OpusDRED;
pub extern fn opus_dred_free(dec: ?*OpusDRED) void;
pub extern fn opus_dred_parse(dred_dec: ?*OpusDREDDecoder, dred: ?*OpusDRED, data: [*c]const u8, len: opus_int32, max_dred_samples: opus_int32, sampling_rate: opus_int32, dred_end: [*c]c_int, defer_processing: c_int) c_int;
pub extern fn opus_dred_process(dred_dec: ?*OpusDREDDecoder, src: ?*const OpusDRED, dst: ?*OpusDRED) c_int;
pub extern fn opus_decoder_dred_decode(st: ?*OpusDecoder, dred: ?*const OpusDRED, dred_offset: opus_int32, pcm: [*c]opus_int16, frame_size: opus_int32) c_int;
pub extern fn opus_decoder_dred_decode_float(st: ?*OpusDecoder, dred: ?*const OpusDRED, dred_offset: opus_int32, pcm: [*c]f32, frame_size: opus_int32) c_int;
pub extern fn opus_packet_parse(data: [*c]const u8, len: opus_int32, out_toc: [*c]u8, frames: [*c][*c]const u8, size: [*c]opus_int16, payload_offset: [*c]c_int) c_int;
pub extern fn opus_packet_get_bandwidth(data: [*c]const u8) c_int;
pub extern fn opus_packet_get_samples_per_frame(data: [*c]const u8, Fs: opus_int32) c_int;
pub extern fn opus_packet_get_nb_channels(data: [*c]const u8) c_int;
pub extern fn opus_packet_get_nb_frames(packet: [*c]const u8, len: opus_int32) c_int;
pub extern fn opus_packet_get_nb_samples(packet: [*c]const u8, len: opus_int32, Fs: opus_int32) c_int;
pub extern fn opus_packet_has_lbrr(packet: [*c]const u8, len: opus_int32) c_int;
pub extern fn opus_decoder_get_nb_samples(dec: ?*const OpusDecoder, packet: [*c]const u8, len: opus_int32) c_int;
pub extern fn opus_pcm_soft_clip(pcm: [*c]f32, frame_size: c_int, channels: c_int, softclip_mem: [*c]f32) void;
pub const struct_OpusRepacketizer = opaque {
    pub const opus_repacketizer_init = __root.opus_repacketizer_init;
    pub const opus_repacketizer_destroy = __root.opus_repacketizer_destroy;
    pub const opus_repacketizer_cat = __root.opus_repacketizer_cat;
    pub const opus_repacketizer_out_range = __root.opus_repacketizer_out_range;
    pub const opus_repacketizer_get_nb_frames = __root.opus_repacketizer_get_nb_frames;
    pub const opus_repacketizer_out = __root.opus_repacketizer_out;
    pub const init = __root.opus_repacketizer_init;
    pub const destroy = __root.opus_repacketizer_destroy;
    pub const cat = __root.opus_repacketizer_cat;
    pub const range = __root.opus_repacketizer_out_range;
    pub const frames = __root.opus_repacketizer_get_nb_frames;
    pub const out = __root.opus_repacketizer_out;
};
pub const OpusRepacketizer = struct_OpusRepacketizer;
pub extern fn opus_repacketizer_get_size() c_int;
pub extern fn opus_repacketizer_init(rp: ?*OpusRepacketizer) ?*OpusRepacketizer;
pub extern fn opus_repacketizer_create() ?*OpusRepacketizer;
pub extern fn opus_repacketizer_destroy(rp: ?*OpusRepacketizer) void;
pub extern fn opus_repacketizer_cat(rp: ?*OpusRepacketizer, data: [*c]const u8, len: opus_int32) c_int;
pub extern fn opus_repacketizer_out_range(rp: ?*OpusRepacketizer, begin: c_int, end: c_int, data: [*c]u8, maxlen: opus_int32) opus_int32;
pub extern fn opus_repacketizer_get_nb_frames(rp: ?*OpusRepacketizer) c_int;
pub extern fn opus_repacketizer_out(rp: ?*OpusRepacketizer, data: [*c]u8, maxlen: opus_int32) opus_int32;
pub extern fn opus_packet_pad(data: [*c]u8, len: opus_int32, new_len: opus_int32) c_int;
pub extern fn opus_packet_unpad(data: [*c]u8, len: opus_int32) opus_int32;
pub extern fn opus_multistream_packet_pad(data: [*c]u8, len: opus_int32, new_len: opus_int32, nb_streams: c_int) c_int;
pub extern fn opus_multistream_packet_unpad(data: [*c]u8, len: opus_int32, nb_streams: c_int) opus_int32;

pub const __VERSION__ = "Aro aro-zig";
pub const __Aro__ = "";
pub const __STDC__ = @as(c_int, 1);
pub const __STDC_HOSTED__ = @as(c_int, 1);
pub const __STDC_UTF_16__ = @as(c_int, 1);
pub const __STDC_UTF_32__ = @as(c_int, 1);
pub const __STDC_EMBED_NOT_FOUND__ = @as(c_int, 0);
pub const __STDC_EMBED_FOUND__ = @as(c_int, 1);
pub const __STDC_EMBED_EMPTY__ = @as(c_int, 2);
pub const __STDC_VERSION__ = @as(c_long, 201710);
pub const __GNUC__ = @as(c_int, 7);
pub const __GNUC_MINOR__ = @as(c_int, 1);
pub const __GNUC_PATCHLEVEL__ = @as(c_int, 0);
pub const __ARO_EMULATE_CLANG__ = @as(c_int, 1);
pub const __ARO_EMULATE_GCC__ = @as(c_int, 2);
pub const __ARO_EMULATE_MSVC__ = @as(c_int, 3);
pub const __ARO_EMULATE__ = __ARO_EMULATE_GCC__;
pub inline fn __building_module(x: anytype) @TypeOf(@as(c_int, 0)) {
    _ = &x;
    return @as(c_int, 0);
}
pub const __OPTIMIZE__ = @as(c_int, 1);
pub const linux = @as(c_int, 1);
pub const __linux = @as(c_int, 1);
pub const __linux__ = @as(c_int, 1);
pub const unix = @as(c_int, 1);
pub const __unix = @as(c_int, 1);
pub const __unix__ = @as(c_int, 1);
pub const __arm__ = @as(c_int, 1);
pub const __arm = @as(c_int, 1);
pub const _ILP32 = @as(c_int, 1);
pub const __ILP32__ = @as(c_int, 1);
pub const __ORDER_LITTLE_ENDIAN__ = @as(c_int, 1234);
pub const __ORDER_BIG_ENDIAN__ = @as(c_int, 4321);
pub const __ORDER_PDP_ENDIAN__ = @as(c_int, 3412);
pub const __BYTE_ORDER__ = __ORDER_LITTLE_ENDIAN__;
pub const __LITTLE_ENDIAN__ = @as(c_int, 1);
pub const __ELF__ = @as(c_int, 1);
pub const __ATOMIC_RELAXED = @as(c_int, 0);
pub const __ATOMIC_CONSUME = @as(c_int, 1);
pub const __ATOMIC_ACQUIRE = @as(c_int, 2);
pub const __ATOMIC_RELEASE = @as(c_int, 3);
pub const __ATOMIC_ACQ_REL = @as(c_int, 4);
pub const __ATOMIC_SEQ_CST = @as(c_int, 5);
pub const __ATOMIC_BOOL_LOCK_FREE = @as(c_int, 1);
pub const __ATOMIC_CHAR_LOCK_FREE = @as(c_int, 1);
pub const __ATOMIC_CHAR16_T_LOCK_FREE = @as(c_int, 1);
pub const __ATOMIC_CHAR32_T_LOCK_FREE = @as(c_int, 1);
pub const __ATOMIC_WCHAR_T_LOCK_FREE = @as(c_int, 1);
pub const __ATOMIC_SHORT_LOCK_FREE = @as(c_int, 1);
pub const __ATOMIC_INT_LOCK_FREE = @as(c_int, 1);
pub const __ATOMIC_LONG_LOCK_FREE = @as(c_int, 1);
pub const __ATOMIC_LLONG_LOCK_FREE = @as(c_int, 1);
pub const __ATOMIC_POINTER_LOCK_FREE = @as(c_int, 1);
pub const __CHAR_UNSIGNED__ = @as(c_int, 1);
pub const __CHAR_BIT__ = @as(c_int, 8);
pub const __BOOL_WIDTH__ = @as(c_int, 8);
pub const __SCHAR_MAX__ = @as(c_int, 127);
pub const __SCHAR_WIDTH__ = @as(c_int, 8);
pub const __SHRT_MAX__ = @as(c_int, 32767);
pub const __SHRT_WIDTH__ = @as(c_int, 16);
pub const __INT_MAX__ = __helpers.promoteIntLiteral(c_int, 2147483647, .decimal);
pub const __INT_WIDTH__ = @as(c_int, 32);
pub const __LONG_MAX__ = @as(c_long, 2147483647);
pub const __LONG_WIDTH__ = @as(c_int, 32);
pub const __LONG_LONG_MAX__ = @as(c_longlong, 9223372036854775807);
pub const __LONG_LONG_WIDTH__ = @as(c_int, 64);
pub const __WCHAR_MAX__ = __helpers.promoteIntLiteral(c_uint, 4294967295, .decimal);
pub const __WCHAR_WIDTH__ = @as(c_int, 32);
pub const __INTMAX_MAX__ = @as(c_longlong, 9223372036854775807);
pub const __INTMAX_WIDTH__ = @as(c_int, 64);
pub const __SIZE_MAX__ = __helpers.promoteIntLiteral(c_uint, 4294967295, .decimal);
pub const __SIZE_WIDTH__ = @as(c_int, 32);
pub const __UINTMAX_MAX__ = @as(c_ulonglong, 18446744073709551615);
pub const __UINTMAX_WIDTH__ = @as(c_int, 64);
pub const __PTRDIFF_MAX__ = __helpers.promoteIntLiteral(c_int, 2147483647, .decimal);
pub const __PTRDIFF_WIDTH__ = @as(c_int, 32);
pub const __INTPTR_MAX__ = @as(c_long, 2147483647);
pub const __INTPTR_WIDTH__ = @as(c_int, 32);
pub const __UINTPTR_MAX__ = @as(c_ulong, 4294967295);
pub const __UINTPTR_WIDTH__ = @as(c_int, 32);
pub const __SIG_ATOMIC_MAX__ = __helpers.promoteIntLiteral(c_int, 2147483647, .decimal);
pub const __SIG_ATOMIC_WIDTH__ = @as(c_int, 32);
pub const __BITINT_MAXWIDTH__ = __helpers.promoteIntLiteral(c_int, 65535, .decimal);
pub const __SIZEOF_FLOAT__ = @as(c_int, 4);
pub const __SIZEOF_DOUBLE__ = @as(c_int, 8);
pub const __SIZEOF_LONG_DOUBLE__ = @as(c_int, 8);
pub const __SIZEOF_SHORT__ = @as(c_int, 2);
pub const __SIZEOF_INT__ = @as(c_int, 4);
pub const __SIZEOF_LONG__ = @as(c_int, 4);
pub const __SIZEOF_LONG_LONG__ = @as(c_int, 8);
pub const __SIZEOF_POINTER__ = @as(c_int, 4);
pub const __SIZEOF_PTRDIFF_T__ = @as(c_int, 4);
pub const __SIZEOF_SIZE_T__ = @as(c_int, 4);
pub const __SIZEOF_WCHAR_T__ = @as(c_int, 4);
pub const __INTPTR_TYPE__ = c_long;
pub const __UINTPTR_TYPE__ = c_ulong;
pub const __INTMAX_TYPE__ = c_longlong;
pub const __INTMAX_C_SUFFIX__ = @compileError("unable to translate macro: undefined identifier `L`"); // <builtin>:96:9
pub const __UINTMAX_TYPE__ = c_ulonglong;
pub const __UINTMAX_C_SUFFIX__ = @compileError("unable to translate macro: undefined identifier `UL`"); // <builtin>:98:9
pub const __PTRDIFF_TYPE__ = c_int;
pub const __SIZE_TYPE__ = c_uint;
pub const __WCHAR_TYPE__ = c_uint;
pub const __CHAR16_TYPE__ = c_ushort;
pub const __CHAR32_TYPE__ = c_uint;
pub const __INT8_TYPE__ = i8;
pub const __INT8_FMTd__ = "hhd";
pub const __INT8_FMTi__ = "hhi";
pub const __INT8_C_SUFFIX__ = "";
pub const __INT16_TYPE__ = c_short;
pub const __INT16_FMTd__ = "hd";
pub const __INT16_FMTi__ = "hi";
pub const __INT16_C_SUFFIX__ = "";
pub const __INT32_TYPE__ = c_int;
pub const __INT32_FMTd__ = "d";
pub const __INT32_FMTi__ = "i";
pub const __INT32_C_SUFFIX__ = "";
pub const __INT64_TYPE__ = c_longlong;
pub const __INT64_FMTd__ = "lld";
pub const __INT64_FMTi__ = "lli";
pub const __INT64_C_SUFFIX__ = @compileError("unable to translate macro: undefined identifier `LL`"); // <builtin>:119:9
pub const __UINT8_TYPE__ = u8;
pub const __UINT8_FMTo__ = "hho";
pub const __UINT8_FMTu__ = "hhu";
pub const __UINT8_FMTx__ = "hhx";
pub const __UINT8_FMTX__ = "hhX";
pub const __UINT8_C_SUFFIX__ = "";
pub const __UINT8_MAX__ = @as(c_int, 255);
pub const __INT8_MAX__ = @as(c_int, 127);
pub const __UINT16_TYPE__ = c_ushort;
pub const __UINT16_FMTo__ = "ho";
pub const __UINT16_FMTu__ = "hu";
pub const __UINT16_FMTx__ = "hx";
pub const __UINT16_FMTX__ = "hX";
pub const __UINT16_C_SUFFIX__ = "";
pub const __UINT16_MAX__ = __helpers.promoteIntLiteral(c_int, 65535, .decimal);
pub const __INT16_MAX__ = @as(c_int, 32767);
pub const __UINT32_TYPE__ = c_uint;
pub const __UINT32_FMTo__ = "o";
pub const __UINT32_FMTu__ = "u";
pub const __UINT32_FMTx__ = "x";
pub const __UINT32_FMTX__ = "X";
pub const __UINT32_C_SUFFIX__ = @compileError("unable to translate macro: undefined identifier `U`"); // <builtin>:141:9
pub const __UINT32_MAX__ = __helpers.promoteIntLiteral(c_uint, 4294967295, .decimal);
pub const __INT32_MAX__ = __helpers.promoteIntLiteral(c_int, 2147483647, .decimal);
pub const __UINT64_TYPE__ = c_ulonglong;
pub const __UINT64_FMTo__ = "llo";
pub const __UINT64_FMTu__ = "llu";
pub const __UINT64_FMTx__ = "llx";
pub const __UINT64_FMTX__ = "llX";
pub const __UINT64_C_SUFFIX__ = @compileError("unable to translate macro: undefined identifier `ULL`"); // <builtin>:149:9
pub const __UINT64_MAX__ = @as(c_ulonglong, 18446744073709551615);
pub const __INT64_MAX__ = @as(c_longlong, 9223372036854775807);
pub const __INT_LEAST8_TYPE__ = i8;
pub const __INT_LEAST8_MAX__ = @as(c_int, 127);
pub const __INT_LEAST8_WIDTH__ = @as(c_int, 8);
pub const INT_LEAST8_FMTd__ = "hhd";
pub const INT_LEAST8_FMTi__ = "hhi";
pub const __UINT_LEAST8_TYPE__ = u8;
pub const __UINT_LEAST8_MAX__ = @as(c_int, 255);
pub const UINT_LEAST8_FMTo__ = "hho";
pub const UINT_LEAST8_FMTu__ = "hhu";
pub const UINT_LEAST8_FMTx__ = "hhx";
pub const UINT_LEAST8_FMTX__ = "hhX";
pub const __INT_FAST8_TYPE__ = i8;
pub const __INT_FAST8_MAX__ = @as(c_int, 127);
pub const __INT_FAST8_WIDTH__ = @as(c_int, 8);
pub const INT_FAST8_FMTd__ = "hhd";
pub const INT_FAST8_FMTi__ = "hhi";
pub const __UINT_FAST8_TYPE__ = u8;
pub const __UINT_FAST8_MAX__ = @as(c_int, 255);
pub const UINT_FAST8_FMTo__ = "hho";
pub const UINT_FAST8_FMTu__ = "hhu";
pub const UINT_FAST8_FMTx__ = "hhx";
pub const UINT_FAST8_FMTX__ = "hhX";
pub const __INT_LEAST16_TYPE__ = c_short;
pub const __INT_LEAST16_MAX__ = @as(c_int, 32767);
pub const __INT_LEAST16_WIDTH__ = @as(c_int, 16);
pub const INT_LEAST16_FMTd__ = "hd";
pub const INT_LEAST16_FMTi__ = "hi";
pub const __UINT_LEAST16_TYPE__ = c_ushort;
pub const __UINT_LEAST16_MAX__ = __helpers.promoteIntLiteral(c_int, 65535, .decimal);
pub const UINT_LEAST16_FMTo__ = "ho";
pub const UINT_LEAST16_FMTu__ = "hu";
pub const UINT_LEAST16_FMTx__ = "hx";
pub const UINT_LEAST16_FMTX__ = "hX";
pub const __INT_FAST16_TYPE__ = c_short;
pub const __INT_FAST16_MAX__ = @as(c_int, 32767);
pub const __INT_FAST16_WIDTH__ = @as(c_int, 16);
pub const INT_FAST16_FMTd__ = "hd";
pub const INT_FAST16_FMTi__ = "hi";
pub const __UINT_FAST16_TYPE__ = c_ushort;
pub const __UINT_FAST16_MAX__ = __helpers.promoteIntLiteral(c_int, 65535, .decimal);
pub const UINT_FAST16_FMTo__ = "ho";
pub const UINT_FAST16_FMTu__ = "hu";
pub const UINT_FAST16_FMTx__ = "hx";
pub const UINT_FAST16_FMTX__ = "hX";
pub const __INT_LEAST32_TYPE__ = c_int;
pub const __INT_LEAST32_MAX__ = __helpers.promoteIntLiteral(c_int, 2147483647, .decimal);
pub const __INT_LEAST32_WIDTH__ = @as(c_int, 32);
pub const INT_LEAST32_FMTd__ = "d";
pub const INT_LEAST32_FMTi__ = "i";
pub const __UINT_LEAST32_TYPE__ = c_uint;
pub const __UINT_LEAST32_MAX__ = __helpers.promoteIntLiteral(c_uint, 4294967295, .decimal);
pub const UINT_LEAST32_FMTo__ = "o";
pub const UINT_LEAST32_FMTu__ = "u";
pub const UINT_LEAST32_FMTx__ = "x";
pub const UINT_LEAST32_FMTX__ = "X";
pub const __INT_FAST32_TYPE__ = c_int;
pub const __INT_FAST32_MAX__ = __helpers.promoteIntLiteral(c_int, 2147483647, .decimal);
pub const __INT_FAST32_WIDTH__ = @as(c_int, 32);
pub const INT_FAST32_FMTd__ = "d";
pub const INT_FAST32_FMTi__ = "i";
pub const __UINT_FAST32_TYPE__ = c_uint;
pub const __UINT_FAST32_MAX__ = __helpers.promoteIntLiteral(c_uint, 4294967295, .decimal);
pub const UINT_FAST32_FMTo__ = "o";
pub const UINT_FAST32_FMTu__ = "u";
pub const UINT_FAST32_FMTx__ = "x";
pub const UINT_FAST32_FMTX__ = "X";
pub const __INT_LEAST64_TYPE__ = c_longlong;
pub const __INT_LEAST64_MAX__ = @as(c_longlong, 9223372036854775807);
pub const __INT_LEAST64_WIDTH__ = @as(c_int, 64);
pub const INT_LEAST64_FMTd__ = "lld";
pub const INT_LEAST64_FMTi__ = "lli";
pub const __UINT_LEAST64_TYPE__ = c_ulonglong;
pub const __UINT_LEAST64_MAX__ = @as(c_ulonglong, 18446744073709551615);
pub const UINT_LEAST64_FMTo__ = "llo";
pub const UINT_LEAST64_FMTu__ = "llu";
pub const UINT_LEAST64_FMTx__ = "llx";
pub const UINT_LEAST64_FMTX__ = "llX";
pub const __INT_FAST64_TYPE__ = c_longlong;
pub const __INT_FAST64_MAX__ = @as(c_longlong, 9223372036854775807);
pub const __INT_FAST64_WIDTH__ = @as(c_int, 64);
pub const INT_FAST64_FMTd__ = "lld";
pub const INT_FAST64_FMTi__ = "lli";
pub const __UINT_FAST64_TYPE__ = c_ulonglong;
pub const __UINT_FAST64_MAX__ = @as(c_ulonglong, 18446744073709551615);
pub const UINT_FAST64_FMTo__ = "llo";
pub const UINT_FAST64_FMTu__ = "llu";
pub const UINT_FAST64_FMTx__ = "llx";
pub const UINT_FAST64_FMTX__ = "llX";
pub const __FLT16_DENORM_MIN__ = @as(f16, 5.9604644775390625e-8);
pub const __FLT16_HAS_DENORM__ = "";
pub const __FLT16_DIG__ = @as(c_int, 3);
pub const __FLT16_DECIMAL_DIG__ = @as(c_int, 5);
pub const __FLT16_EPSILON__ = @as(f16, 9.765625e-4);
pub const __FLT16_HAS_INFINITY__ = "";
pub const __FLT16_HAS_QUIET_NAN__ = "";
pub const __FLT16_MANT_DIG__ = @as(c_int, 11);
pub const __FLT16_MAX_10_EXP__ = @as(c_int, 4);
pub const __FLT16_MAX_EXP__ = @as(c_int, 16);
pub const __FLT16_MAX__ = @as(f16, 6.5504e+4);
pub const __FLT16_MIN_10_EXP__ = -@as(c_int, 4);
pub const __FLT16_MIN_EXP__ = -@as(c_int, 13);
pub const __FLT16_MIN__ = @as(f16, 6.103515625e-5);
pub const __FLT_DENORM_MIN__ = @as(f32, 1.40129846e-45);
pub const __FLT_HAS_DENORM__ = "";
pub const __FLT_DIG__ = @as(c_int, 6);
pub const __FLT_DECIMAL_DIG__ = @as(c_int, 9);
pub const __FLT_EPSILON__ = @as(f32, 1.19209290e-7);
pub const __FLT_HAS_INFINITY__ = "";
pub const __FLT_HAS_QUIET_NAN__ = "";
pub const __FLT_MANT_DIG__ = @as(c_int, 24);
pub const __FLT_MAX_10_EXP__ = @as(c_int, 38);
pub const __FLT_MAX_EXP__ = @as(c_int, 128);
pub const __FLT_MAX__ = @as(f32, 3.40282347e+38);
pub const __FLT_MIN_10_EXP__ = -@as(c_int, 37);
pub const __FLT_MIN_EXP__ = -@as(c_int, 125);
pub const __FLT_MIN__ = @as(f32, 1.17549435e-38);
pub const __DBL_DENORM_MIN__ = @as(f64, 4.9406564584124654e-324);
pub const __DBL_HAS_DENORM__ = "";
pub const __DBL_DIG__ = @as(c_int, 15);
pub const __DBL_DECIMAL_DIG__ = @as(c_int, 17);
pub const __DBL_EPSILON__ = @as(f64, 2.2204460492503131e-16);
pub const __DBL_HAS_INFINITY__ = "";
pub const __DBL_HAS_QUIET_NAN__ = "";
pub const __DBL_MANT_DIG__ = @as(c_int, 53);
pub const __DBL_MAX_10_EXP__ = @as(c_int, 308);
pub const __DBL_MAX_EXP__ = @as(c_int, 1024);
pub const __DBL_MAX__ = @as(f64, 1.7976931348623157e+308);
pub const __DBL_MIN_10_EXP__ = -@as(c_int, 307);
pub const __DBL_MIN_EXP__ = -@as(c_int, 1021);
pub const __DBL_MIN__ = @as(f64, 2.2250738585072014e-308);
pub const __LDBL_DENORM_MIN__ = @as(c_longdouble, 4.9406564584124654e-324);
pub const __LDBL_HAS_DENORM__ = "";
pub const __LDBL_DIG__ = @as(c_int, 15);
pub const __LDBL_DECIMAL_DIG__ = @as(c_int, 17);
pub const __LDBL_EPSILON__ = @as(c_longdouble, 2.2204460492503131e-16);
pub const __LDBL_HAS_INFINITY__ = "";
pub const __LDBL_HAS_QUIET_NAN__ = "";
pub const __LDBL_MANT_DIG__ = @as(c_int, 53);
pub const __LDBL_MAX_10_EXP__ = @as(c_int, 308);
pub const __LDBL_MAX_EXP__ = @as(c_int, 1024);
pub const __LDBL_MAX__ = @as(c_longdouble, 1.7976931348623157e+308);
pub const __LDBL_MIN_10_EXP__ = -@as(c_int, 307);
pub const __LDBL_MIN_EXP__ = -@as(c_int, 1021);
pub const __LDBL_MIN__ = @as(c_longdouble, 2.2250738585072014e-308);
pub const __FLT_EVAL_METHOD__ = @as(c_int, 0);
pub const __FLT_RADIX__ = @as(c_int, 2);
pub const __DECIMAL_DIG__ = __LDBL_DECIMAL_DIG__;
pub const _FORTIFY_SOURCE = @as(c_int, 2);
pub const OPUS_H = "";
pub const OPUS_TYPES_H = "";
pub const opus_int = c_int;
pub const opus_uint = c_uint;
pub const __CLANG_STDINT_H = "";
pub const _STDINT_H = "";
pub const __NEED_int8_t = "";
pub const __NEED_int16_t = "";
pub const __NEED_int32_t = "";
pub const __NEED_int64_t = "";
pub const __NEED_uint8_t = "";
pub const __NEED_uint16_t = "";
pub const __NEED_uint32_t = "";
pub const __NEED_uint64_t = "";
pub const __NEED_intptr_t = "";
pub const __NEED_uintptr_t = "";
pub const __NEED_intmax_t = "";
pub const __NEED_uintmax_t = "";
pub const _REDIR_TIME64 = @as(c_int, 1);
pub const __BYTE_ORDER = @as(c_int, 1234);
pub const __LONG_MAX = @as(c_long, 0x7fffffff);
pub const __LITTLE_ENDIAN = @as(c_int, 1234);
pub const __BIG_ENDIAN = @as(c_int, 4321);
pub const __USE_TIME_BITS64 = @as(c_int, 1);
pub const __DEFINED_uintptr_t = "";
pub const __DEFINED_intptr_t = "";
pub const __DEFINED_int8_t = "";
pub const __DEFINED_int16_t = "";
pub const __DEFINED_int32_t = "";
pub const __DEFINED_int64_t = "";
pub const __DEFINED_intmax_t = "";
pub const __DEFINED_uint8_t = "";
pub const __DEFINED_uint16_t = "";
pub const __DEFINED_uint32_t = "";
pub const __DEFINED_uint64_t = "";
pub const __DEFINED_uintmax_t = "";
pub const INT8_MIN = -@as(c_int, 1) - @as(c_int, 0x7f);
pub const INT16_MIN = -@as(c_int, 1) - @as(c_int, 0x7fff);
pub const INT32_MIN = -@as(c_int, 1) - __helpers.promoteIntLiteral(c_int, 0x7fffffff, .hex);
pub const INT64_MIN = -@as(c_int, 1) - __helpers.promoteIntLiteral(c_int, 0x7fffffffffffffff, .hex);
pub const INT8_MAX = @as(c_int, 0x7f);
pub const INT16_MAX = @as(c_int, 0x7fff);
pub const INT32_MAX = __helpers.promoteIntLiteral(c_int, 0x7fffffff, .hex);
pub const INT64_MAX = __helpers.promoteIntLiteral(c_int, 0x7fffffffffffffff, .hex);
pub const UINT8_MAX = @as(c_int, 0xff);
pub const UINT16_MAX = __helpers.promoteIntLiteral(c_int, 0xffff, .hex);
pub const UINT32_MAX = __helpers.promoteIntLiteral(c_uint, 0xffffffff, .hex);
pub const UINT64_MAX = __helpers.promoteIntLiteral(c_uint, 0xffffffffffffffff, .hex);
pub const INT_FAST8_MIN = INT8_MIN;
pub const INT_FAST64_MIN = INT64_MIN;
pub const INT_LEAST8_MIN = INT8_MIN;
pub const INT_LEAST16_MIN = INT16_MIN;
pub const INT_LEAST32_MIN = INT32_MIN;
pub const INT_LEAST64_MIN = INT64_MIN;
pub const INT_FAST8_MAX = INT8_MAX;
pub const INT_FAST64_MAX = INT64_MAX;
pub const INT_LEAST8_MAX = INT8_MAX;
pub const INT_LEAST16_MAX = INT16_MAX;
pub const INT_LEAST32_MAX = INT32_MAX;
pub const INT_LEAST64_MAX = INT64_MAX;
pub const UINT_FAST8_MAX = UINT8_MAX;
pub const UINT_FAST64_MAX = UINT64_MAX;
pub const UINT_LEAST8_MAX = UINT8_MAX;
pub const UINT_LEAST16_MAX = UINT16_MAX;
pub const UINT_LEAST32_MAX = UINT32_MAX;
pub const UINT_LEAST64_MAX = UINT64_MAX;
pub const INTMAX_MIN = INT64_MIN;
pub const INTMAX_MAX = INT64_MAX;
pub const UINTMAX_MAX = UINT64_MAX;
pub const WINT_MIN = @as(c_uint, 0);
pub const WINT_MAX = UINT32_MAX;
pub const WCHAR_MAX = __helpers.promoteIntLiteral(c_uint, 0xffffffff, .hex) + '\x00';
pub const WCHAR_MIN = @as(c_int, 0) + '\x00';
pub const SIG_ATOMIC_MIN = INT32_MIN;
pub const SIG_ATOMIC_MAX = INT32_MAX;
pub const INT_FAST16_MIN = INT32_MIN;
pub const INT_FAST32_MIN = INT32_MIN;
pub const INT_FAST16_MAX = INT32_MAX;
pub const INT_FAST32_MAX = INT32_MAX;
pub const UINT_FAST16_MAX = UINT32_MAX;
pub const UINT_FAST32_MAX = UINT32_MAX;
pub const INTPTR_MIN = INT32_MIN;
pub const INTPTR_MAX = INT32_MAX;
pub const UINTPTR_MAX = UINT32_MAX;
pub const PTRDIFF_MIN = INT32_MIN;
pub const PTRDIFF_MAX = INT32_MAX;
pub const SIZE_MAX = UINT32_MAX;
pub inline fn INT8_C(c: anytype) @TypeOf(c) {
    _ = &c;
    return c;
}
pub inline fn INT16_C(c: anytype) @TypeOf(c) {
    _ = &c;
    return c;
}
pub inline fn INT32_C(c: anytype) @TypeOf(c) {
    _ = &c;
    return c;
}
pub inline fn UINT8_C(c: anytype) @TypeOf(c) {
    _ = &c;
    return c;
}
pub inline fn UINT16_C(c: anytype) @TypeOf(c) {
    _ = &c;
    return c;
}
pub const UINT32_C = __helpers.U_SUFFIX;
pub const INT64_C = __helpers.LL_SUFFIX;
pub const UINT64_C = __helpers.ULL_SUFFIX;
pub const INTMAX_C = __helpers.LL_SUFFIX;
pub const UINTMAX_C = __helpers.ULL_SUFFIX;
pub const OPUS_DEFINES_H = "";
pub const OPUS_OK = @as(c_int, 0);
pub const OPUS_BAD_ARG = -@as(c_int, 1);
pub const OPUS_BUFFER_TOO_SMALL = -@as(c_int, 2);
pub const OPUS_INTERNAL_ERROR = -@as(c_int, 3);
pub const OPUS_INVALID_PACKET = -@as(c_int, 4);
pub const OPUS_UNIMPLEMENTED = -@as(c_int, 5);
pub const OPUS_INVALID_STATE = -@as(c_int, 6);
pub const OPUS_ALLOC_FAIL = -@as(c_int, 7);
pub const OPUS_EXPORT = "";
pub inline fn OPUS_GNUC_PREREQ(_maj: anytype, _min: anytype) @TypeOf(((__GNUC__ << @as(c_int, 16)) + __GNUC_MINOR__) >= ((_maj << @as(c_int, 16)) + _min)) {
    _ = &_maj;
    _ = &_min;
    return ((__GNUC__ << @as(c_int, 16)) + __GNUC_MINOR__) >= ((_maj << @as(c_int, 16)) + _min);
}
pub const OPUS_RESTRICT = @compileError("unable to translate C expr: unexpected token 'restrict'"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAADWXwF69u4_iufnV_gwfb3M2ATbbZF-yGFRrEFH/include/opus_defines.h:98:10
pub const OPUS_INLINE = @compileError("unable to translate C expr: unexpected token 'inline'"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAADWXwF69u4_iufnV_gwfb3M2ATbbZF-yGFRrEFH/include/opus_defines.h:110:10
pub const OPUS_WARN_UNUSED_RESULT = @compileError("unable to translate macro: undefined identifier `__warn_unused_result__`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAADWXwF69u4_iufnV_gwfb3M2ATbbZF-yGFRrEFH/include/opus_defines.h:117:10
pub const OPUS_ARG_NONNULL = @compileError("unable to translate macro: undefined identifier `__nonnull__`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAADWXwF69u4_iufnV_gwfb3M2ATbbZF-yGFRrEFH/include/opus_defines.h:122:10
pub const OPUS_SET_APPLICATION_REQUEST = @as(c_int, 4000);
pub const OPUS_GET_APPLICATION_REQUEST = @as(c_int, 4001);
pub const OPUS_SET_BITRATE_REQUEST = @as(c_int, 4002);
pub const OPUS_GET_BITRATE_REQUEST = @as(c_int, 4003);
pub const OPUS_SET_MAX_BANDWIDTH_REQUEST = @as(c_int, 4004);
pub const OPUS_GET_MAX_BANDWIDTH_REQUEST = @as(c_int, 4005);
pub const OPUS_SET_VBR_REQUEST = @as(c_int, 4006);
pub const OPUS_GET_VBR_REQUEST = @as(c_int, 4007);
pub const OPUS_SET_BANDWIDTH_REQUEST = @as(c_int, 4008);
pub const OPUS_GET_BANDWIDTH_REQUEST = @as(c_int, 4009);
pub const OPUS_SET_COMPLEXITY_REQUEST = @as(c_int, 4010);
pub const OPUS_GET_COMPLEXITY_REQUEST = @as(c_int, 4011);
pub const OPUS_SET_INBAND_FEC_REQUEST = @as(c_int, 4012);
pub const OPUS_GET_INBAND_FEC_REQUEST = @as(c_int, 4013);
pub const OPUS_SET_PACKET_LOSS_PERC_REQUEST = @as(c_int, 4014);
pub const OPUS_GET_PACKET_LOSS_PERC_REQUEST = @as(c_int, 4015);
pub const OPUS_SET_DTX_REQUEST = @as(c_int, 4016);
pub const OPUS_GET_DTX_REQUEST = @as(c_int, 4017);
pub const OPUS_SET_VBR_CONSTRAINT_REQUEST = @as(c_int, 4020);
pub const OPUS_GET_VBR_CONSTRAINT_REQUEST = @as(c_int, 4021);
pub const OPUS_SET_FORCE_CHANNELS_REQUEST = @as(c_int, 4022);
pub const OPUS_GET_FORCE_CHANNELS_REQUEST = @as(c_int, 4023);
pub const OPUS_SET_SIGNAL_REQUEST = @as(c_int, 4024);
pub const OPUS_GET_SIGNAL_REQUEST = @as(c_int, 4025);
pub const OPUS_GET_LOOKAHEAD_REQUEST = @as(c_int, 4027);
pub const OPUS_GET_SAMPLE_RATE_REQUEST = @as(c_int, 4029);
pub const OPUS_GET_FINAL_RANGE_REQUEST = @as(c_int, 4031);
pub const OPUS_GET_PITCH_REQUEST = @as(c_int, 4033);
pub const OPUS_SET_GAIN_REQUEST = @as(c_int, 4034);
pub const OPUS_GET_GAIN_REQUEST = @as(c_int, 4045);
pub const OPUS_SET_LSB_DEPTH_REQUEST = @as(c_int, 4036);
pub const OPUS_GET_LSB_DEPTH_REQUEST = @as(c_int, 4037);
pub const OPUS_GET_LAST_PACKET_DURATION_REQUEST = @as(c_int, 4039);
pub const OPUS_SET_EXPERT_FRAME_DURATION_REQUEST = @as(c_int, 4040);
pub const OPUS_GET_EXPERT_FRAME_DURATION_REQUEST = @as(c_int, 4041);
pub const OPUS_SET_PREDICTION_DISABLED_REQUEST = @as(c_int, 4042);
pub const OPUS_GET_PREDICTION_DISABLED_REQUEST = @as(c_int, 4043);
pub const OPUS_SET_PHASE_INVERSION_DISABLED_REQUEST = @as(c_int, 4046);
pub const OPUS_GET_PHASE_INVERSION_DISABLED_REQUEST = @as(c_int, 4047);
pub const OPUS_GET_IN_DTX_REQUEST = @as(c_int, 4049);
pub const OPUS_SET_DRED_DURATION_REQUEST = @as(c_int, 4050);
pub const OPUS_GET_DRED_DURATION_REQUEST = @as(c_int, 4051);
pub const OPUS_SET_DNN_BLOB_REQUEST = @as(c_int, 4052);
pub const OPUS_HAVE_OPUS_PROJECTION_H = "";
pub inline fn __opus_check_int(x: anytype) opus_int32 {
    _ = &x;
    return blk_1: {
        _ = __helpers.cast(anyopaque, x == __helpers.cast(opus_int32, @as(c_int, 0)));
        break :blk_1 __helpers.cast(opus_int32, x);
    };
}
pub inline fn __opus_check_int_ptr(ptr: anytype) @TypeOf(ptr + (ptr - __helpers.cast([*c]opus_int32, ptr))) {
    _ = &ptr;
    return ptr + (ptr - __helpers.cast([*c]opus_int32, ptr));
}
pub inline fn __opus_check_uint_ptr(ptr: anytype) @TypeOf(ptr + (ptr - __helpers.cast([*c]opus_uint32, ptr))) {
    _ = &ptr;
    return ptr + (ptr - __helpers.cast([*c]opus_uint32, ptr));
}
pub inline fn __opus_check_uint8_ptr(ptr: anytype) @TypeOf(ptr + (ptr - __helpers.cast([*c]opus_uint8, ptr))) {
    _ = &ptr;
    return ptr + (ptr - __helpers.cast([*c]opus_uint8, ptr));
}
pub const __opus_check_val16_ptr = @compileError("unable to translate macro: undefined identifier `opus_val16`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAADWXwF69u4_iufnV_gwfb3M2ATbbZF-yGFRrEFH/include/opus_defines.h:195:9
pub inline fn __opus_check_void_ptr(x: anytype) @TypeOf(x) {
    _ = &x;
    return blk_1: {
        _ = __helpers.cast(anyopaque, __helpers.cast(?*anyopaque, @as(c_int, 0)) == x);
        break :blk_1 x;
    };
}
pub const OPUS_AUTO = -@as(c_int, 1000);
pub const OPUS_BITRATE_MAX = -@as(c_int, 1);
pub const OPUS_APPLICATION_VOIP = @as(c_int, 2048);
pub const OPUS_APPLICATION_AUDIO = @as(c_int, 2049);
pub const OPUS_APPLICATION_RESTRICTED_LOWDELAY = @as(c_int, 2051);
pub const OPUS_SIGNAL_VOICE = @as(c_int, 3001);
pub const OPUS_SIGNAL_MUSIC = @as(c_int, 3002);
pub const OPUS_BANDWIDTH_NARROWBAND = @as(c_int, 1101);
pub const OPUS_BANDWIDTH_MEDIUMBAND = @as(c_int, 1102);
pub const OPUS_BANDWIDTH_WIDEBAND = @as(c_int, 1103);
pub const OPUS_BANDWIDTH_SUPERWIDEBAND = @as(c_int, 1104);
pub const OPUS_BANDWIDTH_FULLBAND = @as(c_int, 1105);
pub const OPUS_FRAMESIZE_ARG = @as(c_int, 5000);
pub const OPUS_FRAMESIZE_2_5_MS = @as(c_int, 5001);
pub const OPUS_FRAMESIZE_5_MS = @as(c_int, 5002);
pub const OPUS_FRAMESIZE_10_MS = @as(c_int, 5003);
pub const OPUS_FRAMESIZE_20_MS = @as(c_int, 5004);
pub const OPUS_FRAMESIZE_40_MS = @as(c_int, 5005);
pub const OPUS_FRAMESIZE_60_MS = @as(c_int, 5006);
pub const OPUS_FRAMESIZE_80_MS = @as(c_int, 5007);
pub const OPUS_FRAMESIZE_100_MS = @as(c_int, 5008);
pub const OPUS_FRAMESIZE_120_MS = @as(c_int, 5009);
pub inline fn OPUS_SET_COMPLEXITY(x: anytype) @TypeOf(__opus_check_int(x)) {
    _ = &x;
    return blk: {
        _ = &OPUS_SET_COMPLEXITY_REQUEST;
        break :blk __opus_check_int(x);
    };
}
pub inline fn OPUS_GET_COMPLEXITY(x: anytype) @TypeOf(__opus_check_int_ptr(x)) {
    _ = &x;
    return blk: {
        _ = &OPUS_GET_COMPLEXITY_REQUEST;
        break :blk __opus_check_int_ptr(x);
    };
}
pub inline fn OPUS_SET_BITRATE(x: anytype) @TypeOf(__opus_check_int(x)) {
    _ = &x;
    return blk: {
        _ = &OPUS_SET_BITRATE_REQUEST;
        break :blk __opus_check_int(x);
    };
}
pub inline fn OPUS_GET_BITRATE(x: anytype) @TypeOf(__opus_check_int_ptr(x)) {
    _ = &x;
    return blk: {
        _ = &OPUS_GET_BITRATE_REQUEST;
        break :blk __opus_check_int_ptr(x);
    };
}
pub inline fn OPUS_SET_VBR(x: anytype) @TypeOf(__opus_check_int(x)) {
    _ = &x;
    return blk: {
        _ = &OPUS_SET_VBR_REQUEST;
        break :blk __opus_check_int(x);
    };
}
pub inline fn OPUS_GET_VBR(x: anytype) @TypeOf(__opus_check_int_ptr(x)) {
    _ = &x;
    return blk: {
        _ = &OPUS_GET_VBR_REQUEST;
        break :blk __opus_check_int_ptr(x);
    };
}
pub inline fn OPUS_SET_VBR_CONSTRAINT(x: anytype) @TypeOf(__opus_check_int(x)) {
    _ = &x;
    return blk: {
        _ = &OPUS_SET_VBR_CONSTRAINT_REQUEST;
        break :blk __opus_check_int(x);
    };
}
pub inline fn OPUS_GET_VBR_CONSTRAINT(x: anytype) @TypeOf(__opus_check_int_ptr(x)) {
    _ = &x;
    return blk: {
        _ = &OPUS_GET_VBR_CONSTRAINT_REQUEST;
        break :blk __opus_check_int_ptr(x);
    };
}
pub inline fn OPUS_SET_FORCE_CHANNELS(x: anytype) @TypeOf(__opus_check_int(x)) {
    _ = &x;
    return blk: {
        _ = &OPUS_SET_FORCE_CHANNELS_REQUEST;
        break :blk __opus_check_int(x);
    };
}
pub inline fn OPUS_GET_FORCE_CHANNELS(x: anytype) @TypeOf(__opus_check_int_ptr(x)) {
    _ = &x;
    return blk: {
        _ = &OPUS_GET_FORCE_CHANNELS_REQUEST;
        break :blk __opus_check_int_ptr(x);
    };
}
pub inline fn OPUS_SET_MAX_BANDWIDTH(x: anytype) @TypeOf(__opus_check_int(x)) {
    _ = &x;
    return blk: {
        _ = &OPUS_SET_MAX_BANDWIDTH_REQUEST;
        break :blk __opus_check_int(x);
    };
}
pub inline fn OPUS_GET_MAX_BANDWIDTH(x: anytype) @TypeOf(__opus_check_int_ptr(x)) {
    _ = &x;
    return blk: {
        _ = &OPUS_GET_MAX_BANDWIDTH_REQUEST;
        break :blk __opus_check_int_ptr(x);
    };
}
pub inline fn OPUS_SET_BANDWIDTH(x: anytype) @TypeOf(__opus_check_int(x)) {
    _ = &x;
    return blk: {
        _ = &OPUS_SET_BANDWIDTH_REQUEST;
        break :blk __opus_check_int(x);
    };
}
pub inline fn OPUS_SET_SIGNAL(x: anytype) @TypeOf(__opus_check_int(x)) {
    _ = &x;
    return blk: {
        _ = &OPUS_SET_SIGNAL_REQUEST;
        break :blk __opus_check_int(x);
    };
}
pub inline fn OPUS_GET_SIGNAL(x: anytype) @TypeOf(__opus_check_int_ptr(x)) {
    _ = &x;
    return blk: {
        _ = &OPUS_GET_SIGNAL_REQUEST;
        break :blk __opus_check_int_ptr(x);
    };
}
pub inline fn OPUS_SET_APPLICATION(x: anytype) @TypeOf(__opus_check_int(x)) {
    _ = &x;
    return blk: {
        _ = &OPUS_SET_APPLICATION_REQUEST;
        break :blk __opus_check_int(x);
    };
}
pub inline fn OPUS_GET_APPLICATION(x: anytype) @TypeOf(__opus_check_int_ptr(x)) {
    _ = &x;
    return blk: {
        _ = &OPUS_GET_APPLICATION_REQUEST;
        break :blk __opus_check_int_ptr(x);
    };
}
pub inline fn OPUS_GET_LOOKAHEAD(x: anytype) @TypeOf(__opus_check_int_ptr(x)) {
    _ = &x;
    return blk: {
        _ = &OPUS_GET_LOOKAHEAD_REQUEST;
        break :blk __opus_check_int_ptr(x);
    };
}
pub inline fn OPUS_SET_INBAND_FEC(x: anytype) @TypeOf(__opus_check_int(x)) {
    _ = &x;
    return blk: {
        _ = &OPUS_SET_INBAND_FEC_REQUEST;
        break :blk __opus_check_int(x);
    };
}
pub inline fn OPUS_GET_INBAND_FEC(x: anytype) @TypeOf(__opus_check_int_ptr(x)) {
    _ = &x;
    return blk: {
        _ = &OPUS_GET_INBAND_FEC_REQUEST;
        break :blk __opus_check_int_ptr(x);
    };
}
pub inline fn OPUS_SET_PACKET_LOSS_PERC(x: anytype) @TypeOf(__opus_check_int(x)) {
    _ = &x;
    return blk: {
        _ = &OPUS_SET_PACKET_LOSS_PERC_REQUEST;
        break :blk __opus_check_int(x);
    };
}
pub inline fn OPUS_GET_PACKET_LOSS_PERC(x: anytype) @TypeOf(__opus_check_int_ptr(x)) {
    _ = &x;
    return blk: {
        _ = &OPUS_GET_PACKET_LOSS_PERC_REQUEST;
        break :blk __opus_check_int_ptr(x);
    };
}
pub inline fn OPUS_SET_DTX(x: anytype) @TypeOf(__opus_check_int(x)) {
    _ = &x;
    return blk: {
        _ = &OPUS_SET_DTX_REQUEST;
        break :blk __opus_check_int(x);
    };
}
pub inline fn OPUS_GET_DTX(x: anytype) @TypeOf(__opus_check_int_ptr(x)) {
    _ = &x;
    return blk: {
        _ = &OPUS_GET_DTX_REQUEST;
        break :blk __opus_check_int_ptr(x);
    };
}
pub inline fn OPUS_SET_LSB_DEPTH(x: anytype) @TypeOf(__opus_check_int(x)) {
    _ = &x;
    return blk: {
        _ = &OPUS_SET_LSB_DEPTH_REQUEST;
        break :blk __opus_check_int(x);
    };
}
pub inline fn OPUS_GET_LSB_DEPTH(x: anytype) @TypeOf(__opus_check_int_ptr(x)) {
    _ = &x;
    return blk: {
        _ = &OPUS_GET_LSB_DEPTH_REQUEST;
        break :blk __opus_check_int_ptr(x);
    };
}
pub inline fn OPUS_SET_EXPERT_FRAME_DURATION(x: anytype) @TypeOf(__opus_check_int(x)) {
    _ = &x;
    return blk: {
        _ = &OPUS_SET_EXPERT_FRAME_DURATION_REQUEST;
        break :blk __opus_check_int(x);
    };
}
pub inline fn OPUS_GET_EXPERT_FRAME_DURATION(x: anytype) @TypeOf(__opus_check_int_ptr(x)) {
    _ = &x;
    return blk: {
        _ = &OPUS_GET_EXPERT_FRAME_DURATION_REQUEST;
        break :blk __opus_check_int_ptr(x);
    };
}
pub inline fn OPUS_SET_PREDICTION_DISABLED(x: anytype) @TypeOf(__opus_check_int(x)) {
    _ = &x;
    return blk: {
        _ = &OPUS_SET_PREDICTION_DISABLED_REQUEST;
        break :blk __opus_check_int(x);
    };
}
pub inline fn OPUS_GET_PREDICTION_DISABLED(x: anytype) @TypeOf(__opus_check_int_ptr(x)) {
    _ = &x;
    return blk: {
        _ = &OPUS_GET_PREDICTION_DISABLED_REQUEST;
        break :blk __opus_check_int_ptr(x);
    };
}
pub inline fn OPUS_SET_DRED_DURATION(x: anytype) @TypeOf(__opus_check_int(x)) {
    _ = &x;
    return blk: {
        _ = &OPUS_SET_DRED_DURATION_REQUEST;
        break :blk __opus_check_int(x);
    };
}
pub inline fn OPUS_GET_DRED_DURATION(x: anytype) @TypeOf(__opus_check_int_ptr(x)) {
    _ = &x;
    return blk: {
        _ = &OPUS_GET_DRED_DURATION_REQUEST;
        break :blk __opus_check_int_ptr(x);
    };
}
pub inline fn OPUS_SET_DNN_BLOB(data: anytype, len: anytype) @TypeOf(__opus_check_int(len)) {
    _ = &data;
    _ = &len;
    return blk: {
        _ = &OPUS_SET_DNN_BLOB_REQUEST;
        _ = __opus_check_void_ptr(data);
        break :blk __opus_check_int(len);
    };
}
pub const OPUS_RESET_STATE = @as(c_int, 4028);
pub inline fn OPUS_GET_FINAL_RANGE(x: anytype) @TypeOf(__opus_check_uint_ptr(x)) {
    _ = &x;
    return blk: {
        _ = &OPUS_GET_FINAL_RANGE_REQUEST;
        break :blk __opus_check_uint_ptr(x);
    };
}
pub inline fn OPUS_GET_BANDWIDTH(x: anytype) @TypeOf(__opus_check_int_ptr(x)) {
    _ = &x;
    return blk: {
        _ = &OPUS_GET_BANDWIDTH_REQUEST;
        break :blk __opus_check_int_ptr(x);
    };
}
pub inline fn OPUS_GET_SAMPLE_RATE(x: anytype) @TypeOf(__opus_check_int_ptr(x)) {
    _ = &x;
    return blk: {
        _ = &OPUS_GET_SAMPLE_RATE_REQUEST;
        break :blk __opus_check_int_ptr(x);
    };
}
pub inline fn OPUS_SET_PHASE_INVERSION_DISABLED(x: anytype) @TypeOf(__opus_check_int(x)) {
    _ = &x;
    return blk: {
        _ = &OPUS_SET_PHASE_INVERSION_DISABLED_REQUEST;
        break :blk __opus_check_int(x);
    };
}
pub inline fn OPUS_GET_PHASE_INVERSION_DISABLED(x: anytype) @TypeOf(__opus_check_int_ptr(x)) {
    _ = &x;
    return blk: {
        _ = &OPUS_GET_PHASE_INVERSION_DISABLED_REQUEST;
        break :blk __opus_check_int_ptr(x);
    };
}
pub inline fn OPUS_GET_IN_DTX(x: anytype) @TypeOf(__opus_check_int_ptr(x)) {
    _ = &x;
    return blk: {
        _ = &OPUS_GET_IN_DTX_REQUEST;
        break :blk __opus_check_int_ptr(x);
    };
}
pub inline fn OPUS_SET_GAIN(x: anytype) @TypeOf(__opus_check_int(x)) {
    _ = &x;
    return blk: {
        _ = &OPUS_SET_GAIN_REQUEST;
        break :blk __opus_check_int(x);
    };
}
pub inline fn OPUS_GET_GAIN(x: anytype) @TypeOf(__opus_check_int_ptr(x)) {
    _ = &x;
    return blk: {
        _ = &OPUS_GET_GAIN_REQUEST;
        break :blk __opus_check_int_ptr(x);
    };
}
pub inline fn OPUS_GET_LAST_PACKET_DURATION(x: anytype) @TypeOf(__opus_check_int_ptr(x)) {
    _ = &x;
    return blk: {
        _ = &OPUS_GET_LAST_PACKET_DURATION_REQUEST;
        break :blk __opus_check_int_ptr(x);
    };
}
pub inline fn OPUS_GET_PITCH(x: anytype) @TypeOf(__opus_check_int_ptr(x)) {
    _ = &x;
    return blk: {
        _ = &OPUS_GET_PITCH_REQUEST;
        break :blk __opus_check_int_ptr(x);
    };
}
