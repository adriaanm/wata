const __root = @This();
pub const __builtin = @import("std").zig.c_translation.builtins;
pub const __helpers = @import("std").zig.c_translation.helpers;

pub const time_t = c_longlong;
pub const suseconds_t = c_longlong;
pub const struct_timeval = extern struct {
    tv_sec: time_t = 0,
    tv_usec: suseconds_t = 0,
    pub const gettimeofday = __root.gettimeofday;
}; // /Users/adriaan/.local/zig/lib/libc/include/arm-linux-musl/bits/alltypes.h:43:1: warning: struct demoted to opaque type - has bitfield
pub const struct_timespec = opaque {};
pub const struct___sigset_t = extern struct {
    __bits: [32]c_ulong = @import("std").mem.zeroes([32]c_ulong),
};
pub const sigset_t = struct___sigset_t;
pub const fd_mask = c_ulong;
pub const fd_set = extern struct {
    fds_bits: [32]c_ulong = @import("std").mem.zeroes([32]c_ulong),
};
pub extern fn select(c_int, noalias [*c]fd_set, noalias [*c]fd_set, noalias [*c]fd_set, noalias [*c]struct_timeval) c_int;
pub extern fn pselect(c_int, noalias [*c]fd_set, noalias [*c]fd_set, noalias [*c]fd_set, noalias ?*const struct_timespec, noalias [*c]const sigset_t) c_int;
pub extern fn gettimeofday(noalias [*c]struct_timeval, noalias ?*anyopaque) c_int;
pub const struct_itimerval = extern struct {
    it_interval: struct_timeval = @import("std").mem.zeroes(struct_timeval),
    it_value: struct_timeval = @import("std").mem.zeroes(struct_timeval),
};
pub extern fn getitimer(c_int, [*c]struct_itimerval) c_int;
pub extern fn setitimer(c_int, noalias [*c]const struct_itimerval, noalias [*c]struct_itimerval) c_int;
pub extern fn utimes([*c]const u8, [*c]const struct_timeval) c_int;
pub const struct_timezone = extern struct {
    tz_minuteswest: c_int = 0,
    tz_dsttime: c_int = 0,
};
pub extern fn futimes(c_int, [*c]const struct_timeval) c_int;
pub extern fn futimesat(c_int, [*c]const u8, [*c]const struct_timeval) c_int;
pub extern fn lutimes([*c]const u8, [*c]const struct_timeval) c_int;
pub extern fn settimeofday([*c]const struct_timeval, [*c]const struct_timezone) c_int;
pub extern fn adjtime([*c]const struct_timeval, [*c]struct_timeval) c_int;
pub const ptrdiff_t = c_int;
pub const wchar_t = c_uint;
pub const max_align_t = extern struct {
    __aro_max_align_ll: c_longlong = 0,
    __aro_max_align_ld: c_longdouble = 0,
};
pub const PCM_FORMAT_INVALID: c_int = -1;
pub const PCM_FORMAT_S16_LE: c_int = 0;
pub const PCM_FORMAT_S32_LE: c_int = 1;
pub const PCM_FORMAT_S8: c_int = 2;
pub const PCM_FORMAT_S24_LE: c_int = 3;
pub const PCM_FORMAT_S24_3LE: c_int = 4;
pub const PCM_FORMAT_S16_BE: c_int = 5;
pub const PCM_FORMAT_S24_BE: c_int = 6;
pub const PCM_FORMAT_S24_3BE: c_int = 7;
pub const PCM_FORMAT_S32_BE: c_int = 8;
pub const PCM_FORMAT_MAX: c_int = 9;
pub const enum_pcm_format = c_int;
pub const struct_pcm_mask = extern struct {
    bits: [8]c_uint = @import("std").mem.zeroes([8]c_uint),
};
pub const struct_pcm_config = extern struct {
    channels: c_uint = 0,
    rate: c_uint = 0,
    period_size: c_uint = 0,
    period_count: c_uint = 0,
    format: enum_pcm_format = @import("std").mem.zeroes(enum_pcm_format),
    start_threshold: c_uint = 0,
    stop_threshold: c_uint = 0,
    silence_threshold: c_uint = 0,
    silence_size: c_uint = 0,
    avail_min: c_uint = 0,
};
pub const PCM_PARAM_ACCESS: c_int = 0;
pub const PCM_PARAM_FORMAT: c_int = 1;
pub const PCM_PARAM_SUBFORMAT: c_int = 2;
pub const PCM_PARAM_SAMPLE_BITS: c_int = 3;
pub const PCM_PARAM_FRAME_BITS: c_int = 4;
pub const PCM_PARAM_CHANNELS: c_int = 5;
pub const PCM_PARAM_RATE: c_int = 6;
pub const PCM_PARAM_PERIOD_TIME: c_int = 7;
pub const PCM_PARAM_PERIOD_SIZE: c_int = 8;
pub const PCM_PARAM_PERIOD_BYTES: c_int = 9;
pub const PCM_PARAM_PERIODS: c_int = 10;
pub const PCM_PARAM_BUFFER_TIME: c_int = 11;
pub const PCM_PARAM_BUFFER_SIZE: c_int = 12;
pub const PCM_PARAM_BUFFER_BYTES: c_int = 13;
pub const PCM_PARAM_TICK_TIME: c_int = 14;
pub const enum_pcm_param = c_uint;
pub const struct_pcm_params = opaque {
    pub const pcm_params_free = __root.pcm_params_free;
    pub const pcm_params_to_string = __root.pcm_params_to_string;
    pub const pcm_params_format_test = __root.pcm_params_format_test;
    pub const free = __root.pcm_params_free;
    pub const string = __root.pcm_params_to_string;
    pub const @"test" = __root.pcm_params_format_test;
};
pub extern fn pcm_params_get(card: c_uint, device: c_uint, flags: c_uint) ?*struct_pcm_params;
pub extern fn pcm_params_free(pcm_params: ?*struct_pcm_params) void;
pub extern fn pcm_params_get_mask(pcm_params: ?*const struct_pcm_params, param: enum_pcm_param) [*c]const struct_pcm_mask;
pub extern fn pcm_params_get_min(pcm_params: ?*const struct_pcm_params, param: enum_pcm_param) c_uint;
pub extern fn pcm_params_get_max(pcm_params: ?*const struct_pcm_params, param: enum_pcm_param) c_uint;
pub extern fn pcm_params_to_string(params: ?*struct_pcm_params, string: [*c]u8, size: c_uint) c_int;
pub extern fn pcm_params_format_test(params: ?*struct_pcm_params, format: enum_pcm_format) c_int;
pub const struct_pcm = opaque {
    pub const pcm_close = __root.pcm_close;
    pub const pcm_set_config = __root.pcm_set_config;
    pub const pcm_get_htimestamp = __root.pcm_get_htimestamp;
    pub const pcm_writei = __root.pcm_writei;
    pub const pcm_readi = __root.pcm_readi;
    pub const pcm_write = __root.pcm_write;
    pub const pcm_read = __root.pcm_read;
    pub const pcm_mmap_write = __root.pcm_mmap_write;
    pub const pcm_mmap_read = __root.pcm_mmap_read;
    pub const pcm_mmap_begin = __root.pcm_mmap_begin;
    pub const pcm_mmap_commit = __root.pcm_mmap_commit;
    pub const pcm_mmap_avail = __root.pcm_mmap_avail;
    pub const pcm_mmap_get_hw_ptr = __root.pcm_mmap_get_hw_ptr;
    pub const pcm_get_poll_fd = __root.pcm_get_poll_fd;
    pub const pcm_link = __root.pcm_link;
    pub const pcm_unlink = __root.pcm_unlink;
    pub const pcm_prepare = __root.pcm_prepare;
    pub const pcm_start = __root.pcm_start;
    pub const pcm_stop = __root.pcm_stop;
    pub const pcm_wait = __root.pcm_wait;
    pub const pcm_get_delay = __root.pcm_get_delay;
    pub const pcm_ioctl = __root.pcm_ioctl;
    pub const close = __root.pcm_close;
    pub const config = __root.pcm_set_config;
    pub const htimestamp = __root.pcm_get_htimestamp;
    pub const writei = __root.pcm_writei;
    pub const readi = __root.pcm_readi;
    pub const write = __root.pcm_write;
    pub const read = __root.pcm_read;
    pub const begin = __root.pcm_mmap_begin;
    pub const commit = __root.pcm_mmap_commit;
    pub const avail = __root.pcm_mmap_avail;
    pub const ptr = __root.pcm_mmap_get_hw_ptr;
    pub const fd = __root.pcm_get_poll_fd;
    pub const link = __root.pcm_link;
    pub const unlink = __root.pcm_unlink;
    pub const prepare = __root.pcm_prepare;
    pub const start = __root.pcm_start;
    pub const stop = __root.pcm_stop;
    pub const wait = __root.pcm_wait;
    pub const delay = __root.pcm_get_delay;
    pub const ioctl = __root.pcm_ioctl;
};
pub extern fn pcm_open(card: c_uint, device: c_uint, flags: c_uint, config: [*c]const struct_pcm_config) ?*struct_pcm;
pub extern fn pcm_open_by_name(name: [*c]const u8, flags: c_uint, config: [*c]const struct_pcm_config) ?*struct_pcm;
pub extern fn pcm_close(pcm: ?*struct_pcm) c_int;
pub extern fn pcm_is_ready(pcm: ?*const struct_pcm) c_int;
pub extern fn pcm_get_channels(pcm: ?*const struct_pcm) c_uint;
pub extern fn pcm_get_config(pcm: ?*const struct_pcm) [*c]const struct_pcm_config;
pub extern fn pcm_get_rate(pcm: ?*const struct_pcm) c_uint;
pub extern fn pcm_get_format(pcm: ?*const struct_pcm) enum_pcm_format;
pub extern fn pcm_get_file_descriptor(pcm: ?*const struct_pcm) c_int;
pub extern fn pcm_get_error(pcm: ?*const struct_pcm) [*c]const u8;
pub extern fn pcm_set_config(pcm: ?*struct_pcm, config: [*c]const struct_pcm_config) c_int;
pub extern fn pcm_format_to_bits(format: enum_pcm_format) c_uint;
pub extern fn pcm_get_buffer_size(pcm: ?*const struct_pcm) c_uint;
pub extern fn pcm_frames_to_bytes(pcm: ?*const struct_pcm, frames: c_uint) c_uint;
pub extern fn pcm_bytes_to_frames(pcm: ?*const struct_pcm, bytes: c_uint) c_uint;
pub extern fn pcm_get_htimestamp(pcm: ?*struct_pcm, avail: [*c]c_uint, tstamp: ?*struct_timespec) c_int;
pub extern fn pcm_get_subdevice(pcm: ?*const struct_pcm) c_uint;
pub extern fn pcm_writei(pcm: ?*struct_pcm, data: ?*const anyopaque, frame_count: c_uint) c_int;
pub extern fn pcm_readi(pcm: ?*struct_pcm, data: ?*anyopaque, frame_count: c_uint) c_int;
pub extern fn pcm_write(pcm: ?*struct_pcm, data: ?*const anyopaque, count: c_uint) c_int;
pub extern fn pcm_read(pcm: ?*struct_pcm, data: ?*anyopaque, count: c_uint) c_int;
pub extern fn pcm_mmap_write(pcm: ?*struct_pcm, data: ?*const anyopaque, count: c_uint) c_int;
pub extern fn pcm_mmap_read(pcm: ?*struct_pcm, data: ?*anyopaque, count: c_uint) c_int;
pub extern fn pcm_mmap_begin(pcm: ?*struct_pcm, areas: [*c]?*anyopaque, offset: [*c]c_uint, frames: [*c]c_uint) c_int;
pub extern fn pcm_mmap_commit(pcm: ?*struct_pcm, offset: c_uint, frames: c_uint) c_int;
pub extern fn pcm_mmap_avail(pcm: ?*struct_pcm) c_int;
pub extern fn pcm_mmap_get_hw_ptr(pcm: ?*struct_pcm, hw_ptr: [*c]c_uint, tstamp: ?*struct_timespec) c_int;
pub extern fn pcm_get_poll_fd(pcm: ?*struct_pcm) c_int;
pub extern fn pcm_link(pcm1: ?*struct_pcm, pcm2: ?*struct_pcm) c_int;
pub extern fn pcm_unlink(pcm: ?*struct_pcm) c_int;
pub extern fn pcm_prepare(pcm: ?*struct_pcm) c_int;
pub extern fn pcm_start(pcm: ?*struct_pcm) c_int;
pub extern fn pcm_stop(pcm: ?*struct_pcm) c_int;
pub extern fn pcm_wait(pcm: ?*struct_pcm, timeout: c_int) c_int;
pub extern fn pcm_get_delay(pcm: ?*struct_pcm) c_long;
pub extern fn pcm_ioctl(pcm: ?*struct_pcm, code: c_int, ...) c_int;
const struct_unnamed_3 = extern struct {
    numid: c_uint = 0,
    iface: c_int = 0,
    device: c_uint = 0,
    subdevice: c_uint = 0,
    name: [44]u8 = @import("std").mem.zeroes([44]u8),
    index: c_uint = 0,
};
const struct_unnamed_2 = extern struct {
    mask: c_uint = 0,
    id: struct_unnamed_3 = @import("std").mem.zeroes(struct_unnamed_3),
};
const union_unnamed_1 = extern union {
    element: struct_unnamed_2,
    data: [60]u8,
};
pub const struct_mixer_ctl_event = extern struct {
    type: c_int = 0,
    data: union_unnamed_1 = @import("std").mem.zeroes(union_unnamed_1),
};
pub const MIXER_CTL_TYPE_BOOL: c_int = 0;
pub const MIXER_CTL_TYPE_INT: c_int = 1;
pub const MIXER_CTL_TYPE_ENUM: c_int = 2;
pub const MIXER_CTL_TYPE_BYTE: c_int = 3;
pub const MIXER_CTL_TYPE_IEC958: c_int = 4;
pub const MIXER_CTL_TYPE_INT64: c_int = 5;
pub const MIXER_CTL_TYPE_UNKNOWN: c_int = 6;
pub const MIXER_CTL_TYPE_MAX: c_int = 7;
pub const enum_mixer_ctl_type = c_uint;
pub const struct_mixer = opaque {
    pub const mixer_close = __root.mixer_close;
    pub const mixer_add_new_ctls = __root.mixer_add_new_ctls;
    pub const mixer_get_ctl = __root.mixer_get_ctl;
    pub const mixer_get_ctl_by_name = __root.mixer_get_ctl_by_name;
    pub const mixer_get_ctl_by_name_and_index = __root.mixer_get_ctl_by_name_and_index;
    pub const mixer_subscribe_events = __root.mixer_subscribe_events;
    pub const mixer_wait_event = __root.mixer_wait_event;
    pub const mixer_read_event = __root.mixer_read_event;
    pub const mixer_consume_event = __root.mixer_consume_event;
    pub const close = __root.mixer_close;
    pub const ctls = __root.mixer_add_new_ctls;
    pub const ctl = __root.mixer_get_ctl;
    pub const name = __root.mixer_get_ctl_by_name;
    pub const index = __root.mixer_get_ctl_by_name_and_index;
    pub const events = __root.mixer_subscribe_events;
    pub const event = __root.mixer_wait_event;
};
pub extern fn mixer_open(card: c_uint) ?*struct_mixer;
pub extern fn mixer_close(mixer: ?*struct_mixer) void;
pub extern fn mixer_add_new_ctls(mixer: ?*struct_mixer) c_int;
pub extern fn mixer_get_name(mixer: ?*const struct_mixer) [*c]const u8;
pub extern fn mixer_get_num_ctls(mixer: ?*const struct_mixer) c_uint;
pub extern fn mixer_get_num_ctls_by_name(mixer: ?*const struct_mixer, name: [*c]const u8) c_uint;
pub const struct_mixer_ctl = opaque {
    pub const mixer_ctl_get_id = __root.mixer_ctl_get_id;
    pub const mixer_ctl_get_name = __root.mixer_ctl_get_name;
    pub const mixer_ctl_get_type = __root.mixer_ctl_get_type;
    pub const mixer_ctl_get_type_string = __root.mixer_ctl_get_type_string;
    pub const mixer_ctl_get_num_values = __root.mixer_ctl_get_num_values;
    pub const mixer_ctl_get_num_enums = __root.mixer_ctl_get_num_enums;
    pub const mixer_ctl_is_access_tlv_rw = __root.mixer_ctl_is_access_tlv_rw;
    pub const mixer_ctl_get_percent = __root.mixer_ctl_get_percent;
    pub const mixer_ctl_get_value = __root.mixer_ctl_get_value;
    pub const mixer_ctl_get_array = __root.mixer_ctl_get_array;
    pub const mixer_ctl_get_range_min = __root.mixer_ctl_get_range_min;
    pub const mixer_ctl_get_range_max = __root.mixer_ctl_get_range_max;
    pub const id = __root.mixer_ctl_get_id;
    pub const name = __root.mixer_ctl_get_name;
    pub const @"type" = __root.mixer_ctl_get_type;
    pub const string = __root.mixer_ctl_get_type_string;
    pub const values = __root.mixer_ctl_get_num_values;
    pub const enums = __root.mixer_ctl_get_num_enums;
    pub const rw = __root.mixer_ctl_is_access_tlv_rw;
    pub const percent = __root.mixer_ctl_get_percent;
    pub const value = __root.mixer_ctl_get_value;
    pub const array = __root.mixer_ctl_get_array;
    pub const min = __root.mixer_ctl_get_range_min;
    pub const max = __root.mixer_ctl_get_range_max;
};
pub extern fn mixer_get_ctl_const(mixer: ?*const struct_mixer, id: c_uint) ?*const struct_mixer_ctl;
pub extern fn mixer_get_ctl(mixer: ?*struct_mixer, id: c_uint) ?*struct_mixer_ctl;
pub extern fn mixer_get_ctl_by_name(mixer: ?*struct_mixer, name: [*c]const u8) ?*struct_mixer_ctl;
pub extern fn mixer_get_ctl_by_name_and_index(mixer: ?*struct_mixer, name: [*c]const u8, index: c_uint) ?*struct_mixer_ctl;
pub extern fn mixer_subscribe_events(mixer: ?*struct_mixer, subscribe: c_int) c_int;
pub extern fn mixer_wait_event(mixer: ?*struct_mixer, timeout: c_int) c_int;
pub extern fn mixer_ctl_get_id(ctl: ?*const struct_mixer_ctl) c_uint;
pub extern fn mixer_ctl_get_name(ctl: ?*const struct_mixer_ctl) [*c]const u8;
pub extern fn mixer_ctl_get_type(ctl: ?*const struct_mixer_ctl) enum_mixer_ctl_type;
pub extern fn mixer_ctl_get_type_string(ctl: ?*const struct_mixer_ctl) [*c]const u8;
pub extern fn mixer_ctl_get_num_values(ctl: ?*const struct_mixer_ctl) c_uint;
pub extern fn mixer_ctl_get_num_enums(ctl: ?*const struct_mixer_ctl) c_uint;
pub extern fn mixer_ctl_get_enum_string(ctl: ?*struct_mixer_ctl, enum_id: c_uint) [*c]const u8;
pub extern fn mixer_ctl_update(ctl: ?*struct_mixer_ctl) void;
pub extern fn mixer_ctl_is_access_tlv_rw(ctl: ?*const struct_mixer_ctl) c_int;
pub extern fn mixer_ctl_get_percent(ctl: ?*const struct_mixer_ctl, id: c_uint) c_int;
pub extern fn mixer_ctl_set_percent(ctl: ?*struct_mixer_ctl, id: c_uint, percent: c_int) c_int;
pub extern fn mixer_ctl_get_value(ctl: ?*const struct_mixer_ctl, id: c_uint) c_int;
pub extern fn mixer_ctl_get_array(ctl: ?*const struct_mixer_ctl, array: ?*anyopaque, count: usize) c_int;
pub extern fn mixer_ctl_set_value(ctl: ?*struct_mixer_ctl, id: c_uint, value: c_int) c_int;
pub extern fn mixer_ctl_set_array(ctl: ?*struct_mixer_ctl, array: ?*const anyopaque, count: usize) c_int;
pub extern fn mixer_ctl_set_enum_by_string(ctl: ?*struct_mixer_ctl, string: [*c]const u8) c_int;
pub extern fn mixer_ctl_get_range_min(ctl: ?*const struct_mixer_ctl) c_int;
pub extern fn mixer_ctl_get_range_max(ctl: ?*const struct_mixer_ctl) c_int;
pub extern fn mixer_read_event(mixer: ?*struct_mixer, event: [*c]struct_mixer_ctl_event) c_int;
pub extern fn mixer_consume_event(mixer: ?*struct_mixer) c_int;

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
pub const TINYALSA_PCM_H = "";
pub const TINYALSA_ATTRIBUTES_H = "";
pub const TINYALSA_DEPRECATED = @compileError("unable to translate macro: undefined identifier `deprecated`"); // /Users/adriaan/g/wata/src/fbclient/zig-pkg/N-V-__8AAIXaBgCxF6eJGl2-u82_CFMzLWeFnRxykudW0Zrt/include/tinyalsa/attributes.h:20:9
pub const TINYALSA_WARN_UNUSED_RESULT = @compileError("unable to translate macro: undefined identifier `warn_unused_result`"); // /Users/adriaan/g/wata/src/fbclient/zig-pkg/N-V-__8AAIXaBgCxF6eJGl2-u82_CFMzLWeFnRxykudW0Zrt/include/tinyalsa/attributes.h:26:9
pub const _SYS_TIME_H = "";
pub const _FEATURES_H = "";
pub const _BSD_SOURCE = @as(c_int, 1);
pub const _XOPEN_SOURCE = @as(c_int, 700);
pub const __restrict = @compileError("unable to translate C expr: unexpected token 'restrict'"); // /Users/adriaan/.local/zig/lib/libc/include/generic-musl/features.h:20:9
pub const __inline = @compileError("unable to translate C expr: unexpected token 'inline'"); // /Users/adriaan/.local/zig/lib/libc/include/generic-musl/features.h:26:9
pub const __REDIR = @compileError("unable to translate C expr: unexpected token '__typeof__'"); // /Users/adriaan/.local/zig/lib/libc/include/generic-musl/features.h:38:9
pub const _SYS_SELECT_H = "";
pub const __NEED_size_t = "";
pub const __NEED_time_t = "";
pub const __NEED_suseconds_t = "";
pub const __NEED_struct_timeval = "";
pub const __NEED_struct_timespec = "";
pub const __NEED_sigset_t = "";
pub const _REDIR_TIME64 = @as(c_int, 1);
pub const __BYTE_ORDER = @as(c_int, 1234);
pub const __LONG_MAX = @as(c_long, 0x7fffffff);
pub const __LITTLE_ENDIAN = @as(c_int, 1234);
pub const __BIG_ENDIAN = @as(c_int, 4321);
pub const __USE_TIME_BITS64 = @as(c_int, 1);
pub const __DEFINED_size_t = "";
pub const __DEFINED_time_t = "";
pub const __DEFINED_suseconds_t = "";
pub const __DEFINED_struct_timeval = "";
pub const __DEFINED_struct_timespec = "";
pub const __DEFINED_sigset_t = "";
pub const FD_SETSIZE = @as(c_int, 1024);
pub const FD_ZERO = @compileError("unable to translate macro: undefined identifier `__i`"); // /Users/adriaan/.local/zig/lib/libc/include/generic-musl/sys/select.h:26:9
pub const FD_SET = @compileError("unable to translate C expr: expected ')' instead got '|='"); // /Users/adriaan/.local/zig/lib/libc/include/generic-musl/sys/select.h:27:9
pub const FD_CLR = @compileError("unable to translate C expr: expected ')' instead got '&='"); // /Users/adriaan/.local/zig/lib/libc/include/generic-musl/sys/select.h:28:9
pub inline fn FD_ISSET(d: anytype, s: anytype) @TypeOf(!!((s.*.fds_bits[@as(usize, @intCast(__helpers.div(d, @as(c_int, 8) * __helpers.sizeof(c_long))))] & (@as(c_ulong, 1) << __helpers.rem(d, @as(c_int, 8) * __helpers.sizeof(c_long)))) != 0)) {
    _ = &d;
    _ = &s;
    return !!((s.*.fds_bits[@as(usize, @intCast(__helpers.div(d, @as(c_int, 8) * __helpers.sizeof(c_long))))] & (@as(c_ulong, 1) << __helpers.rem(d, @as(c_int, 8) * __helpers.sizeof(c_long)))) != 0);
}
pub const NFDBITS = @as(c_int, 8) * __helpers.cast(c_int, __helpers.sizeof(c_long));
pub const ITIMER_REAL = @as(c_int, 0);
pub const ITIMER_VIRTUAL = @as(c_int, 1);
pub const ITIMER_PROF = @as(c_int, 2);
pub inline fn timerisset(t: anytype) @TypeOf((t.*.tv_sec != 0) or (t.*.tv_usec != 0)) {
    _ = &t;
    return (t.*.tv_sec != 0) or (t.*.tv_usec != 0);
}
pub const timerclear = @compileError("unable to translate C expr: expected ')' instead got '='"); // /Users/adriaan/.local/zig/lib/libc/include/generic-musl/sys/time.h:37:9
pub const timercmp = @compileError("unable to translate C expr: expected ':' instead got ''"); // /Users/adriaan/.local/zig/lib/libc/include/generic-musl/sys/time.h:38:9
pub const timeradd = @compileError("unable to translate C expr: expected ')' instead got '='"); // /Users/adriaan/.local/zig/lib/libc/include/generic-musl/sys/time.h:40:9
pub const timersub = @compileError("unable to translate C expr: expected ')' instead got '='"); // /Users/adriaan/.local/zig/lib/libc/include/generic-musl/sys/time.h:43:9
pub const __STDC_VERSION_STDDEF_H__ = @as(c_long, 202311);
pub const NULL = __helpers.cast(?*anyopaque, @as(c_int, 0));
pub const offsetof = @compileError("unable to translate macro: undefined identifier `__builtin_offsetof`"); // /Users/adriaan/.local/zig/lib/compiler/aro/include/stddef.h:18:9
pub const PCM_OUT = @as(c_int, 0x00000000);
pub const PCM_IN = __helpers.promoteIntLiteral(c_int, 0x10000000, .hex);
pub const PCM_MMAP = @as(c_int, 0x00000001);
pub const PCM_NOIRQ = @as(c_int, 0x00000002);
pub const PCM_NORESTART = @as(c_int, 0x00000004);
pub const PCM_MONOTONIC = @as(c_int, 0x00000008);
pub const PCM_NONBLOCK = @as(c_int, 0x00000010);
pub const PCM_STATE_OPEN = @as(c_int, 0x00);
pub const PCM_STATE_SETUP = @as(c_int, 0x01);
pub const PCM_STATE_PREPARED = @as(c_int, 0x02);
pub const PCM_STATE_RUNNING = @as(c_int, 0x03);
pub const PCM_STATE_XRUN = @as(c_int, 0x04);
pub const PCM_STATE_DRAINING = @as(c_int, 0x05);
pub const PCM_STATE_SUSPENDED = @as(c_int, 0x07);
pub const PCM_STATE_DISCONNECTED = @as(c_int, 0x08);
pub const TINYALSA_MIXER_H = "";
pub const timeval = struct_timeval;
pub const timespec = struct_timespec;
pub const __sigset_t = struct___sigset_t;
pub const itimerval = struct_itimerval;
pub const timezone = struct_timezone;
pub const pcm_format = enum_pcm_format;
pub const pcm_mask = struct_pcm_mask;
pub const pcm_config = struct_pcm_config;
pub const pcm_param = enum_pcm_param;
pub const pcm_params = struct_pcm_params;
pub const pcm = struct_pcm;
pub const mixer_ctl_event = struct_mixer_ctl_event;
pub const mixer_ctl_type = enum_mixer_ctl_type;
pub const mixer = struct_mixer;
pub const mixer_ctl = struct_mixer_ctl;
