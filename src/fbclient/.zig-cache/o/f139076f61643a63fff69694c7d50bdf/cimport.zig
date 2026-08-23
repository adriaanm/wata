const __root = @This();
pub const __builtin = @import("std").zig.c_translation.builtins;
pub const __helpers = @import("std").zig.c_translation.helpers;

pub const ptrdiff_t = c_int;
pub const wchar_t = c_uint;
pub const max_align_t = extern struct {
    __aro_max_align_ll: c_longlong = 0,
    __aro_max_align_ld: c_longdouble = 0,
};
pub const struct___locale_struct = opaque {};
pub const locale_t = ?*struct___locale_struct;
pub extern fn memcpy(noalias ?*anyopaque, noalias ?*const anyopaque, usize) ?*anyopaque;
pub extern fn memmove(?*anyopaque, ?*const anyopaque, usize) ?*anyopaque;
pub extern fn memset(?*anyopaque, c_int, usize) ?*anyopaque;
pub extern fn memcmp(?*const anyopaque, ?*const anyopaque, usize) c_int;
pub extern fn memchr(?*const anyopaque, c_int, usize) ?*anyopaque;
pub extern fn strcpy(noalias [*c]u8, noalias [*c]const u8) [*c]u8;
pub extern fn strncpy(noalias [*c]u8, noalias [*c]const u8, usize) [*c]u8;
pub extern fn strcat(noalias [*c]u8, noalias [*c]const u8) [*c]u8;
pub extern fn strncat(noalias [*c]u8, noalias [*c]const u8, usize) [*c]u8;
pub extern fn strcmp([*c]const u8, [*c]const u8) c_int;
pub extern fn strncmp([*c]const u8, [*c]const u8, usize) c_int;
pub extern fn strcoll([*c]const u8, [*c]const u8) c_int;
pub extern fn strxfrm(noalias [*c]u8, noalias [*c]const u8, usize) usize;
pub extern fn strchr([*c]const u8, c_int) [*c]u8;
pub extern fn strrchr([*c]const u8, c_int) [*c]u8;
pub extern fn strcspn([*c]const u8, [*c]const u8) usize;
pub extern fn strspn([*c]const u8, [*c]const u8) usize;
pub extern fn strpbrk([*c]const u8, [*c]const u8) [*c]u8;
pub extern fn strstr([*c]const u8, [*c]const u8) [*c]u8;
pub extern fn strtok(noalias [*c]u8, noalias [*c]const u8) [*c]u8;
pub extern fn strlen([*c]const u8) usize;
pub extern fn strerror(c_int) [*c]u8;
pub extern fn bcmp(?*const anyopaque, ?*const anyopaque, usize) c_int;
pub extern fn bcopy(?*const anyopaque, ?*anyopaque, usize) void;
pub extern fn bzero(?*anyopaque, usize) void;
pub extern fn index([*c]const u8, c_int) [*c]u8;
pub extern fn rindex([*c]const u8, c_int) [*c]u8;
pub extern fn ffs(c_int) c_int;
pub extern fn ffsl(c_long) c_int;
pub extern fn ffsll(c_longlong) c_int;
pub extern fn strcasecmp([*c]const u8, [*c]const u8) c_int;
pub extern fn strncasecmp([*c]const u8, [*c]const u8, usize) c_int;
pub extern fn strcasecmp_l([*c]const u8, [*c]const u8, locale_t) c_int;
pub extern fn strncasecmp_l([*c]const u8, [*c]const u8, usize, locale_t) c_int;
pub extern fn strtok_r(noalias [*c]u8, noalias [*c]const u8, noalias [*c][*c]u8) [*c]u8;
pub extern fn strerror_r(c_int, [*c]u8, usize) c_int;
pub extern fn stpcpy(noalias [*c]u8, noalias [*c]const u8) [*c]u8;
pub extern fn stpncpy(noalias [*c]u8, noalias [*c]const u8, usize) [*c]u8;
pub extern fn strnlen([*c]const u8, usize) usize;
pub extern fn strdup([*c]const u8) [*c]u8;
pub extern fn strndup([*c]const u8, usize) [*c]u8;
pub extern fn strsignal(c_int) [*c]u8;
pub extern fn strerror_l(c_int, locale_t) [*c]u8;
pub extern fn strcoll_l([*c]const u8, [*c]const u8, locale_t) c_int;
pub extern fn strxfrm_l(noalias [*c]u8, noalias [*c]const u8, usize, locale_t) usize;
pub extern fn memmem(?*const anyopaque, usize, ?*const anyopaque, usize) ?*anyopaque;
pub extern fn memccpy(noalias ?*anyopaque, noalias ?*const anyopaque, c_int, usize) ?*anyopaque;
pub extern fn strsep([*c][*c]u8, [*c]const u8) [*c]u8;
pub extern fn strlcat([*c]u8, [*c]const u8, usize) usize;
pub extern fn strlcpy([*c]u8, [*c]const u8, usize) usize;
pub extern fn explicit_bzero(?*anyopaque, usize) void;
pub const off_t = c_longlong;
pub const struct__IO_FILE = opaque {
    pub const fclose = __root.fclose;
    pub const feof = __root.feof;
    pub const ferror = __root.ferror;
    pub const fflush = __root.fflush;
    pub const clearerr = __root.clearerr;
    pub const fseek = __root.fseek;
    pub const ftell = __root.ftell;
    pub const rewind = __root.rewind;
    pub const fgetpos = __root.fgetpos;
    pub const fsetpos = __root.fsetpos;
    pub const fgetc = __root.fgetc;
    pub const getc = __root.getc;
    pub const fprintf = __root.fprintf;
    pub const vfprintf = __root.vfprintf;
    pub const fscanf = __root.fscanf;
    pub const vfscanf = __root.vfscanf;
    pub const setvbuf = __root.setvbuf;
    pub const setbuf = __root.setbuf;
    pub const pclose = __root.pclose;
    pub const fileno = __root.fileno;
    pub const fseeko = __root.fseeko;
    pub const ftello = __root.ftello;
    pub const flockfile = __root.flockfile;
    pub const ftrylockfile = __root.ftrylockfile;
    pub const funlockfile = __root.funlockfile;
    pub const getc_unlocked = __root.getc_unlocked;
    pub const setlinebuf = __root.setlinebuf;
    pub const setbuffer = __root.setbuffer;
    pub const fgetc_unlocked = __root.fgetc_unlocked;
    pub const fflush_unlocked = __root.fflush_unlocked;
    pub const clearerr_unlocked = __root.clearerr_unlocked;
    pub const feof_unlocked = __root.feof_unlocked;
    pub const ferror_unlocked = __root.ferror_unlocked;
    pub const fileno_unlocked = __root.fileno_unlocked;
    pub const getw = __root.getw;
    pub const fgetln = __root.fgetln;
    pub const unlocked = __root.getc_unlocked;
};
pub const FILE = struct__IO_FILE;
pub const struct___va_list_tag_1 = extern struct {
    unnamed_0: ?*anyopaque = null,
};
pub const __builtin_va_list = [1]struct___va_list_tag_1;
pub const va_list = __builtin_va_list;
pub const __isoc_va_list = __builtin_va_list;
pub const union__G_fpos64_t = extern union {
    __opaque: [16]u8,
    __lldata: c_longlong,
    __align: f64,
};
pub const fpos_t = union__G_fpos64_t;
pub extern const stdin: ?*FILE;
pub extern const stdout: ?*FILE;
pub extern const stderr: ?*FILE;
pub extern fn fopen(noalias [*c]const u8, noalias [*c]const u8) ?*FILE;
pub extern fn freopen(noalias [*c]const u8, noalias [*c]const u8, noalias ?*FILE) ?*FILE;
pub extern fn fclose(?*FILE) c_int;
pub extern fn remove([*c]const u8) c_int;
pub extern fn rename([*c]const u8, [*c]const u8) c_int;
pub extern fn feof(?*FILE) c_int;
pub extern fn ferror(?*FILE) c_int;
pub extern fn fflush(?*FILE) c_int;
pub extern fn clearerr(?*FILE) void;
pub extern fn fseek(?*FILE, c_long, c_int) c_int;
pub extern fn ftell(?*FILE) c_long;
pub extern fn rewind(?*FILE) void;
pub extern fn fgetpos(noalias ?*FILE, noalias [*c]fpos_t) c_int;
pub extern fn fsetpos(?*FILE, [*c]const fpos_t) c_int;
pub extern fn fread(noalias ?*anyopaque, usize, usize, noalias ?*FILE) usize;
pub extern fn fwrite(noalias ?*const anyopaque, usize, usize, noalias ?*FILE) usize;
pub extern fn fgetc(?*FILE) c_int;
pub extern fn getc(?*FILE) c_int;
pub extern fn getchar() c_int;
pub extern fn ungetc(c_int, ?*FILE) c_int;
pub extern fn fputc(c_int, ?*FILE) c_int;
pub extern fn putc(c_int, ?*FILE) c_int;
pub extern fn putchar(c_int) c_int;
pub extern fn fgets(noalias [*c]u8, c_int, noalias ?*FILE) [*c]u8;
pub extern fn fputs(noalias [*c]const u8, noalias ?*FILE) c_int;
pub extern fn puts([*c]const u8) c_int;
pub extern fn printf(noalias [*c]const u8, ...) c_int;
pub extern fn fprintf(noalias ?*FILE, noalias [*c]const u8, ...) c_int;
pub extern fn sprintf(noalias [*c]u8, noalias [*c]const u8, ...) c_int;
pub extern fn snprintf(noalias [*c]u8, usize, noalias [*c]const u8, ...) c_int;
pub extern fn vprintf(noalias [*c]const u8, [*c]struct___va_list_tag_1) c_int;
pub extern fn vfprintf(noalias ?*FILE, noalias [*c]const u8, [*c]struct___va_list_tag_1) c_int;
pub extern fn vsprintf(noalias [*c]u8, noalias [*c]const u8, [*c]struct___va_list_tag_1) c_int;
pub extern fn vsnprintf(noalias [*c]u8, usize, noalias [*c]const u8, [*c]struct___va_list_tag_1) c_int;
pub extern fn scanf(noalias [*c]const u8, ...) c_int;
pub extern fn fscanf(noalias ?*FILE, noalias [*c]const u8, ...) c_int;
pub extern fn sscanf(noalias [*c]const u8, noalias [*c]const u8, ...) c_int;
pub extern fn vscanf(noalias [*c]const u8, [*c]struct___va_list_tag_1) c_int;
pub extern fn vfscanf(noalias ?*FILE, noalias [*c]const u8, [*c]struct___va_list_tag_1) c_int;
pub extern fn vsscanf(noalias [*c]const u8, noalias [*c]const u8, [*c]struct___va_list_tag_1) c_int;
pub extern fn perror([*c]const u8) void;
pub extern fn setvbuf(noalias ?*FILE, noalias [*c]u8, c_int, usize) c_int;
pub extern fn setbuf(noalias ?*FILE, noalias [*c]u8) void;
pub extern fn tmpnam([*c]u8) [*c]u8;
pub extern fn tmpfile() ?*FILE;
pub extern fn fmemopen(noalias ?*anyopaque, usize, noalias [*c]const u8) ?*FILE;
pub extern fn open_memstream([*c][*c]u8, [*c]usize) ?*FILE;
pub extern fn fdopen(c_int, [*c]const u8) ?*FILE;
pub extern fn popen([*c]const u8, [*c]const u8) ?*FILE;
pub extern fn pclose(?*FILE) c_int;
pub extern fn fileno(?*FILE) c_int;
pub extern fn fseeko(?*FILE, off_t, c_int) c_int;
pub extern fn ftello(?*FILE) off_t;
pub extern fn dprintf(c_int, noalias [*c]const u8, ...) c_int;
pub extern fn vdprintf(c_int, noalias [*c]const u8, [*c]struct___va_list_tag_1) c_int;
pub extern fn flockfile(?*FILE) void;
pub extern fn ftrylockfile(?*FILE) c_int;
pub extern fn funlockfile(?*FILE) void;
pub extern fn getc_unlocked(?*FILE) c_int;
pub extern fn getchar_unlocked() c_int;
pub extern fn putc_unlocked(c_int, ?*FILE) c_int;
pub extern fn putchar_unlocked(c_int) c_int;
pub extern fn getdelim(noalias [*c][*c]u8, noalias [*c]usize, c_int, noalias ?*FILE) isize;
pub extern fn getline(noalias [*c][*c]u8, noalias [*c]usize, noalias ?*FILE) isize;
pub extern fn renameat(c_int, [*c]const u8, c_int, [*c]const u8) c_int;
pub extern fn ctermid([*c]u8) [*c]u8;
pub extern fn tempnam([*c]const u8, [*c]const u8) [*c]u8;
pub extern fn cuserid([*c]u8) [*c]u8;
pub extern fn setlinebuf(?*FILE) void;
pub extern fn setbuffer(?*FILE, [*c]u8, usize) void;
pub extern fn fgetc_unlocked(?*FILE) c_int;
pub extern fn fputc_unlocked(c_int, ?*FILE) c_int;
pub extern fn fflush_unlocked(?*FILE) c_int;
pub extern fn fread_unlocked(?*anyopaque, usize, usize, ?*FILE) usize;
pub extern fn fwrite_unlocked(?*const anyopaque, usize, usize, ?*FILE) usize;
pub extern fn clearerr_unlocked(?*FILE) void;
pub extern fn feof_unlocked(?*FILE) c_int;
pub extern fn ferror_unlocked(?*FILE) c_int;
pub extern fn fileno_unlocked(?*FILE) c_int;
pub extern fn getw(?*FILE) c_int;
pub extern fn putw(c_int, ?*FILE) c_int;
pub extern fn fgetln(?*FILE, [*c]usize) [*c]u8;
pub extern fn asprintf([*c][*c]u8, [*c]const u8, ...) c_int;
pub extern fn vasprintf([*c][*c]u8, [*c]const u8, [*c]struct___va_list_tag_1) c_int;
pub extern fn atoi([*c]const u8) c_int;
pub extern fn atol([*c]const u8) c_long;
pub extern fn atoll([*c]const u8) c_longlong;
pub extern fn atof([*c]const u8) f64;
pub extern fn strtof(noalias [*c]const u8, noalias [*c][*c]u8) f32;
pub extern fn strtod(noalias [*c]const u8, noalias [*c][*c]u8) f64;
pub extern fn strtold(noalias [*c]const u8, noalias [*c][*c]u8) c_longdouble;
pub extern fn strtol(noalias [*c]const u8, noalias [*c][*c]u8, c_int) c_long;
pub extern fn strtoul(noalias [*c]const u8, noalias [*c][*c]u8, c_int) c_ulong;
pub extern fn strtoll(noalias [*c]const u8, noalias [*c][*c]u8, c_int) c_longlong;
pub extern fn strtoull(noalias [*c]const u8, noalias [*c][*c]u8, c_int) c_ulonglong;
pub extern fn rand() c_int;
pub extern fn srand(c_uint) void;
pub extern fn malloc(usize) ?*anyopaque;
pub extern fn calloc(usize, usize) ?*anyopaque;
pub extern fn realloc(?*anyopaque, usize) ?*anyopaque;
pub extern fn free(?*anyopaque) void;
pub extern fn aligned_alloc(usize, usize) ?*anyopaque;
pub extern fn abort() noreturn;
pub extern fn atexit(?*const fn () callconv(.c) void) c_int;
pub extern fn exit(c_int) noreturn;
pub extern fn _Exit(c_int) noreturn;
pub extern fn at_quick_exit(?*const fn () callconv(.c) void) c_int;
pub extern fn quick_exit(c_int) noreturn;
pub extern fn getenv([*c]const u8) [*c]u8;
pub extern fn system([*c]const u8) c_int;
pub extern fn bsearch(?*const anyopaque, ?*const anyopaque, usize, usize, ?*const fn (?*const anyopaque, ?*const anyopaque) callconv(.c) c_int) ?*anyopaque;
pub extern fn qsort(?*anyopaque, usize, usize, ?*const fn (?*const anyopaque, ?*const anyopaque) callconv(.c) c_int) void;
pub extern fn abs(c_int) c_int;
pub extern fn labs(c_long) c_long;
pub extern fn llabs(c_longlong) c_longlong;
pub const div_t = extern struct {
    quot: c_int = 0,
    rem: c_int = 0,
};
pub const ldiv_t = extern struct {
    quot: c_long = 0,
    rem: c_long = 0,
};
pub const lldiv_t = extern struct {
    quot: c_longlong = 0,
    rem: c_longlong = 0,
};
pub extern fn div(c_int, c_int) div_t;
pub extern fn ldiv(c_long, c_long) ldiv_t;
pub extern fn lldiv(c_longlong, c_longlong) lldiv_t;
pub extern fn mblen([*c]const u8, usize) c_int;
pub extern fn mbtowc(noalias [*c]wchar_t, noalias [*c]const u8, usize) c_int;
pub extern fn wctomb([*c]u8, wchar_t) c_int;
pub extern fn mbstowcs(noalias [*c]wchar_t, noalias [*c]const u8, usize) usize;
pub extern fn wcstombs(noalias [*c]u8, noalias [*c]const wchar_t, usize) usize;
pub extern fn __ctype_get_mb_cur_max() usize;
pub extern fn posix_memalign([*c]?*anyopaque, usize, usize) c_int;
pub extern fn setenv([*c]const u8, [*c]const u8, c_int) c_int;
pub extern fn unsetenv([*c]const u8) c_int;
pub extern fn mkstemp([*c]u8) c_int;
pub extern fn mkostemp([*c]u8, c_int) c_int;
pub extern fn mkdtemp([*c]u8) [*c]u8;
pub extern fn getsubopt([*c][*c]u8, [*c]const [*c]u8, [*c][*c]u8) c_int;
pub extern fn rand_r([*c]c_uint) c_int;
pub extern fn realpath(noalias [*c]const u8, noalias [*c]u8) [*c]u8;
pub extern fn random() c_long;
pub extern fn srandom(c_uint) void;
pub extern fn initstate(c_uint, [*c]u8, usize) [*c]u8;
pub extern fn setstate([*c]u8) [*c]u8;
pub extern fn putenv([*c]u8) c_int;
pub extern fn posix_openpt(c_int) c_int;
pub extern fn grantpt(c_int) c_int;
pub extern fn unlockpt(c_int) c_int;
pub extern fn ptsname(c_int) [*c]u8;
pub extern fn l64a(c_long) [*c]u8;
pub extern fn a64l([*c]const u8) c_long;
pub extern fn setkey([*c]const u8) void;
pub extern fn drand48() f64;
pub extern fn erand48([*c]c_ushort) f64;
pub extern fn lrand48() c_long;
pub extern fn nrand48([*c]c_ushort) c_long;
pub extern fn mrand48() c_long;
pub extern fn jrand48([*c]c_ushort) c_long;
pub extern fn srand48(c_long) void;
pub extern fn seed48([*c]c_ushort) [*c]c_ushort;
pub extern fn lcong48([*c]c_ushort) void;
pub extern fn alloca(usize) ?*anyopaque;
pub extern fn mktemp([*c]u8) [*c]u8;
pub extern fn mkstemps([*c]u8, c_int) c_int;
pub extern fn mkostemps([*c]u8, c_int, c_int) c_int;
pub extern fn valloc(usize) ?*anyopaque;
pub extern fn memalign(usize, usize) ?*anyopaque;
pub extern fn getloadavg([*c]f64, c_int) c_int;
pub extern fn clearenv() c_int;
pub extern fn reallocarray(?*anyopaque, usize, usize) ?*anyopaque;
pub extern fn qsort_r(?*anyopaque, usize, usize, ?*const fn (?*const anyopaque, ?*const anyopaque, ?*anyopaque) callconv(.c) c_int, ?*anyopaque) void;
pub const __jmp_buf = [32]c_ulonglong;
pub const struct___jmp_buf_tag = extern struct {
    __jb: __jmp_buf = @import("std").mem.zeroes(__jmp_buf),
    __fl: c_ulong = 0,
    __ss: [32]c_ulong = @import("std").mem.zeroes([32]c_ulong),
    pub const sigsetjmp = __root.sigsetjmp;
    pub const siglongjmp = __root.siglongjmp;
    pub const _setjmp = __root._setjmp;
    pub const _longjmp = __root._longjmp;
    pub const setjmp = __root.setjmp;
    pub const longjmp = __root.longjmp;
};
pub const jmp_buf = [1]struct___jmp_buf_tag;
pub const sigjmp_buf = jmp_buf;
pub extern fn sigsetjmp([*c]struct___jmp_buf_tag, c_int) c_int;
pub extern fn siglongjmp([*c]struct___jmp_buf_tag, c_int) noreturn;
pub extern fn _setjmp([*c]struct___jmp_buf_tag) c_int;
pub extern fn _longjmp([*c]struct___jmp_buf_tag, c_int) noreturn;
pub extern fn setjmp([*c]struct___jmp_buf_tag) c_int;
pub extern fn longjmp([*c]struct___jmp_buf_tag, c_int) noreturn;
pub const __gnuc_va_list = __builtin_va_list;
pub const FT_Int16 = c_short;
pub const FT_UInt16 = c_ushort;
pub const FT_Int32 = c_int;
pub const FT_UInt32 = c_uint;
pub const FT_Fast = c_int;
pub const FT_UFast = c_uint;
pub const FT_Int64 = c_longlong;
pub const FT_UInt64 = c_ulonglong;
pub const FT_Alloc_Func = ?*const fn (memory: FT_Memory, size: c_long) callconv(.c) ?*anyopaque;
pub const FT_Free_Func = ?*const fn (memory: FT_Memory, block: ?*anyopaque) callconv(.c) void;
pub const FT_Realloc_Func = ?*const fn (memory: FT_Memory, cur_size: c_long, new_size: c_long, block: ?*anyopaque) callconv(.c) ?*anyopaque;
pub const struct_FT_MemoryRec_ = extern struct {
    user: ?*anyopaque = null,
    alloc: FT_Alloc_Func = null,
    free: FT_Free_Func = null,
    realloc: FT_Realloc_Func = null,
};
pub const FT_Memory = [*c]struct_FT_MemoryRec_;
pub const union_FT_StreamDesc_ = extern union {
    value: c_long,
    pointer: ?*anyopaque,
};
pub const FT_StreamDesc = union_FT_StreamDesc_;
pub const FT_Stream_IoFunc = ?*const fn (stream: FT_Stream, offset: c_ulong, buffer: [*c]u8, count: c_ulong) callconv(.c) c_ulong;
pub const FT_Stream_CloseFunc = ?*const fn (stream: FT_Stream) callconv(.c) void;
pub const struct_FT_StreamRec_ = extern struct {
    base: [*c]u8 = null,
    size: c_ulong = 0,
    pos: c_ulong = 0,
    descriptor: FT_StreamDesc = @import("std").mem.zeroes(FT_StreamDesc),
    pathname: FT_StreamDesc = @import("std").mem.zeroes(FT_StreamDesc),
    read: FT_Stream_IoFunc = null,
    close: FT_Stream_CloseFunc = null,
    memory: FT_Memory = null,
    cursor: [*c]u8 = null,
    limit: [*c]u8 = null,
};
pub const FT_Stream = [*c]struct_FT_StreamRec_;
pub const FT_StreamRec = struct_FT_StreamRec_;
pub const FT_Pos = c_long;
pub const struct_FT_Vector_ = extern struct {
    x: FT_Pos = 0,
    y: FT_Pos = 0,
    pub const FT_Vector_Transform = __root.FT_Vector_Transform;
    pub const Transform = __root.FT_Vector_Transform;
};
pub const FT_Vector = struct_FT_Vector_;
pub const struct_FT_BBox_ = extern struct {
    xMin: FT_Pos = 0,
    yMin: FT_Pos = 0,
    xMax: FT_Pos = 0,
    yMax: FT_Pos = 0,
};
pub const FT_BBox = struct_FT_BBox_;
pub const FT_PIXEL_MODE_NONE: c_int = 0;
pub const FT_PIXEL_MODE_MONO: c_int = 1;
pub const FT_PIXEL_MODE_GRAY: c_int = 2;
pub const FT_PIXEL_MODE_GRAY2: c_int = 3;
pub const FT_PIXEL_MODE_GRAY4: c_int = 4;
pub const FT_PIXEL_MODE_LCD: c_int = 5;
pub const FT_PIXEL_MODE_LCD_V: c_int = 6;
pub const FT_PIXEL_MODE_BGRA: c_int = 7;
pub const FT_PIXEL_MODE_MAX: c_int = 8;
pub const enum_FT_Pixel_Mode_ = c_uint;
pub const FT_Pixel_Mode = enum_FT_Pixel_Mode_;
pub const struct_FT_Bitmap_ = extern struct {
    rows: c_uint = 0,
    width: c_uint = 0,
    pitch: c_int = 0,
    buffer: [*c]u8 = null,
    num_grays: c_ushort = 0,
    pixel_mode: u8 = 0,
    palette_mode: u8 = 0,
    palette: ?*anyopaque = null,
    pub const FT_Bitmap_Init = __root.FT_Bitmap_Init;
    pub const FT_Bitmap_New = __root.FT_Bitmap_New;
    pub const Init = __root.FT_Bitmap_Init;
    pub const New = __root.FT_Bitmap_New;
};
pub const FT_Bitmap = struct_FT_Bitmap_;
pub const struct_FT_Outline_ = extern struct {
    n_contours: c_ushort = 0,
    n_points: c_ushort = 0,
    points: [*c]FT_Vector = null,
    tags: [*c]u8 = null,
    contours: [*c]c_ushort = null,
    flags: c_int = 0,
};
pub const FT_Outline = struct_FT_Outline_;
pub const FT_Outline_MoveToFunc = ?*const fn (to: [*c]const FT_Vector, user: ?*anyopaque) callconv(.c) c_int;
pub const FT_Outline_LineToFunc = ?*const fn (to: [*c]const FT_Vector, user: ?*anyopaque) callconv(.c) c_int;
pub const FT_Outline_ConicToFunc = ?*const fn (control: [*c]const FT_Vector, to: [*c]const FT_Vector, user: ?*anyopaque) callconv(.c) c_int;
pub const FT_Outline_CubicToFunc = ?*const fn (control1: [*c]const FT_Vector, control2: [*c]const FT_Vector, to: [*c]const FT_Vector, user: ?*anyopaque) callconv(.c) c_int;
pub const struct_FT_Outline_Funcs_ = extern struct {
    move_to: FT_Outline_MoveToFunc = null,
    line_to: FT_Outline_LineToFunc = null,
    conic_to: FT_Outline_ConicToFunc = null,
    cubic_to: FT_Outline_CubicToFunc = null,
    shift: c_int = 0,
    delta: FT_Pos = 0,
};
pub const FT_Outline_Funcs = struct_FT_Outline_Funcs_;
pub const FT_GLYPH_FORMAT_NONE: c_int = 0;
pub const FT_GLYPH_FORMAT_COMPOSITE: c_int = 1668246896;
pub const FT_GLYPH_FORMAT_BITMAP: c_int = 1651078259;
pub const FT_GLYPH_FORMAT_OUTLINE: c_int = 1869968492;
pub const FT_GLYPH_FORMAT_PLOTTER: c_int = 1886154612;
pub const FT_GLYPH_FORMAT_SVG: c_int = 1398163232;
pub const enum_FT_Glyph_Format_ = c_uint;
pub const FT_Glyph_Format = enum_FT_Glyph_Format_;
pub const struct_FT_Span_ = extern struct {
    x: c_short = 0,
    len: c_ushort = 0,
    coverage: u8 = 0,
};
pub const FT_Span = struct_FT_Span_;
pub const FT_SpanFunc = ?*const fn (y: c_int, count: c_int, spans: [*c]const FT_Span, user: ?*anyopaque) callconv(.c) void;
pub const FT_Raster_BitTest_Func = ?*const fn (y: c_int, x: c_int, user: ?*anyopaque) callconv(.c) c_int;
pub const FT_Raster_BitSet_Func = ?*const fn (y: c_int, x: c_int, user: ?*anyopaque) callconv(.c) void;
pub const struct_FT_Raster_Params_ = extern struct {
    target: [*c]const FT_Bitmap = null,
    source: ?*const anyopaque = null,
    flags: c_int = 0,
    gray_spans: FT_SpanFunc = null,
    black_spans: FT_SpanFunc = null,
    bit_test: FT_Raster_BitTest_Func = null,
    bit_set: FT_Raster_BitSet_Func = null,
    user: ?*anyopaque = null,
    clip_box: FT_BBox = @import("std").mem.zeroes(FT_BBox),
};
pub const FT_Raster_Params = struct_FT_Raster_Params_;
pub const struct_FT_RasterRec_ = opaque {};
pub const FT_Raster = ?*struct_FT_RasterRec_;
pub const FT_Raster_NewFunc = ?*const fn (memory: ?*anyopaque, raster: [*c]FT_Raster) callconv(.c) c_int;
pub const FT_Raster_DoneFunc = ?*const fn (raster: FT_Raster) callconv(.c) void;
pub const FT_Raster_ResetFunc = ?*const fn (raster: FT_Raster, pool_base: [*c]u8, pool_size: c_ulong) callconv(.c) void;
pub const FT_Raster_SetModeFunc = ?*const fn (raster: FT_Raster, mode: c_ulong, args: ?*anyopaque) callconv(.c) c_int;
pub const FT_Raster_RenderFunc = ?*const fn (raster: FT_Raster, params: [*c]const FT_Raster_Params) callconv(.c) c_int;
pub const struct_FT_Raster_Funcs_ = extern struct {
    glyph_format: FT_Glyph_Format = @import("std").mem.zeroes(FT_Glyph_Format),
    raster_new: FT_Raster_NewFunc = null,
    raster_reset: FT_Raster_ResetFunc = null,
    raster_set_mode: FT_Raster_SetModeFunc = null,
    raster_render: FT_Raster_RenderFunc = null,
    raster_done: FT_Raster_DoneFunc = null,
};
pub const FT_Raster_Funcs = struct_FT_Raster_Funcs_;
pub const FT_Bool = u8;
pub const FT_FWord = c_short;
pub const FT_UFWord = c_ushort;
pub const FT_Char = i8;
pub const FT_Byte = u8;
pub const FT_Bytes = [*c]const FT_Byte;
pub const FT_Tag = FT_UInt32;
pub const FT_String = u8;
pub const FT_Short = c_short;
pub const FT_UShort = c_ushort;
pub const FT_Int = c_int;
pub const FT_UInt = c_uint;
pub const FT_Long = c_long;
pub const FT_ULong = c_ulong;
pub const FT_F2Dot14 = c_short;
pub const FT_F26Dot6 = c_long;
pub const FT_Fixed = c_long;
pub const FT_Error = c_int;
pub const FT_Pointer = ?*anyopaque;
pub const FT_Offset = usize;
pub const FT_PtrDist = ptrdiff_t;
pub const struct_FT_UnitVector_ = extern struct {
    x: FT_F2Dot14 = 0,
    y: FT_F2Dot14 = 0,
};
pub const FT_UnitVector = struct_FT_UnitVector_;
pub const struct_FT_Matrix_ = extern struct {
    xx: FT_Fixed = 0,
    xy: FT_Fixed = 0,
    yx: FT_Fixed = 0,
    yy: FT_Fixed = 0,
};
pub const FT_Matrix = struct_FT_Matrix_;
pub const struct_FT_Data_ = extern struct {
    pointer: [*c]const FT_Byte = null,
    length: FT_UInt = 0,
};
pub const FT_Data = struct_FT_Data_;
pub const FT_Generic_Finalizer = ?*const fn (object: ?*anyopaque) callconv(.c) void;
pub const struct_FT_Generic_ = extern struct {
    data: ?*anyopaque = null,
    finalizer: FT_Generic_Finalizer = null,
};
pub const FT_Generic = struct_FT_Generic_;
pub const struct_FT_ListNodeRec_ = extern struct {
    prev: FT_ListNode = null,
    next: FT_ListNode = null,
    data: ?*anyopaque = null,
};
pub const FT_ListNode = [*c]struct_FT_ListNodeRec_;
pub const struct_FT_ListRec_ = extern struct {
    head: FT_ListNode = null,
    tail: FT_ListNode = null,
};
pub const FT_List = [*c]struct_FT_ListRec_;
pub const FT_ListNodeRec = struct_FT_ListNodeRec_;
pub const FT_ListRec = struct_FT_ListRec_;
pub const FT_Mod_Err_Base: c_int = 0;
pub const FT_Mod_Err_Autofit: c_int = 0;
pub const FT_Mod_Err_BDF: c_int = 0;
pub const FT_Mod_Err_Bzip2: c_int = 0;
pub const FT_Mod_Err_Cache: c_int = 0;
pub const FT_Mod_Err_CFF: c_int = 0;
pub const FT_Mod_Err_CID: c_int = 0;
pub const FT_Mod_Err_Gzip: c_int = 0;
pub const FT_Mod_Err_LZW: c_int = 0;
pub const FT_Mod_Err_OTvalid: c_int = 0;
pub const FT_Mod_Err_PCF: c_int = 0;
pub const FT_Mod_Err_PFR: c_int = 0;
pub const FT_Mod_Err_PSaux: c_int = 0;
pub const FT_Mod_Err_PShinter: c_int = 0;
pub const FT_Mod_Err_PSnames: c_int = 0;
pub const FT_Mod_Err_Raster: c_int = 0;
pub const FT_Mod_Err_SFNT: c_int = 0;
pub const FT_Mod_Err_Smooth: c_int = 0;
pub const FT_Mod_Err_TrueType: c_int = 0;
pub const FT_Mod_Err_Type1: c_int = 0;
pub const FT_Mod_Err_Type42: c_int = 0;
pub const FT_Mod_Err_Winfonts: c_int = 0;
pub const FT_Mod_Err_GXvalid: c_int = 0;
pub const FT_Mod_Err_Sdf: c_int = 0;
pub const FT_Mod_Err_Max: c_int = 1;
const enum_unnamed_2 = c_uint;
pub const FT_Err_Ok: c_int = 0;
pub const FT_Err_Cannot_Open_Resource: c_int = 1;
pub const FT_Err_Unknown_File_Format: c_int = 2;
pub const FT_Err_Invalid_File_Format: c_int = 3;
pub const FT_Err_Invalid_Version: c_int = 4;
pub const FT_Err_Lower_Module_Version: c_int = 5;
pub const FT_Err_Invalid_Argument: c_int = 6;
pub const FT_Err_Unimplemented_Feature: c_int = 7;
pub const FT_Err_Invalid_Table: c_int = 8;
pub const FT_Err_Invalid_Offset: c_int = 9;
pub const FT_Err_Array_Too_Large: c_int = 10;
pub const FT_Err_Missing_Module: c_int = 11;
pub const FT_Err_Missing_Property: c_int = 12;
pub const FT_Err_Invalid_Glyph_Index: c_int = 16;
pub const FT_Err_Invalid_Character_Code: c_int = 17;
pub const FT_Err_Invalid_Glyph_Format: c_int = 18;
pub const FT_Err_Cannot_Render_Glyph: c_int = 19;
pub const FT_Err_Invalid_Outline: c_int = 20;
pub const FT_Err_Invalid_Composite: c_int = 21;
pub const FT_Err_Too_Many_Hints: c_int = 22;
pub const FT_Err_Invalid_Pixel_Size: c_int = 23;
pub const FT_Err_Invalid_SVG_Document: c_int = 24;
pub const FT_Err_Invalid_Handle: c_int = 32;
pub const FT_Err_Invalid_Library_Handle: c_int = 33;
pub const FT_Err_Invalid_Driver_Handle: c_int = 34;
pub const FT_Err_Invalid_Face_Handle: c_int = 35;
pub const FT_Err_Invalid_Size_Handle: c_int = 36;
pub const FT_Err_Invalid_Slot_Handle: c_int = 37;
pub const FT_Err_Invalid_CharMap_Handle: c_int = 38;
pub const FT_Err_Invalid_Cache_Handle: c_int = 39;
pub const FT_Err_Invalid_Stream_Handle: c_int = 40;
pub const FT_Err_Too_Many_Drivers: c_int = 48;
pub const FT_Err_Too_Many_Extensions: c_int = 49;
pub const FT_Err_Out_Of_Memory: c_int = 64;
pub const FT_Err_Unlisted_Object: c_int = 65;
pub const FT_Err_Cannot_Open_Stream: c_int = 81;
pub const FT_Err_Invalid_Stream_Seek: c_int = 82;
pub const FT_Err_Invalid_Stream_Skip: c_int = 83;
pub const FT_Err_Invalid_Stream_Read: c_int = 84;
pub const FT_Err_Invalid_Stream_Operation: c_int = 85;
pub const FT_Err_Invalid_Frame_Operation: c_int = 86;
pub const FT_Err_Nested_Frame_Access: c_int = 87;
pub const FT_Err_Invalid_Frame_Read: c_int = 88;
pub const FT_Err_Raster_Uninitialized: c_int = 96;
pub const FT_Err_Raster_Corrupted: c_int = 97;
pub const FT_Err_Raster_Overflow: c_int = 98;
pub const FT_Err_Raster_Negative_Height: c_int = 99;
pub const FT_Err_Too_Many_Caches: c_int = 112;
pub const FT_Err_Invalid_Opcode: c_int = 128;
pub const FT_Err_Too_Few_Arguments: c_int = 129;
pub const FT_Err_Stack_Overflow: c_int = 130;
pub const FT_Err_Code_Overflow: c_int = 131;
pub const FT_Err_Bad_Argument: c_int = 132;
pub const FT_Err_Divide_By_Zero: c_int = 133;
pub const FT_Err_Invalid_Reference: c_int = 134;
pub const FT_Err_Debug_OpCode: c_int = 135;
pub const FT_Err_ENDF_In_Exec_Stream: c_int = 136;
pub const FT_Err_Nested_DEFS: c_int = 137;
pub const FT_Err_Invalid_CodeRange: c_int = 138;
pub const FT_Err_Execution_Too_Long: c_int = 139;
pub const FT_Err_Too_Many_Function_Defs: c_int = 140;
pub const FT_Err_Too_Many_Instruction_Defs: c_int = 141;
pub const FT_Err_Table_Missing: c_int = 142;
pub const FT_Err_Horiz_Header_Missing: c_int = 143;
pub const FT_Err_Locations_Missing: c_int = 144;
pub const FT_Err_Name_Table_Missing: c_int = 145;
pub const FT_Err_CMap_Table_Missing: c_int = 146;
pub const FT_Err_Hmtx_Table_Missing: c_int = 147;
pub const FT_Err_Post_Table_Missing: c_int = 148;
pub const FT_Err_Invalid_Horiz_Metrics: c_int = 149;
pub const FT_Err_Invalid_CharMap_Format: c_int = 150;
pub const FT_Err_Invalid_PPem: c_int = 151;
pub const FT_Err_Invalid_Vert_Metrics: c_int = 152;
pub const FT_Err_Could_Not_Find_Context: c_int = 153;
pub const FT_Err_Invalid_Post_Table_Format: c_int = 154;
pub const FT_Err_Invalid_Post_Table: c_int = 155;
pub const FT_Err_DEF_In_Glyf_Bytecode: c_int = 156;
pub const FT_Err_Missing_Bitmap: c_int = 157;
pub const FT_Err_Missing_SVG_Hooks: c_int = 158;
pub const FT_Err_Syntax_Error: c_int = 160;
pub const FT_Err_Stack_Underflow: c_int = 161;
pub const FT_Err_Ignore: c_int = 162;
pub const FT_Err_No_Unicode_Glyph_Name: c_int = 163;
pub const FT_Err_Glyph_Too_Big: c_int = 164;
pub const FT_Err_Missing_Startfont_Field: c_int = 176;
pub const FT_Err_Missing_Font_Field: c_int = 177;
pub const FT_Err_Missing_Size_Field: c_int = 178;
pub const FT_Err_Missing_Fontboundingbox_Field: c_int = 179;
pub const FT_Err_Missing_Chars_Field: c_int = 180;
pub const FT_Err_Missing_Startchar_Field: c_int = 181;
pub const FT_Err_Missing_Encoding_Field: c_int = 182;
pub const FT_Err_Missing_Bbx_Field: c_int = 183;
pub const FT_Err_Bbx_Too_Big: c_int = 184;
pub const FT_Err_Corrupted_Font_Header: c_int = 185;
pub const FT_Err_Corrupted_Font_Glyphs: c_int = 186;
pub const FT_Err_Max: c_int = 187;
const enum_unnamed_3 = c_uint;
pub extern fn FT_Error_String(error_code: FT_Error) [*c]const u8;
pub const struct_FT_Glyph_Metrics_ = extern struct {
    width: FT_Pos = 0,
    height: FT_Pos = 0,
    horiBearingX: FT_Pos = 0,
    horiBearingY: FT_Pos = 0,
    horiAdvance: FT_Pos = 0,
    vertBearingX: FT_Pos = 0,
    vertBearingY: FT_Pos = 0,
    vertAdvance: FT_Pos = 0,
};
pub const FT_Glyph_Metrics = struct_FT_Glyph_Metrics_;
pub const struct_FT_Bitmap_Size_ = extern struct {
    height: FT_Short = 0,
    width: FT_Short = 0,
    size: FT_Pos = 0,
    x_ppem: FT_Pos = 0,
    y_ppem: FT_Pos = 0,
};
pub const FT_Bitmap_Size = struct_FT_Bitmap_Size_;
pub const struct_FT_LibraryRec_ = opaque {
    pub const FT_Done_FreeType = __root.FT_Done_FreeType;
    pub const FT_New_Face = __root.FT_New_Face;
    pub const FT_New_Memory_Face = __root.FT_New_Memory_Face;
    pub const FT_Open_Face = __root.FT_Open_Face;
    pub const FT_Library_Version = __root.FT_Library_Version;
    pub const FT_Bitmap_Copy = __root.FT_Bitmap_Copy;
    pub const FT_Bitmap_Embolden = __root.FT_Bitmap_Embolden;
    pub const FT_Bitmap_Convert = __root.FT_Bitmap_Convert;
    pub const FT_Bitmap_Blend = __root.FT_Bitmap_Blend;
    pub const FT_Bitmap_Done = __root.FT_Bitmap_Done;
    pub const FreeType = __root.FT_Done_FreeType;
    pub const Face = __root.FT_New_Face;
    pub const Version = __root.FT_Library_Version;
    pub const Copy = __root.FT_Bitmap_Copy;
    pub const Embolden = __root.FT_Bitmap_Embolden;
    pub const Convert = __root.FT_Bitmap_Convert;
    pub const Blend = __root.FT_Bitmap_Blend;
    pub const Done = __root.FT_Bitmap_Done;
};
pub const FT_Library = ?*struct_FT_LibraryRec_;
pub const struct_FT_ModuleRec_ = opaque {};
pub const FT_Module = ?*struct_FT_ModuleRec_;
pub const struct_FT_DriverRec_ = opaque {};
pub const FT_Driver = ?*struct_FT_DriverRec_;
pub const struct_FT_RendererRec_ = opaque {};
pub const FT_Renderer = ?*struct_FT_RendererRec_;
pub const FT_ENCODING_NONE: c_int = 0;
pub const FT_ENCODING_MS_SYMBOL: c_int = 1937337698;
pub const FT_ENCODING_UNICODE: c_int = 1970170211;
pub const FT_ENCODING_SJIS: c_int = 1936353651;
pub const FT_ENCODING_PRC: c_int = 1734484000;
pub const FT_ENCODING_BIG5: c_int = 1651074869;
pub const FT_ENCODING_WANSUNG: c_int = 2002873971;
pub const FT_ENCODING_JOHAB: c_int = 1785686113;
pub const FT_ENCODING_GB2312: c_int = 1734484000;
pub const FT_ENCODING_MS_SJIS: c_int = 1936353651;
pub const FT_ENCODING_MS_GB2312: c_int = 1734484000;
pub const FT_ENCODING_MS_BIG5: c_int = 1651074869;
pub const FT_ENCODING_MS_WANSUNG: c_int = 2002873971;
pub const FT_ENCODING_MS_JOHAB: c_int = 1785686113;
pub const FT_ENCODING_ADOBE_STANDARD: c_int = 1094995778;
pub const FT_ENCODING_ADOBE_EXPERT: c_int = 1094992453;
pub const FT_ENCODING_ADOBE_CUSTOM: c_int = 1094992451;
pub const FT_ENCODING_ADOBE_LATIN_1: c_int = 1818326065;
pub const FT_ENCODING_OLD_LATIN_2: c_int = 1818326066;
pub const FT_ENCODING_APPLE_ROMAN: c_int = 1634889070;
pub const enum_FT_Encoding_ = c_uint;
pub const FT_Encoding = enum_FT_Encoding_;
pub const struct_FT_CharMapRec_ = extern struct {
    face: FT_Face = null,
    encoding: FT_Encoding = @import("std").mem.zeroes(FT_Encoding),
    platform_id: FT_UShort = 0,
    encoding_id: FT_UShort = 0,
    pub const FT_Get_Charmap_Index = __root.FT_Get_Charmap_Index;
    pub const Index = __root.FT_Get_Charmap_Index;
};
pub const FT_CharMap = [*c]struct_FT_CharMapRec_;
pub const struct_FT_SubGlyphRec_ = opaque {};
pub const FT_SubGlyph = ?*struct_FT_SubGlyphRec_;
pub const struct_FT_Slot_InternalRec_ = opaque {};
pub const FT_Slot_Internal = ?*struct_FT_Slot_InternalRec_;
pub const struct_FT_GlyphSlotRec_ = extern struct {
    library: FT_Library = null,
    face: FT_Face = null,
    next: FT_GlyphSlot = null,
    glyph_index: FT_UInt = 0,
    generic: FT_Generic = @import("std").mem.zeroes(FT_Generic),
    metrics: FT_Glyph_Metrics = @import("std").mem.zeroes(FT_Glyph_Metrics),
    linearHoriAdvance: FT_Fixed = 0,
    linearVertAdvance: FT_Fixed = 0,
    advance: FT_Vector = @import("std").mem.zeroes(FT_Vector),
    format: FT_Glyph_Format = @import("std").mem.zeroes(FT_Glyph_Format),
    bitmap: FT_Bitmap = @import("std").mem.zeroes(FT_Bitmap),
    bitmap_left: FT_Int = 0,
    bitmap_top: FT_Int = 0,
    outline: FT_Outline = @import("std").mem.zeroes(FT_Outline),
    num_subglyphs: FT_UInt = 0,
    subglyphs: FT_SubGlyph = null,
    control_data: ?*anyopaque = null,
    control_len: c_long = 0,
    lsb_delta: FT_Pos = 0,
    rsb_delta: FT_Pos = 0,
    other: ?*anyopaque = null,
    internal: FT_Slot_Internal = null,
    pub const FT_Render_Glyph = __root.FT_Render_Glyph;
    pub const FT_Get_SubGlyph_Info = __root.FT_Get_SubGlyph_Info;
    pub const FT_GlyphSlot_Own_Bitmap = __root.FT_GlyphSlot_Own_Bitmap;
    pub const Glyph = __root.FT_Render_Glyph;
    pub const Info = __root.FT_Get_SubGlyph_Info;
    pub const Bitmap = __root.FT_GlyphSlot_Own_Bitmap;
};
pub const FT_GlyphSlot = [*c]struct_FT_GlyphSlotRec_;
pub const struct_FT_Size_Metrics_ = extern struct {
    x_ppem: FT_UShort = 0,
    y_ppem: FT_UShort = 0,
    x_scale: FT_Fixed = 0,
    y_scale: FT_Fixed = 0,
    ascender: FT_Pos = 0,
    descender: FT_Pos = 0,
    height: FT_Pos = 0,
    max_advance: FT_Pos = 0,
};
pub const FT_Size_Metrics = struct_FT_Size_Metrics_;
pub const struct_FT_Size_InternalRec_ = opaque {};
pub const FT_Size_Internal = ?*struct_FT_Size_InternalRec_;
pub const struct_FT_SizeRec_ = extern struct {
    face: FT_Face = null,
    generic: FT_Generic = @import("std").mem.zeroes(FT_Generic),
    metrics: FT_Size_Metrics = @import("std").mem.zeroes(FT_Size_Metrics),
    internal: FT_Size_Internal = null,
};
pub const FT_Size = [*c]struct_FT_SizeRec_;
pub const struct_FT_Face_InternalRec_ = opaque {};
pub const FT_Face_Internal = ?*struct_FT_Face_InternalRec_;
pub const struct_FT_FaceRec_ = extern struct {
    num_faces: FT_Long = 0,
    face_index: FT_Long = 0,
    face_flags: FT_Long = 0,
    style_flags: FT_Long = 0,
    num_glyphs: FT_Long = 0,
    family_name: [*c]FT_String = null,
    style_name: [*c]FT_String = null,
    num_fixed_sizes: FT_Int = 0,
    available_sizes: [*c]FT_Bitmap_Size = null,
    num_charmaps: FT_Int = 0,
    charmaps: [*c]FT_CharMap = null,
    generic: FT_Generic = @import("std").mem.zeroes(FT_Generic),
    bbox: FT_BBox = @import("std").mem.zeroes(FT_BBox),
    units_per_EM: FT_UShort = 0,
    ascender: FT_Short = 0,
    descender: FT_Short = 0,
    height: FT_Short = 0,
    max_advance_width: FT_Short = 0,
    max_advance_height: FT_Short = 0,
    underline_position: FT_Short = 0,
    underline_thickness: FT_Short = 0,
    glyph: FT_GlyphSlot = null,
    size: FT_Size = null,
    charmap: FT_CharMap = null,
    driver: FT_Driver = null,
    memory: FT_Memory = null,
    stream: FT_Stream = null,
    sizes_list: FT_ListRec = @import("std").mem.zeroes(FT_ListRec),
    autohint: FT_Generic = @import("std").mem.zeroes(FT_Generic),
    extensions: ?*anyopaque = null,
    internal: FT_Face_Internal = null,
    pub const FT_Attach_File = __root.FT_Attach_File;
    pub const FT_Attach_Stream = __root.FT_Attach_Stream;
    pub const FT_Reference_Face = __root.FT_Reference_Face;
    pub const FT_Done_Face = __root.FT_Done_Face;
    pub const FT_Select_Size = __root.FT_Select_Size;
    pub const FT_Request_Size = __root.FT_Request_Size;
    pub const FT_Set_Char_Size = __root.FT_Set_Char_Size;
    pub const FT_Set_Pixel_Sizes = __root.FT_Set_Pixel_Sizes;
    pub const FT_Load_Glyph = __root.FT_Load_Glyph;
    pub const FT_Load_Char = __root.FT_Load_Char;
    pub const FT_Set_Transform = __root.FT_Set_Transform;
    pub const FT_Get_Transform = __root.FT_Get_Transform;
    pub const FT_Get_Kerning = __root.FT_Get_Kerning;
    pub const FT_Get_Track_Kerning = __root.FT_Get_Track_Kerning;
    pub const FT_Select_Charmap = __root.FT_Select_Charmap;
    pub const FT_Set_Charmap = __root.FT_Set_Charmap;
    pub const FT_Get_Char_Index = __root.FT_Get_Char_Index;
    pub const FT_Get_First_Char = __root.FT_Get_First_Char;
    pub const FT_Get_Next_Char = __root.FT_Get_Next_Char;
    pub const FT_Face_Properties = __root.FT_Face_Properties;
    pub const FT_Get_Name_Index = __root.FT_Get_Name_Index;
    pub const FT_Get_Glyph_Name = __root.FT_Get_Glyph_Name;
    pub const FT_Get_Postscript_Name = __root.FT_Get_Postscript_Name;
    pub const FT_Get_FSType_Flags = __root.FT_Get_FSType_Flags;
    pub const FT_Face_GetCharVariantIndex = __root.FT_Face_GetCharVariantIndex;
    pub const FT_Face_GetCharVariantIsDefault = __root.FT_Face_GetCharVariantIsDefault;
    pub const FT_Face_GetVariantSelectors = __root.FT_Face_GetVariantSelectors;
    pub const FT_Face_GetVariantsOfChar = __root.FT_Face_GetVariantsOfChar;
    pub const FT_Face_GetCharsOfVariant = __root.FT_Face_GetCharsOfVariant;
    pub const FT_Face_CheckTrueTypePatents = __root.FT_Face_CheckTrueTypePatents;
    pub const FT_Face_SetUnpatentedHinting = __root.FT_Face_SetUnpatentedHinting;
    pub const FT_Palette_Data_Get = __root.FT_Palette_Data_Get;
    pub const FT_Palette_Select = __root.FT_Palette_Select;
    pub const FT_Palette_Set_Foreground_Color = __root.FT_Palette_Set_Foreground_Color;
    pub const FT_Get_Color_Glyph_Layer = __root.FT_Get_Color_Glyph_Layer;
    pub const FT_Get_Color_Glyph_Paint = __root.FT_Get_Color_Glyph_Paint;
    pub const FT_Get_Color_Glyph_ClipBox = __root.FT_Get_Color_Glyph_ClipBox;
    pub const FT_Get_Paint_Layers = __root.FT_Get_Paint_Layers;
    pub const FT_Get_Colorline_Stops = __root.FT_Get_Colorline_Stops;
    pub const FT_Get_Paint = __root.FT_Get_Paint;
    pub const File = __root.FT_Attach_File;
    pub const Stream = __root.FT_Attach_Stream;
    pub const Face = __root.FT_Reference_Face;
    pub const Size = __root.FT_Select_Size;
    pub const Sizes = __root.FT_Set_Pixel_Sizes;
    pub const Glyph = __root.FT_Load_Glyph;
    pub const Char = __root.FT_Load_Char;
    pub const Transform = __root.FT_Set_Transform;
    pub const Kerning = __root.FT_Get_Kerning;
    pub const Charmap = __root.FT_Select_Charmap;
    pub const Index = __root.FT_Get_Char_Index;
    pub const Properties = __root.FT_Face_Properties;
    pub const Name = __root.FT_Get_Glyph_Name;
    pub const Flags = __root.FT_Get_FSType_Flags;
    pub const GetCharVariantIndex = __root.FT_Face_GetCharVariantIndex;
    pub const GetCharVariantIsDefault = __root.FT_Face_GetCharVariantIsDefault;
    pub const GetVariantSelectors = __root.FT_Face_GetVariantSelectors;
    pub const GetVariantsOfChar = __root.FT_Face_GetVariantsOfChar;
    pub const GetCharsOfVariant = __root.FT_Face_GetCharsOfVariant;
    pub const CheckTrueTypePatents = __root.FT_Face_CheckTrueTypePatents;
    pub const SetUnpatentedHinting = __root.FT_Face_SetUnpatentedHinting;
    pub const Get = __root.FT_Palette_Data_Get;
    pub const Select = __root.FT_Palette_Select;
    pub const Color = __root.FT_Palette_Set_Foreground_Color;
    pub const Layer = __root.FT_Get_Color_Glyph_Layer;
    pub const Paint = __root.FT_Get_Color_Glyph_Paint;
    pub const ClipBox = __root.FT_Get_Color_Glyph_ClipBox;
    pub const Layers = __root.FT_Get_Paint_Layers;
    pub const Stops = __root.FT_Get_Colorline_Stops;
};
pub const FT_Face = [*c]struct_FT_FaceRec_;
pub const FT_CharMapRec = struct_FT_CharMapRec_;
pub const FT_FaceRec = struct_FT_FaceRec_;
pub const FT_SizeRec = struct_FT_SizeRec_;
pub const FT_GlyphSlotRec = struct_FT_GlyphSlotRec_;
pub extern fn FT_Init_FreeType(alibrary: [*c]FT_Library) FT_Error;
pub extern fn FT_Done_FreeType(library: FT_Library) FT_Error;
pub const struct_FT_Parameter_ = extern struct {
    tag: FT_ULong = 0,
    data: FT_Pointer = null,
};
pub const FT_Parameter = struct_FT_Parameter_;
pub const struct_FT_Open_Args_ = extern struct {
    flags: FT_UInt = 0,
    memory_base: [*c]const FT_Byte = null,
    memory_size: FT_Long = 0,
    pathname: [*c]FT_String = null,
    stream: FT_Stream = null,
    driver: FT_Module = null,
    num_params: FT_Int = 0,
    params: [*c]FT_Parameter = null,
};
pub const FT_Open_Args = struct_FT_Open_Args_;
pub extern fn FT_New_Face(library: FT_Library, filepathname: [*c]const u8, face_index: FT_Long, aface: [*c]FT_Face) FT_Error;
pub extern fn FT_New_Memory_Face(library: FT_Library, file_base: [*c]const FT_Byte, file_size: FT_Long, face_index: FT_Long, aface: [*c]FT_Face) FT_Error;
pub extern fn FT_Open_Face(library: FT_Library, args: [*c]const FT_Open_Args, face_index: FT_Long, aface: [*c]FT_Face) FT_Error;
pub extern fn FT_Attach_File(face: FT_Face, filepathname: [*c]const u8) FT_Error;
pub extern fn FT_Attach_Stream(face: FT_Face, parameters: [*c]const FT_Open_Args) FT_Error;
pub extern fn FT_Reference_Face(face: FT_Face) FT_Error;
pub extern fn FT_Done_Face(face: FT_Face) FT_Error;
pub extern fn FT_Select_Size(face: FT_Face, strike_index: FT_Int) FT_Error;
pub const FT_SIZE_REQUEST_TYPE_NOMINAL: c_int = 0;
pub const FT_SIZE_REQUEST_TYPE_REAL_DIM: c_int = 1;
pub const FT_SIZE_REQUEST_TYPE_BBOX: c_int = 2;
pub const FT_SIZE_REQUEST_TYPE_CELL: c_int = 3;
pub const FT_SIZE_REQUEST_TYPE_SCALES: c_int = 4;
pub const FT_SIZE_REQUEST_TYPE_MAX: c_int = 5;
pub const enum_FT_Size_Request_Type_ = c_uint;
pub const FT_Size_Request_Type = enum_FT_Size_Request_Type_;
pub const struct_FT_Size_RequestRec_ = extern struct {
    type: FT_Size_Request_Type = @import("std").mem.zeroes(FT_Size_Request_Type),
    width: FT_Long = 0,
    height: FT_Long = 0,
    horiResolution: FT_UInt = 0,
    vertResolution: FT_UInt = 0,
};
pub const FT_Size_RequestRec = struct_FT_Size_RequestRec_;
pub const FT_Size_Request = [*c]struct_FT_Size_RequestRec_;
pub extern fn FT_Request_Size(face: FT_Face, req: FT_Size_Request) FT_Error;
pub extern fn FT_Set_Char_Size(face: FT_Face, char_width: FT_F26Dot6, char_height: FT_F26Dot6, horz_resolution: FT_UInt, vert_resolution: FT_UInt) FT_Error;
pub extern fn FT_Set_Pixel_Sizes(face: FT_Face, pixel_width: FT_UInt, pixel_height: FT_UInt) FT_Error;
pub extern fn FT_Load_Glyph(face: FT_Face, glyph_index: FT_UInt, load_flags: FT_Int32) FT_Error;
pub extern fn FT_Load_Char(face: FT_Face, char_code: FT_ULong, load_flags: FT_Int32) FT_Error;
pub extern fn FT_Set_Transform(face: FT_Face, matrix: [*c]FT_Matrix, delta: [*c]FT_Vector) void;
pub extern fn FT_Get_Transform(face: FT_Face, matrix: [*c]FT_Matrix, delta: [*c]FT_Vector) void;
pub const FT_RENDER_MODE_NORMAL: c_int = 0;
pub const FT_RENDER_MODE_LIGHT: c_int = 1;
pub const FT_RENDER_MODE_MONO: c_int = 2;
pub const FT_RENDER_MODE_LCD: c_int = 3;
pub const FT_RENDER_MODE_LCD_V: c_int = 4;
pub const FT_RENDER_MODE_SDF: c_int = 5;
pub const FT_RENDER_MODE_MAX: c_int = 6;
pub const enum_FT_Render_Mode_ = c_uint;
pub const FT_Render_Mode = enum_FT_Render_Mode_;
pub extern fn FT_Render_Glyph(slot: FT_GlyphSlot, render_mode: FT_Render_Mode) FT_Error;
pub const FT_KERNING_DEFAULT: c_int = 0;
pub const FT_KERNING_UNFITTED: c_int = 1;
pub const FT_KERNING_UNSCALED: c_int = 2;
pub const enum_FT_Kerning_Mode_ = c_uint;
pub const FT_Kerning_Mode = enum_FT_Kerning_Mode_;
pub extern fn FT_Get_Kerning(face: FT_Face, left_glyph: FT_UInt, right_glyph: FT_UInt, kern_mode: FT_UInt, akerning: [*c]FT_Vector) FT_Error;
pub extern fn FT_Get_Track_Kerning(face: FT_Face, point_size: FT_Fixed, degree: FT_Int, akerning: [*c]FT_Fixed) FT_Error;
pub extern fn FT_Select_Charmap(face: FT_Face, encoding: FT_Encoding) FT_Error;
pub extern fn FT_Set_Charmap(face: FT_Face, charmap: FT_CharMap) FT_Error;
pub extern fn FT_Get_Charmap_Index(charmap: FT_CharMap) FT_Int;
pub extern fn FT_Get_Char_Index(face: FT_Face, charcode: FT_ULong) FT_UInt;
pub extern fn FT_Get_First_Char(face: FT_Face, agindex: [*c]FT_UInt) FT_ULong;
pub extern fn FT_Get_Next_Char(face: FT_Face, char_code: FT_ULong, agindex: [*c]FT_UInt) FT_ULong;
pub extern fn FT_Face_Properties(face: FT_Face, num_properties: FT_UInt, properties: [*c]FT_Parameter) FT_Error;
pub extern fn FT_Get_Name_Index(face: FT_Face, glyph_name: [*c]const FT_String) FT_UInt;
pub extern fn FT_Get_Glyph_Name(face: FT_Face, glyph_index: FT_UInt, buffer: FT_Pointer, buffer_max: FT_UInt) FT_Error;
pub extern fn FT_Get_Postscript_Name(face: FT_Face) [*c]const u8;
pub extern fn FT_Get_SubGlyph_Info(glyph: FT_GlyphSlot, sub_index: FT_UInt, p_index: [*c]FT_Int, p_flags: [*c]FT_UInt, p_arg1: [*c]FT_Int, p_arg2: [*c]FT_Int, p_transform: [*c]FT_Matrix) FT_Error;
pub extern fn FT_Get_FSType_Flags(face: FT_Face) FT_UShort;
pub extern fn FT_Face_GetCharVariantIndex(face: FT_Face, charcode: FT_ULong, variantSelector: FT_ULong) FT_UInt;
pub extern fn FT_Face_GetCharVariantIsDefault(face: FT_Face, charcode: FT_ULong, variantSelector: FT_ULong) FT_Int;
pub extern fn FT_Face_GetVariantSelectors(face: FT_Face) [*c]FT_UInt32;
pub extern fn FT_Face_GetVariantsOfChar(face: FT_Face, charcode: FT_ULong) [*c]FT_UInt32;
pub extern fn FT_Face_GetCharsOfVariant(face: FT_Face, variantSelector: FT_ULong) [*c]FT_UInt32;
pub extern fn FT_MulDiv(a: FT_Long, b: FT_Long, c: FT_Long) FT_Long;
pub extern fn FT_MulFix(a: FT_Long, b: FT_Long) FT_Long;
pub extern fn FT_DivFix(a: FT_Long, b: FT_Long) FT_Long;
pub extern fn FT_RoundFix(a: FT_Fixed) FT_Fixed;
pub extern fn FT_CeilFix(a: FT_Fixed) FT_Fixed;
pub extern fn FT_FloorFix(a: FT_Fixed) FT_Fixed;
pub extern fn FT_Vector_Transform(vector: [*c]FT_Vector, matrix: [*c]const FT_Matrix) void;
pub extern fn FT_Library_Version(library: FT_Library, amajor: [*c]FT_Int, aminor: [*c]FT_Int, apatch: [*c]FT_Int) void;
pub extern fn FT_Face_CheckTrueTypePatents(face: FT_Face) FT_Bool;
pub extern fn FT_Face_SetUnpatentedHinting(face: FT_Face, value: FT_Bool) FT_Bool;
pub const struct_FT_Color_ = extern struct {
    blue: FT_Byte = 0,
    green: FT_Byte = 0,
    red: FT_Byte = 0,
    alpha: FT_Byte = 0,
};
pub const FT_Color = struct_FT_Color_;
pub const struct_FT_Palette_Data_ = extern struct {
    num_palettes: FT_UShort = 0,
    palette_name_ids: [*c]const FT_UShort = null,
    palette_flags: [*c]const FT_UShort = null,
    num_palette_entries: FT_UShort = 0,
    palette_entry_name_ids: [*c]const FT_UShort = null,
};
pub const FT_Palette_Data = struct_FT_Palette_Data_;
pub extern fn FT_Palette_Data_Get(face: FT_Face, apalette: [*c]FT_Palette_Data) FT_Error;
pub extern fn FT_Palette_Select(face: FT_Face, palette_index: FT_UShort, apalette: [*c][*c]FT_Color) FT_Error;
pub extern fn FT_Palette_Set_Foreground_Color(face: FT_Face, foreground_color: FT_Color) FT_Error;
pub const struct_FT_LayerIterator_ = extern struct {
    num_layers: FT_UInt = 0,
    layer: FT_UInt = 0,
    p: [*c]FT_Byte = null,
};
pub const FT_LayerIterator = struct_FT_LayerIterator_;
pub extern fn FT_Get_Color_Glyph_Layer(face: FT_Face, base_glyph: FT_UInt, aglyph_index: [*c]FT_UInt, acolor_index: [*c]FT_UInt, iterator: [*c]FT_LayerIterator) FT_Bool;
pub const FT_COLR_PAINTFORMAT_COLR_LAYERS: c_int = 1;
pub const FT_COLR_PAINTFORMAT_SOLID: c_int = 2;
pub const FT_COLR_PAINTFORMAT_LINEAR_GRADIENT: c_int = 4;
pub const FT_COLR_PAINTFORMAT_RADIAL_GRADIENT: c_int = 6;
pub const FT_COLR_PAINTFORMAT_SWEEP_GRADIENT: c_int = 8;
pub const FT_COLR_PAINTFORMAT_GLYPH: c_int = 10;
pub const FT_COLR_PAINTFORMAT_COLR_GLYPH: c_int = 11;
pub const FT_COLR_PAINTFORMAT_TRANSFORM: c_int = 12;
pub const FT_COLR_PAINTFORMAT_TRANSLATE: c_int = 14;
pub const FT_COLR_PAINTFORMAT_SCALE: c_int = 16;
pub const FT_COLR_PAINTFORMAT_ROTATE: c_int = 24;
pub const FT_COLR_PAINTFORMAT_SKEW: c_int = 28;
pub const FT_COLR_PAINTFORMAT_COMPOSITE: c_int = 32;
pub const FT_COLR_PAINT_FORMAT_MAX: c_int = 33;
pub const FT_COLR_PAINTFORMAT_UNSUPPORTED: c_int = 255;
pub const enum_FT_PaintFormat_ = c_uint;
pub const FT_PaintFormat = enum_FT_PaintFormat_;
pub const struct_FT_ColorStopIterator_ = extern struct {
    num_color_stops: FT_UInt = 0,
    current_color_stop: FT_UInt = 0,
    p: [*c]FT_Byte = null,
    read_variable: FT_Bool = 0,
};
pub const FT_ColorStopIterator = struct_FT_ColorStopIterator_;
pub const struct_FT_ColorIndex_ = extern struct {
    palette_index: FT_UInt16 = 0,
    alpha: FT_F2Dot14 = 0,
};
pub const FT_ColorIndex = struct_FT_ColorIndex_;
pub const struct_FT_ColorStop_ = extern struct {
    stop_offset: FT_Fixed = 0,
    color: FT_ColorIndex = @import("std").mem.zeroes(FT_ColorIndex),
};
pub const FT_ColorStop = struct_FT_ColorStop_;
pub const FT_COLR_PAINT_EXTEND_PAD: c_int = 0;
pub const FT_COLR_PAINT_EXTEND_REPEAT: c_int = 1;
pub const FT_COLR_PAINT_EXTEND_REFLECT: c_int = 2;
pub const enum_FT_PaintExtend_ = c_uint;
pub const FT_PaintExtend = enum_FT_PaintExtend_;
pub const struct_FT_ColorLine_ = extern struct {
    extend: FT_PaintExtend = @import("std").mem.zeroes(FT_PaintExtend),
    color_stop_iterator: FT_ColorStopIterator = @import("std").mem.zeroes(FT_ColorStopIterator),
};
pub const FT_ColorLine = struct_FT_ColorLine_;
pub const struct_FT_Affine_23_ = extern struct {
    xx: FT_Fixed = 0,
    xy: FT_Fixed = 0,
    dx: FT_Fixed = 0,
    yx: FT_Fixed = 0,
    yy: FT_Fixed = 0,
    dy: FT_Fixed = 0,
};
pub const FT_Affine23 = struct_FT_Affine_23_;
pub const FT_COLR_COMPOSITE_CLEAR: c_int = 0;
pub const FT_COLR_COMPOSITE_SRC: c_int = 1;
pub const FT_COLR_COMPOSITE_DEST: c_int = 2;
pub const FT_COLR_COMPOSITE_SRC_OVER: c_int = 3;
pub const FT_COLR_COMPOSITE_DEST_OVER: c_int = 4;
pub const FT_COLR_COMPOSITE_SRC_IN: c_int = 5;
pub const FT_COLR_COMPOSITE_DEST_IN: c_int = 6;
pub const FT_COLR_COMPOSITE_SRC_OUT: c_int = 7;
pub const FT_COLR_COMPOSITE_DEST_OUT: c_int = 8;
pub const FT_COLR_COMPOSITE_SRC_ATOP: c_int = 9;
pub const FT_COLR_COMPOSITE_DEST_ATOP: c_int = 10;
pub const FT_COLR_COMPOSITE_XOR: c_int = 11;
pub const FT_COLR_COMPOSITE_PLUS: c_int = 12;
pub const FT_COLR_COMPOSITE_SCREEN: c_int = 13;
pub const FT_COLR_COMPOSITE_OVERLAY: c_int = 14;
pub const FT_COLR_COMPOSITE_DARKEN: c_int = 15;
pub const FT_COLR_COMPOSITE_LIGHTEN: c_int = 16;
pub const FT_COLR_COMPOSITE_COLOR_DODGE: c_int = 17;
pub const FT_COLR_COMPOSITE_COLOR_BURN: c_int = 18;
pub const FT_COLR_COMPOSITE_HARD_LIGHT: c_int = 19;
pub const FT_COLR_COMPOSITE_SOFT_LIGHT: c_int = 20;
pub const FT_COLR_COMPOSITE_DIFFERENCE: c_int = 21;
pub const FT_COLR_COMPOSITE_EXCLUSION: c_int = 22;
pub const FT_COLR_COMPOSITE_MULTIPLY: c_int = 23;
pub const FT_COLR_COMPOSITE_HSL_HUE: c_int = 24;
pub const FT_COLR_COMPOSITE_HSL_SATURATION: c_int = 25;
pub const FT_COLR_COMPOSITE_HSL_COLOR: c_int = 26;
pub const FT_COLR_COMPOSITE_HSL_LUMINOSITY: c_int = 27;
pub const FT_COLR_COMPOSITE_MAX: c_int = 28;
pub const enum_FT_Composite_Mode_ = c_uint;
pub const FT_Composite_Mode = enum_FT_Composite_Mode_;
pub const struct_FT_Opaque_Paint_ = extern struct {
    p: [*c]FT_Byte = null,
    insert_root_transform: FT_Bool = 0,
};
pub const FT_OpaquePaint = struct_FT_Opaque_Paint_;
pub const struct_FT_PaintColrLayers_ = extern struct {
    layer_iterator: FT_LayerIterator = @import("std").mem.zeroes(FT_LayerIterator),
};
pub const FT_PaintColrLayers = struct_FT_PaintColrLayers_;
pub const struct_FT_PaintSolid_ = extern struct {
    color: FT_ColorIndex = @import("std").mem.zeroes(FT_ColorIndex),
};
pub const FT_PaintSolid = struct_FT_PaintSolid_;
pub const struct_FT_PaintLinearGradient_ = extern struct {
    colorline: FT_ColorLine = @import("std").mem.zeroes(FT_ColorLine),
    p0: FT_Vector = @import("std").mem.zeroes(FT_Vector),
    p1: FT_Vector = @import("std").mem.zeroes(FT_Vector),
    p2: FT_Vector = @import("std").mem.zeroes(FT_Vector),
};
pub const FT_PaintLinearGradient = struct_FT_PaintLinearGradient_;
pub const struct_FT_PaintRadialGradient_ = extern struct {
    colorline: FT_ColorLine = @import("std").mem.zeroes(FT_ColorLine),
    c0: FT_Vector = @import("std").mem.zeroes(FT_Vector),
    r0: FT_Pos = 0,
    c1: FT_Vector = @import("std").mem.zeroes(FT_Vector),
    r1: FT_Pos = 0,
};
pub const FT_PaintRadialGradient = struct_FT_PaintRadialGradient_;
pub const struct_FT_PaintSweepGradient_ = extern struct {
    colorline: FT_ColorLine = @import("std").mem.zeroes(FT_ColorLine),
    center: FT_Vector = @import("std").mem.zeroes(FT_Vector),
    start_angle: FT_Fixed = 0,
    end_angle: FT_Fixed = 0,
};
pub const FT_PaintSweepGradient = struct_FT_PaintSweepGradient_;
pub const struct_FT_PaintGlyph_ = extern struct {
    paint: FT_OpaquePaint = @import("std").mem.zeroes(FT_OpaquePaint),
    glyphID: FT_UInt = 0,
};
pub const FT_PaintGlyph = struct_FT_PaintGlyph_;
pub const struct_FT_PaintColrGlyph_ = extern struct {
    glyphID: FT_UInt = 0,
};
pub const FT_PaintColrGlyph = struct_FT_PaintColrGlyph_;
pub const struct_FT_PaintTransform_ = extern struct {
    paint: FT_OpaquePaint = @import("std").mem.zeroes(FT_OpaquePaint),
    affine: FT_Affine23 = @import("std").mem.zeroes(FT_Affine23),
};
pub const FT_PaintTransform = struct_FT_PaintTransform_;
pub const struct_FT_PaintTranslate_ = extern struct {
    paint: FT_OpaquePaint = @import("std").mem.zeroes(FT_OpaquePaint),
    dx: FT_Fixed = 0,
    dy: FT_Fixed = 0,
};
pub const FT_PaintTranslate = struct_FT_PaintTranslate_;
pub const struct_FT_PaintScale_ = extern struct {
    paint: FT_OpaquePaint = @import("std").mem.zeroes(FT_OpaquePaint),
    scale_x: FT_Fixed = 0,
    scale_y: FT_Fixed = 0,
    center_x: FT_Fixed = 0,
    center_y: FT_Fixed = 0,
};
pub const FT_PaintScale = struct_FT_PaintScale_;
pub const struct_FT_PaintRotate_ = extern struct {
    paint: FT_OpaquePaint = @import("std").mem.zeroes(FT_OpaquePaint),
    angle: FT_Fixed = 0,
    center_x: FT_Fixed = 0,
    center_y: FT_Fixed = 0,
};
pub const FT_PaintRotate = struct_FT_PaintRotate_;
pub const struct_FT_PaintSkew_ = extern struct {
    paint: FT_OpaquePaint = @import("std").mem.zeroes(FT_OpaquePaint),
    x_skew_angle: FT_Fixed = 0,
    y_skew_angle: FT_Fixed = 0,
    center_x: FT_Fixed = 0,
    center_y: FT_Fixed = 0,
};
pub const FT_PaintSkew = struct_FT_PaintSkew_;
pub const struct_FT_PaintComposite_ = extern struct {
    source_paint: FT_OpaquePaint = @import("std").mem.zeroes(FT_OpaquePaint),
    composite_mode: FT_Composite_Mode = @import("std").mem.zeroes(FT_Composite_Mode),
    backdrop_paint: FT_OpaquePaint = @import("std").mem.zeroes(FT_OpaquePaint),
};
pub const FT_PaintComposite = struct_FT_PaintComposite_;
const union_unnamed_4 = extern union {
    colr_layers: FT_PaintColrLayers,
    glyph: FT_PaintGlyph,
    solid: FT_PaintSolid,
    linear_gradient: FT_PaintLinearGradient,
    radial_gradient: FT_PaintRadialGradient,
    sweep_gradient: FT_PaintSweepGradient,
    transform: FT_PaintTransform,
    translate: FT_PaintTranslate,
    scale: FT_PaintScale,
    rotate: FT_PaintRotate,
    skew: FT_PaintSkew,
    composite: FT_PaintComposite,
    colr_glyph: FT_PaintColrGlyph,
};
pub const struct_FT_COLR_Paint_ = extern struct {
    format: FT_PaintFormat = @import("std").mem.zeroes(FT_PaintFormat),
    u: union_unnamed_4 = @import("std").mem.zeroes(union_unnamed_4),
};
pub const FT_COLR_Paint = struct_FT_COLR_Paint_;
pub const FT_COLOR_INCLUDE_ROOT_TRANSFORM: c_int = 0;
pub const FT_COLOR_NO_ROOT_TRANSFORM: c_int = 1;
pub const FT_COLOR_ROOT_TRANSFORM_MAX: c_int = 2;
pub const enum_FT_Color_Root_Transform_ = c_uint;
pub const FT_Color_Root_Transform = enum_FT_Color_Root_Transform_;
pub const struct_FT_ClipBox_ = extern struct {
    bottom_left: FT_Vector = @import("std").mem.zeroes(FT_Vector),
    top_left: FT_Vector = @import("std").mem.zeroes(FT_Vector),
    top_right: FT_Vector = @import("std").mem.zeroes(FT_Vector),
    bottom_right: FT_Vector = @import("std").mem.zeroes(FT_Vector),
};
pub const FT_ClipBox = struct_FT_ClipBox_;
pub extern fn FT_Get_Color_Glyph_Paint(face: FT_Face, base_glyph: FT_UInt, root_transform: FT_Color_Root_Transform, paint: [*c]FT_OpaquePaint) FT_Bool;
pub extern fn FT_Get_Color_Glyph_ClipBox(face: FT_Face, base_glyph: FT_UInt, clip_box: [*c]FT_ClipBox) FT_Bool;
pub extern fn FT_Get_Paint_Layers(face: FT_Face, iterator: [*c]FT_LayerIterator, paint: [*c]FT_OpaquePaint) FT_Bool;
pub extern fn FT_Get_Colorline_Stops(face: FT_Face, color_stop: [*c]FT_ColorStop, iterator: [*c]FT_ColorStopIterator) FT_Bool;
pub extern fn FT_Get_Paint(face: FT_Face, opaque_paint: FT_OpaquePaint, paint: [*c]FT_COLR_Paint) FT_Bool;
pub extern fn FT_Bitmap_Init(abitmap: [*c]FT_Bitmap) void;
pub extern fn FT_Bitmap_New(abitmap: [*c]FT_Bitmap) void;
pub extern fn FT_Bitmap_Copy(library: FT_Library, source: [*c]const FT_Bitmap, target: [*c]FT_Bitmap) FT_Error;
pub extern fn FT_Bitmap_Embolden(library: FT_Library, bitmap: [*c]FT_Bitmap, xStrength: FT_Pos, yStrength: FT_Pos) FT_Error;
pub extern fn FT_Bitmap_Convert(library: FT_Library, source: [*c]const FT_Bitmap, target: [*c]FT_Bitmap, alignment: FT_Int) FT_Error;
pub extern fn FT_Bitmap_Blend(library: FT_Library, source: [*c]const FT_Bitmap, source_offset: FT_Vector, target: [*c]FT_Bitmap, atarget_offset: [*c]FT_Vector, color: FT_Color) FT_Error;
pub extern fn FT_GlyphSlot_Own_Bitmap(slot: FT_GlyphSlot) FT_Error;
pub extern fn FT_Bitmap_Done(library: FT_Library, bitmap: [*c]FT_Bitmap) FT_Error;

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
pub const FT2BUILD_H_ = "";
pub const FTHEADER_H_ = "";
pub const FT_BEGIN_HEADER = "";
pub const FT_END_HEADER = "";
pub const FT_CONFIG_CONFIG_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:117:9
pub const FT_CONFIG_STANDARD_LIBRARY_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:132:9
pub const FT_CONFIG_OPTIONS_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:147:9
pub const FT_CONFIG_MODULES_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:163:9
pub const FT_FREETYPE_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:180:9
pub const FT_ERRORS_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:195:9
pub const FT_MODULE_ERRORS_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:208:9
pub const FT_SYSTEM_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:224:9
pub const FT_IMAGE_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:240:9
pub const FT_TYPES_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:255:9
pub const FT_LIST_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:270:9
pub const FT_OUTLINE_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:283:9
pub const FT_SIZES_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:296:9
pub const FT_MODULE_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:309:9
pub const FT_RENDER_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:322:9
pub const FT_DRIVER_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:335:9
pub const FT_AUTOHINTER_H = FT_DRIVER_H;
pub const FT_CFF_DRIVER_H = FT_DRIVER_H;
pub const FT_TRUETYPE_DRIVER_H = FT_DRIVER_H;
pub const FT_PCF_DRIVER_H = FT_DRIVER_H;
pub const FT_TYPE1_TABLES_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:408:9
pub const FT_TRUETYPE_IDS_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:423:9
pub const FT_TRUETYPE_TABLES_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:436:9
pub const FT_TRUETYPE_TAGS_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:450:9
pub const FT_BDF_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:463:9
pub const FT_CID_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:476:9
pub const FT_GZIP_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:489:9
pub const FT_LZW_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:502:9
pub const FT_BZIP2_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:515:9
pub const FT_WINFONTS_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:528:9
pub const FT_GLYPH_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:541:9
pub const FT_BITMAP_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:554:9
pub const FT_BBOX_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:567:9
pub const FT_CACHE_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:580:9
pub const FT_MAC_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:597:9
pub const FT_MULTIPLE_MASTERS_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:610:9
pub const FT_SFNT_NAMES_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:624:9
pub const FT_OPENTYPE_VALIDATE_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:638:9
pub const FT_GX_VALIDATE_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:652:9
pub const FT_PFR_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:665:9
pub const FT_STROKER_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:677:9
pub const FT_SYNTHESIS_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:689:9
pub const FT_FONT_FORMATS_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:701:9
pub const FT_XFREE86_H = FT_FONT_FORMATS_H;
pub const FT_TRIGONOMETRY_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:717:9
pub const FT_LCD_FILTER_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:729:9
pub const FT_INCREMENTAL_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:741:9
pub const FT_GASP_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:753:9
pub const FT_ADVANCES_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:765:9
pub const FT_COLOR_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:777:9
pub const FT_OTSVG_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:789:9
pub const FT_ERROR_DEFINITIONS_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:795:9
pub const FT_PARAMETER_TAGS_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:796:9
pub const FT_UNPATENTED_HINTING_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:799:9
pub const FT_TRUETYPE_UNPATENTED_H = @compileError("unable to translate macro: undefined identifier `freetype`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/ftheader.h:800:9
pub const FT_CACHE_IMAGE_H = FT_CACHE_H;
pub const FT_CACHE_SMALL_BITMAPS_H = FT_CACHE_H;
pub const FT_CACHE_CHARMAP_H = FT_CACHE_H;
pub const FT_CACHE_MANAGER_H = FT_CACHE_H;
pub const FT_CACHE_INTERNAL_MRU_H = FT_CACHE_H;
pub const FT_CACHE_INTERNAL_MANAGER_H = FT_CACHE_H;
pub const FT_CACHE_INTERNAL_CACHE_H = FT_CACHE_H;
pub const FT_CACHE_INTERNAL_GLYPH_H = FT_CACHE_H;
pub const FT_CACHE_INTERNAL_IMAGE_H = FT_CACHE_H;
pub const FT_CACHE_INTERNAL_SBITS_H = FT_CACHE_H;
pub const FREETYPE_H_ = "";
pub const FTCONFIG_H_ = "";
pub const FTOPTION_H_ = "";
pub const FT_CONFIG_OPTION_ENVIRONMENT_PROPERTIES = "";
pub const FT_CONFIG_OPTION_INLINE_MULFIX = "";
pub const FT_CONFIG_OPTION_USE_LZW = "";
pub const FT_CONFIG_OPTION_USE_ZLIB = "";
pub const FT_CONFIG_OPTION_POSTSCRIPT_NAMES = "";
pub const FT_CONFIG_OPTION_ADOBE_GLYPH_LIST = "";
pub const FT_CONFIG_OPTION_MAC_FONTS = "";
pub const FT_CONFIG_OPTION_GUESSING_EMBEDDED_RFORK = "";
pub const FT_CONFIG_OPTION_INCREMENTAL = "";
pub const FT_RENDER_POOL_SIZE = @as(c_long, 16384);
pub const FT_MAX_MODULES = @as(c_int, 32);
pub const FT_CONFIG_OPTION_SVG = "";
pub const TT_CONFIG_OPTION_EMBEDDED_BITMAPS = "";
pub const TT_CONFIG_OPTION_COLOR_LAYERS = "";
pub const TT_CONFIG_OPTION_POSTSCRIPT_NAMES = "";
pub const TT_CONFIG_OPTION_SFNT_NAMES = "";
pub const TT_CONFIG_CMAP_FORMAT_0 = "";
pub const TT_CONFIG_CMAP_FORMAT_2 = "";
pub const TT_CONFIG_CMAP_FORMAT_4 = "";
pub const TT_CONFIG_CMAP_FORMAT_6 = "";
pub const TT_CONFIG_CMAP_FORMAT_8 = "";
pub const TT_CONFIG_CMAP_FORMAT_10 = "";
pub const TT_CONFIG_CMAP_FORMAT_12 = "";
pub const TT_CONFIG_CMAP_FORMAT_13 = "";
pub const TT_CONFIG_CMAP_FORMAT_14 = "";
pub const TT_CONFIG_OPTION_BYTECODE_INTERPRETER = "";
pub const TT_CONFIG_OPTION_SUBPIXEL_HINTING = "";
pub const TT_CONFIG_OPTION_GX_VAR_SUPPORT = "";
pub const TT_CONFIG_OPTION_BDF = "";
pub const TT_CONFIG_OPTION_MAX_RUNNABLE_OPCODES = @as(c_long, 1000000);
pub const T1_MAX_DICT_DEPTH = @as(c_int, 5);
pub const T1_MAX_SUBRS_CALLS = @as(c_int, 16);
pub const T1_MAX_CHARSTRINGS_OPERANDS = @as(c_int, 256);
pub const CFF_CONFIG_OPTION_DARKENING_PARAMETER_X1 = @as(c_int, 500);
pub const CFF_CONFIG_OPTION_DARKENING_PARAMETER_Y1 = @as(c_int, 400);
pub const CFF_CONFIG_OPTION_DARKENING_PARAMETER_X2 = @as(c_int, 1000);
pub const CFF_CONFIG_OPTION_DARKENING_PARAMETER_Y2 = @as(c_int, 275);
pub const CFF_CONFIG_OPTION_DARKENING_PARAMETER_X3 = @as(c_int, 1667);
pub const CFF_CONFIG_OPTION_DARKENING_PARAMETER_Y3 = @as(c_int, 275);
pub const CFF_CONFIG_OPTION_DARKENING_PARAMETER_X4 = @as(c_int, 2333);
pub const CFF_CONFIG_OPTION_DARKENING_PARAMETER_Y4 = @as(c_int, 0);
pub const AF_CONFIG_OPTION_CJK = "";
pub const AF_CONFIG_OPTION_INDIC = "";
pub const TT_USE_BYTECODE_INTERPRETER = "";
pub const TT_SUPPORT_SUBPIXEL_HINTING_MINIMAL = "";
pub const TT_SUPPORT_COLRV1 = "";
pub const FTSTDLIB_H_ = "";
pub const __STDC_VERSION_STDDEF_H__ = @as(c_long, 202311);
pub const NULL = __helpers.cast(?*anyopaque, @as(c_int, 0));
pub const offsetof = @compileError("unable to translate macro: undefined identifier `__builtin_offsetof`"); // /Users/adriaan/.local/zig/lib/compiler/aro/include/stddef.h:18:9
pub const ft_ptrdiff_t = ptrdiff_t;
pub const _GCC_LIMITS_H_ = "";
pub const __CLANG_LIMITS_H = "";
pub const _LIMITS_H = "";
pub const _FEATURES_H = "";
pub const _BSD_SOURCE = @as(c_int, 1);
pub const _XOPEN_SOURCE = @as(c_int, 700);
pub const __restrict = @compileError("unable to translate C expr: unexpected token 'restrict'"); // /Users/adriaan/.local/zig/lib/libc/include/generic-musl/features.h:20:9
pub const __inline = @compileError("unable to translate C expr: unexpected token 'inline'"); // /Users/adriaan/.local/zig/lib/libc/include/generic-musl/features.h:26:9
pub const __REDIR = @compileError("unable to translate C expr: unexpected token '__typeof__'"); // /Users/adriaan/.local/zig/lib/libc/include/generic-musl/features.h:38:9
pub const _REDIR_TIME64 = @as(c_int, 1);
pub const __BYTE_ORDER = @as(c_int, 1234);
pub const __LONG_MAX = @as(c_long, 0x7fffffff);
pub const __LITTLE_ENDIAN = @as(c_int, 1234);
pub const __BIG_ENDIAN = @as(c_int, 4321);
pub const __USE_TIME_BITS64 = @as(c_int, 1);
pub const MB_LEN_MAX = @as(c_int, 4);
pub const PIPE_BUF = @as(c_int, 4096);
pub const FILESIZEBITS = @as(c_int, 64);
pub const NAME_MAX = @as(c_int, 255);
pub const PATH_MAX = @as(c_int, 4096);
pub const NGROUPS_MAX = @as(c_int, 32);
pub const ARG_MAX = __helpers.promoteIntLiteral(c_int, 131072, .decimal);
pub const IOV_MAX = @as(c_int, 1024);
pub const SYMLOOP_MAX = @as(c_int, 40);
pub const WORD_BIT = @as(c_int, 32);
pub const SSIZE_MAX = LONG_MAX;
pub const TZNAME_MAX = @as(c_int, 6);
pub const TTY_NAME_MAX = @as(c_int, 32);
pub const HOST_NAME_MAX = @as(c_int, 255);
pub const LONG_BIT = @as(c_int, 32);
pub const PTHREAD_KEYS_MAX = @as(c_int, 128);
pub const PTHREAD_STACK_MIN = @as(c_int, 2048);
pub const PTHREAD_DESTRUCTOR_ITERATIONS = @as(c_int, 4);
pub const SEM_VALUE_MAX = __helpers.promoteIntLiteral(c_int, 0x7fffffff, .hex);
pub const SEM_NSEMS_MAX = @as(c_int, 256);
pub const DELAYTIMER_MAX = __helpers.promoteIntLiteral(c_int, 0x7fffffff, .hex);
pub const MQ_PRIO_MAX = __helpers.promoteIntLiteral(c_int, 32768, .decimal);
pub const LOGIN_NAME_MAX = @as(c_int, 256);
pub const BC_BASE_MAX = @as(c_int, 99);
pub const BC_DIM_MAX = @as(c_int, 2048);
pub const BC_SCALE_MAX = @as(c_int, 99);
pub const BC_STRING_MAX = @as(c_int, 1000);
pub const CHARCLASS_NAME_MAX = @as(c_int, 14);
pub const COLL_WEIGHTS_MAX = @as(c_int, 2);
pub const EXPR_NEST_MAX = @as(c_int, 32);
pub const LINE_MAX = @as(c_int, 4096);
pub const RE_DUP_MAX = @as(c_int, 255);
pub const NL_ARGMAX = @as(c_int, 9);
pub const NL_MSGMAX = @as(c_int, 32767);
pub const NL_SETMAX = @as(c_int, 255);
pub const NL_TEXTMAX = @as(c_int, 2048);
pub const NZERO = @as(c_int, 20);
pub const NL_LANGMAX = @as(c_int, 32);
pub const NL_NMAX = @as(c_int, 16);
pub const _POSIX_AIO_LISTIO_MAX = @as(c_int, 2);
pub const _POSIX_AIO_MAX = @as(c_int, 1);
pub const _POSIX_ARG_MAX = @as(c_int, 4096);
pub const _POSIX_CHILD_MAX = @as(c_int, 25);
pub const _POSIX_CLOCKRES_MIN = __helpers.promoteIntLiteral(c_int, 20000000, .decimal);
pub const _POSIX_DELAYTIMER_MAX = @as(c_int, 32);
pub const _POSIX_HOST_NAME_MAX = @as(c_int, 255);
pub const _POSIX_LINK_MAX = @as(c_int, 8);
pub const _POSIX_LOGIN_NAME_MAX = @as(c_int, 9);
pub const _POSIX_MAX_CANON = @as(c_int, 255);
pub const _POSIX_MAX_INPUT = @as(c_int, 255);
pub const _POSIX_MQ_OPEN_MAX = @as(c_int, 8);
pub const _POSIX_MQ_PRIO_MAX = @as(c_int, 32);
pub const _POSIX_NAME_MAX = @as(c_int, 14);
pub const _POSIX_NGROUPS_MAX = @as(c_int, 8);
pub const _POSIX_OPEN_MAX = @as(c_int, 20);
pub const _POSIX_PATH_MAX = @as(c_int, 256);
pub const _POSIX_PIPE_BUF = @as(c_int, 512);
pub const _POSIX_RE_DUP_MAX = @as(c_int, 255);
pub const _POSIX_RTSIG_MAX = @as(c_int, 8);
pub const _POSIX_SEM_NSEMS_MAX = @as(c_int, 256);
pub const _POSIX_SEM_VALUE_MAX = @as(c_int, 32767);
pub const _POSIX_SIGQUEUE_MAX = @as(c_int, 32);
pub const _POSIX_SSIZE_MAX = @as(c_int, 32767);
pub const _POSIX_STREAM_MAX = @as(c_int, 8);
pub const _POSIX_SS_REPL_MAX = @as(c_int, 4);
pub const _POSIX_SYMLINK_MAX = @as(c_int, 255);
pub const _POSIX_SYMLOOP_MAX = @as(c_int, 8);
pub const _POSIX_THREAD_DESTRUCTOR_ITERATIONS = @as(c_int, 4);
pub const _POSIX_THREAD_KEYS_MAX = @as(c_int, 128);
pub const _POSIX_THREAD_THREADS_MAX = @as(c_int, 64);
pub const _POSIX_TIMER_MAX = @as(c_int, 32);
pub const _POSIX_TRACE_EVENT_NAME_MAX = @as(c_int, 30);
pub const _POSIX_TRACE_NAME_MAX = @as(c_int, 8);
pub const _POSIX_TRACE_SYS_MAX = @as(c_int, 8);
pub const _POSIX_TRACE_USER_EVENT_MAX = @as(c_int, 32);
pub const _POSIX_TTY_NAME_MAX = @as(c_int, 9);
pub const _POSIX_TZNAME_MAX = @as(c_int, 6);
pub const _POSIX2_BC_BASE_MAX = @as(c_int, 99);
pub const _POSIX2_BC_DIM_MAX = @as(c_int, 2048);
pub const _POSIX2_BC_SCALE_MAX = @as(c_int, 99);
pub const _POSIX2_BC_STRING_MAX = @as(c_int, 1000);
pub const _POSIX2_CHARCLASS_NAME_MAX = @as(c_int, 14);
pub const _POSIX2_COLL_WEIGHTS_MAX = @as(c_int, 2);
pub const _POSIX2_EXPR_NEST_MAX = @as(c_int, 32);
pub const _POSIX2_LINE_MAX = @as(c_int, 2048);
pub const _POSIX2_RE_DUP_MAX = @as(c_int, 255);
pub const _XOPEN_IOV_MAX = @as(c_int, 16);
pub const _XOPEN_NAME_MAX = @as(c_int, 255);
pub const _XOPEN_PATH_MAX = @as(c_int, 1024);
pub const LONG_LONG_MAX = __LONG_LONG_MAX__;
pub const LONG_LONG_MIN = -__LONG_LONG_MAX__ - @as(c_longlong, 1);
pub const ULONG_LONG_MAX = (__LONG_LONG_MAX__ * @as(c_ulonglong, 2)) + @as(c_ulonglong, 1);
pub const SCHAR_MAX = __SCHAR_MAX__;
pub const SHRT_MAX = __SHRT_MAX__;
pub const INT_MAX = __INT_MAX__;
pub const LONG_MAX = __LONG_MAX__;
pub const SCHAR_MIN = -__SCHAR_MAX__ - @as(c_int, 1);
pub const SHRT_MIN = -__SHRT_MAX__ - @as(c_int, 1);
pub const INT_MIN = -__INT_MAX__ - @as(c_int, 1);
pub const LONG_MIN = -__LONG_MAX__ - @as(c_long, 1);
pub const UCHAR_MAX = (__SCHAR_MAX__ * @as(c_int, 2)) + @as(c_int, 1);
pub const USHRT_MAX = (__SHRT_MAX__ * @as(c_int, 2)) + @as(c_int, 1);
pub const UINT_MAX = (__INT_MAX__ * @as(c_uint, 2)) + @as(c_uint, 1);
pub const ULONG_MAX = (__LONG_MAX__ * @as(c_ulong, 2)) + @as(c_ulong, 1);
pub const CHAR_BIT = __CHAR_BIT__;
pub const CHAR_MIN = @as(c_int, 0);
pub const CHAR_MAX = UCHAR_MAX;
pub const LLONG_MIN = -__LONG_LONG_MAX__ - @as(c_longlong, 1);
pub const LLONG_MAX = __LONG_LONG_MAX__;
pub const ULLONG_MAX = (__LONG_LONG_MAX__ * @as(c_ulonglong, 2)) + @as(c_ulonglong, 1);
pub const FT_CHAR_BIT = CHAR_BIT;
pub const FT_USHORT_MAX = USHRT_MAX;
pub const FT_INT_MAX = INT_MAX;
pub const FT_INT_MIN = INT_MIN;
pub const FT_UINT_MAX = UINT_MAX;
pub const FT_LONG_MIN = LONG_MIN;
pub const FT_LONG_MAX = LONG_MAX;
pub const FT_ULONG_MAX = ULONG_MAX;
pub const FT_LLONG_MAX = LLONG_MAX;
pub const FT_LLONG_MIN = LLONG_MIN;
pub const FT_ULLONG_MAX = ULLONG_MAX;
pub const _STRING_H = "";
pub const __NEED_size_t = "";
pub const __NEED_locale_t = "";
pub const __DEFINED_size_t = "";
pub const __DEFINED_locale_t = "";
pub const _STRINGS_H = "";
pub const ft_memchr = memchr;
pub const ft_memcmp = memcmp;
pub const ft_memcpy = memcpy;
pub const ft_memmove = memmove;
pub const ft_memset = memset;
pub const ft_strcat = strcat;
pub const ft_strcmp = strcmp;
pub const ft_strcpy = strcpy;
pub const ft_strlen = strlen;
pub const ft_strncmp = strncmp;
pub const ft_strncpy = strncpy;
pub const ft_strrchr = strrchr;
pub const ft_strstr = strstr;
pub const _STDIO_H = "";
pub const __NEED_FILE = "";
pub const __NEED___isoc_va_list = "";
pub const __NEED_ssize_t = "";
pub const __NEED_off_t = "";
pub const __NEED_va_list = "";
pub const __DEFINED_ssize_t = "";
pub const __DEFINED_off_t = "";
pub const __DEFINED_FILE = "";
pub const __DEFINED_va_list = "";
pub const __DEFINED___isoc_va_list = "";
pub const EOF = -@as(c_int, 1);
pub const SEEK_SET = @as(c_int, 0);
pub const SEEK_CUR = @as(c_int, 1);
pub const SEEK_END = @as(c_int, 2);
pub const _IOFBF = @as(c_int, 0);
pub const _IOLBF = @as(c_int, 1);
pub const _IONBF = @as(c_int, 2);
pub const BUFSIZ = @as(c_int, 1024);
pub const FILENAME_MAX = @as(c_int, 4096);
pub const FOPEN_MAX = @as(c_int, 1000);
pub const TMP_MAX = @as(c_int, 10000);
pub const L_tmpnam = @as(c_int, 20);
pub const L_ctermid = @as(c_int, 20);
pub const P_tmpdir = "/tmp";
pub const L_cuserid = @as(c_int, 20);
pub const FT_FILE = FILE;
pub const ft_fclose = fclose;
pub const ft_fopen = fopen;
pub const ft_fread = fread;
pub const ft_fseek = fseek;
pub const ft_ftell = ftell;
pub const ft_snprintf = snprintf;
pub const _STDLIB_H = "";
pub const __NEED_wchar_t = "";
pub const __DEFINED_wchar_t = "";
pub const EXIT_FAILURE = @as(c_int, 1);
pub const EXIT_SUCCESS = @as(c_int, 0);
pub const MB_CUR_MAX = __ctype_get_mb_cur_max();
pub const RAND_MAX = __helpers.promoteIntLiteral(c_int, 0x7fffffff, .hex);
pub const WNOHANG = @as(c_int, 1);
pub const WUNTRACED = @as(c_int, 2);
pub inline fn WEXITSTATUS(s: anytype) @TypeOf((s & __helpers.promoteIntLiteral(c_int, 0xff00, .hex)) >> @as(c_int, 8)) {
    _ = &s;
    return (s & __helpers.promoteIntLiteral(c_int, 0xff00, .hex)) >> @as(c_int, 8);
}
pub inline fn WTERMSIG(s: anytype) @TypeOf(s & @as(c_int, 0x7f)) {
    _ = &s;
    return s & @as(c_int, 0x7f);
}
pub inline fn WSTOPSIG(s: anytype) @TypeOf(WEXITSTATUS(s)) {
    _ = &s;
    return WEXITSTATUS(s);
}
pub inline fn WIFEXITED(s: anytype) @TypeOf(!(WTERMSIG(s) != 0)) {
    _ = &s;
    return !(WTERMSIG(s) != 0);
}
pub inline fn WIFSTOPPED(s: anytype) @TypeOf(__helpers.cast(c_short, ((s & __helpers.promoteIntLiteral(c_int, 0xffff, .hex)) * __helpers.promoteIntLiteral(c_uint, 0x10001, .hex)) >> @as(c_int, 8)) > @as(c_int, 0x7f00)) {
    _ = &s;
    return __helpers.cast(c_short, ((s & __helpers.promoteIntLiteral(c_int, 0xffff, .hex)) * __helpers.promoteIntLiteral(c_uint, 0x10001, .hex)) >> @as(c_int, 8)) > @as(c_int, 0x7f00);
}
pub inline fn WIFSIGNALED(s: anytype) @TypeOf(((s & __helpers.promoteIntLiteral(c_int, 0xffff, .hex)) - @as(c_uint, 1)) < @as(c_uint, 0xff)) {
    _ = &s;
    return ((s & __helpers.promoteIntLiteral(c_int, 0xffff, .hex)) - @as(c_uint, 1)) < @as(c_uint, 0xff);
}
pub const _ALLOCA_H = "";
pub inline fn WCOREDUMP(s: anytype) @TypeOf(s & @as(c_int, 0x80)) {
    _ = &s;
    return s & @as(c_int, 0x80);
}
pub inline fn WIFCONTINUED(s: anytype) @TypeOf(s == __helpers.promoteIntLiteral(c_int, 0xffff, .hex)) {
    _ = &s;
    return s == __helpers.promoteIntLiteral(c_int, 0xffff, .hex);
}
pub const ft_qsort = qsort;
pub const ft_scalloc = calloc;
pub const ft_sfree = free;
pub const ft_smalloc = malloc;
pub const ft_srealloc = realloc;
pub const ft_strtol = strtol;
pub const ft_getenv = getenv;
pub const _SETJMP_H = "";
pub const ft_jmp_buf = jmp_buf;
pub const ft_longjmp = longjmp;
pub inline fn ft_setjmp(b: anytype) @TypeOf(setjmp([*c]ft_jmp_buf.* & b)) {
    _ = &b;
    return setjmp([*c]ft_jmp_buf.* & b);
}
pub const __STDC_VERSION_STDARG_H__ = @as(c_int, 0);
pub const va_start = @compileError("unable to translate macro: undefined identifier `__builtin_va_start`"); // /Users/adriaan/.local/zig/lib/compiler/aro/include/stdarg.h:12:9
pub const va_end = @compileError("unable to translate macro: undefined identifier `__builtin_va_end`"); // /Users/adriaan/.local/zig/lib/compiler/aro/include/stdarg.h:14:9
pub const va_arg = @compileError("unable to translate macro: undefined identifier `__builtin_va_arg`"); // /Users/adriaan/.local/zig/lib/compiler/aro/include/stdarg.h:15:9
pub const __va_copy = @compileError("unable to translate macro: undefined identifier `__builtin_va_copy`"); // /Users/adriaan/.local/zig/lib/compiler/aro/include/stdarg.h:18:9
pub const va_copy = @compileError("unable to translate macro: undefined identifier `__builtin_va_copy`"); // /Users/adriaan/.local/zig/lib/compiler/aro/include/stdarg.h:22:9
pub const __GNUC_VA_LIST = @as(c_int, 1);
pub const FREETYPE_CONFIG_INTEGER_TYPES_H_ = "";
pub const FT_SIZEOF_INT = __helpers.div(@as(c_int, 32), FT_CHAR_BIT);
pub const FT_SIZEOF_LONG = __helpers.div(@as(c_int, 32), FT_CHAR_BIT);
pub const FT_SIZEOF_LONG_LONG = __helpers.div(@as(c_int, 64), FT_CHAR_BIT);
pub const FT_INT64 = c_longlong;
pub const FT_UINT64 = c_ulonglong;
pub const FREETYPE_CONFIG_PUBLIC_MACROS_H_ = "";
pub const FT_PUBLIC_FUNCTION_ATTRIBUTE = @compileError("unable to translate macro: undefined identifier `visibility`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/public-macros.h:76:9
pub const FT_EXPORT = @compileError("unable to translate C expr: unexpected token 'extern'"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/public-macros.h:104:9
pub const FT_UNUSED = @compileError("unable to translate C expr: expected ')' instead got '='"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/config/public-macros.h:115:9
pub const FT_STATIC_CAST = __helpers.CAST_OR_CALL;
pub const FT_REINTERPRET_CAST = __helpers.CAST_OR_CALL;
pub inline fn FT_STATIC_BYTE_CAST(@"type": anytype, @"var": anytype) @TypeOf(@"type"(u8)(@"var")) {
    _ = &@"type";
    _ = &@"var";
    return @"type"(u8)(@"var");
}
pub const FREETYPE_CONFIG_MAC_SUPPORT_H_ = "";
pub const FTTYPES_H_ = "";
pub const FTSYSTEM_H_ = "";
pub const FTIMAGE_H_ = "";
pub const ft_pixel_mode_none = FT_PIXEL_MODE_NONE;
pub const ft_pixel_mode_mono = FT_PIXEL_MODE_MONO;
pub const ft_pixel_mode_grays = FT_PIXEL_MODE_GRAY;
pub const ft_pixel_mode_pal2 = FT_PIXEL_MODE_GRAY2;
pub const ft_pixel_mode_pal4 = FT_PIXEL_MODE_GRAY4;
pub const FT_OUTLINE_CONTOURS_MAX = USHRT_MAX;
pub const FT_OUTLINE_POINTS_MAX = USHRT_MAX;
pub const FT_OUTLINE_NONE = @as(c_int, 0x0);
pub const FT_OUTLINE_OWNER = @as(c_int, 0x1);
pub const FT_OUTLINE_EVEN_ODD_FILL = @as(c_int, 0x2);
pub const FT_OUTLINE_REVERSE_FILL = @as(c_int, 0x4);
pub const FT_OUTLINE_IGNORE_DROPOUTS = @as(c_int, 0x8);
pub const FT_OUTLINE_SMART_DROPOUTS = @as(c_int, 0x10);
pub const FT_OUTLINE_INCLUDE_STUBS = @as(c_int, 0x20);
pub const FT_OUTLINE_OVERLAP = @as(c_int, 0x40);
pub const FT_OUTLINE_HIGH_PRECISION = @as(c_int, 0x100);
pub const FT_OUTLINE_SINGLE_PASS = @as(c_int, 0x200);
pub const ft_outline_none = FT_OUTLINE_NONE;
pub const ft_outline_owner = FT_OUTLINE_OWNER;
pub const ft_outline_even_odd_fill = FT_OUTLINE_EVEN_ODD_FILL;
pub const ft_outline_reverse_fill = FT_OUTLINE_REVERSE_FILL;
pub const ft_outline_ignore_dropouts = FT_OUTLINE_IGNORE_DROPOUTS;
pub const ft_outline_high_precision = FT_OUTLINE_HIGH_PRECISION;
pub const ft_outline_single_pass = FT_OUTLINE_SINGLE_PASS;
pub inline fn FT_CURVE_TAG(flag: anytype) @TypeOf(flag & @as(c_int, 0x03)) {
    _ = &flag;
    return flag & @as(c_int, 0x03);
}
pub const FT_CURVE_TAG_ON = @as(c_int, 0x01);
pub const FT_CURVE_TAG_CONIC = @as(c_int, 0x00);
pub const FT_CURVE_TAG_CUBIC = @as(c_int, 0x02);
pub const FT_CURVE_TAG_HAS_SCANMODE = @as(c_int, 0x04);
pub const FT_CURVE_TAG_TOUCH_X = @as(c_int, 0x08);
pub const FT_CURVE_TAG_TOUCH_Y = @as(c_int, 0x10);
pub const FT_CURVE_TAG_TOUCH_BOTH = FT_CURVE_TAG_TOUCH_X | FT_CURVE_TAG_TOUCH_Y;
pub const FT_Curve_Tag_On = FT_CURVE_TAG_ON;
pub const FT_Curve_Tag_Conic = FT_CURVE_TAG_CONIC;
pub const FT_Curve_Tag_Cubic = FT_CURVE_TAG_CUBIC;
pub const FT_Curve_Tag_Touch_X = FT_CURVE_TAG_TOUCH_X;
pub const FT_Curve_Tag_Touch_Y = FT_CURVE_TAG_TOUCH_Y;
pub const FT_Outline_MoveTo_Func = FT_Outline_MoveToFunc;
pub const FT_Outline_LineTo_Func = FT_Outline_LineToFunc;
pub const FT_Outline_ConicTo_Func = FT_Outline_ConicToFunc;
pub const FT_Outline_CubicTo_Func = FT_Outline_CubicToFunc;
pub const FT_IMAGE_TAG = @compileError("unable to translate C expr: unexpected token '='"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/ftimage.h:710:9
pub const ft_glyph_format_none = FT_GLYPH_FORMAT_NONE;
pub const ft_glyph_format_composite = FT_GLYPH_FORMAT_COMPOSITE;
pub const ft_glyph_format_bitmap = FT_GLYPH_FORMAT_BITMAP;
pub const ft_glyph_format_outline = FT_GLYPH_FORMAT_OUTLINE;
pub const ft_glyph_format_plotter = FT_GLYPH_FORMAT_PLOTTER;
pub const FT_Raster_Span_Func = FT_SpanFunc;
pub const FT_RASTER_FLAG_DEFAULT = @as(c_int, 0x0);
pub const FT_RASTER_FLAG_AA = @as(c_int, 0x1);
pub const FT_RASTER_FLAG_DIRECT = @as(c_int, 0x2);
pub const FT_RASTER_FLAG_CLIP = @as(c_int, 0x4);
pub const FT_RASTER_FLAG_SDF = @as(c_int, 0x8);
pub const ft_raster_flag_default = FT_RASTER_FLAG_DEFAULT;
pub const ft_raster_flag_aa = FT_RASTER_FLAG_AA;
pub const ft_raster_flag_direct = FT_RASTER_FLAG_DIRECT;
pub const ft_raster_flag_clip = FT_RASTER_FLAG_CLIP;
pub const FT_Raster_New_Func = FT_Raster_NewFunc;
pub const FT_Raster_Done_Func = FT_Raster_DoneFunc;
pub const FT_Raster_Reset_Func = FT_Raster_ResetFunc;
pub const FT_Raster_Set_Mode_Func = FT_Raster_SetModeFunc;
pub const FT_Raster_Render_Func = FT_Raster_RenderFunc;
pub inline fn FT_MAKE_TAG(_x1: anytype, _x2: anytype, _x3: anytype, _x4: anytype) @TypeOf((((FT_STATIC_BYTE_CAST(FT_Tag, _x1) << @as(c_int, 24)) | (FT_STATIC_BYTE_CAST(FT_Tag, _x2) << @as(c_int, 16))) | (FT_STATIC_BYTE_CAST(FT_Tag, _x3) << @as(c_int, 8))) | FT_STATIC_BYTE_CAST(FT_Tag, _x4)) {
    _ = &_x1;
    _ = &_x2;
    _ = &_x3;
    _ = &_x4;
    return (((FT_STATIC_BYTE_CAST(FT_Tag, _x1) << @as(c_int, 24)) | (FT_STATIC_BYTE_CAST(FT_Tag, _x2) << @as(c_int, 16))) | (FT_STATIC_BYTE_CAST(FT_Tag, _x3) << @as(c_int, 8))) | FT_STATIC_BYTE_CAST(FT_Tag, _x4);
}
pub inline fn FT_IS_EMPTY(list: anytype) @TypeOf(list.head == @as(c_int, 0)) {
    _ = &list;
    return list.head == @as(c_int, 0);
}
pub inline fn FT_BOOL(x: anytype) @TypeOf(FT_STATIC_CAST(FT_Bool, x != @as(c_int, 0))) {
    _ = &x;
    return FT_STATIC_CAST(FT_Bool, x != @as(c_int, 0));
}
pub const FT_ERR_XCAT = @compileError("unable to translate C expr: unexpected token '##'"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/fttypes.h:596:9
pub inline fn FT_ERR_CAT(x: anytype, y: anytype) @TypeOf(FT_ERR_XCAT(x, y)) {
    _ = &x;
    _ = &y;
    return FT_ERR_XCAT(x, y);
}
pub const FT_ERR = @compileError("unable to translate macro: undefined identifier `FT_ERR_PREFIX`"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/fttypes.h:601:9
pub inline fn FT_ERROR_BASE(x: anytype) @TypeOf(x & @as(c_int, 0xFF)) {
    _ = &x;
    return x & @as(c_int, 0xFF);
}
pub inline fn FT_ERROR_MODULE(x: anytype) @TypeOf(x & @as(c_uint, 0xFF00)) {
    _ = &x;
    return x & @as(c_uint, 0xFF00);
}
pub inline fn FT_ERR_EQ(x: anytype, e: anytype) @TypeOf(FT_ERROR_BASE(x) == FT_ERROR_BASE(FT_ERR(e))) {
    _ = &x;
    _ = &e;
    return FT_ERROR_BASE(x) == FT_ERROR_BASE(FT_ERR(e));
}
pub inline fn FT_ERR_NEQ(x: anytype, e: anytype) @TypeOf(FT_ERROR_BASE(x) != FT_ERROR_BASE(FT_ERR(e))) {
    _ = &x;
    _ = &e;
    return FT_ERROR_BASE(x) != FT_ERROR_BASE(FT_ERR(e));
}
pub const FTERRORS_H_ = "";
pub const __FTERRORS_H__ = "";
pub const FTMODERR_H_ = "";
pub const FT_ERR_PROTOS_DEFINED = "";
pub const FT_ENC_TAG = @compileError("unable to translate C expr: unexpected token '='"); // /Users/adriaan/g/bq268/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj/include/freetype/freetype.h:772:9
pub const ft_encoding_none = FT_ENCODING_NONE;
pub const ft_encoding_unicode = FT_ENCODING_UNICODE;
pub const ft_encoding_symbol = FT_ENCODING_MS_SYMBOL;
pub const ft_encoding_latin_1 = FT_ENCODING_ADOBE_LATIN_1;
pub const ft_encoding_latin_2 = FT_ENCODING_OLD_LATIN_2;
pub const ft_encoding_sjis = FT_ENCODING_SJIS;
pub const ft_encoding_gb2312 = FT_ENCODING_PRC;
pub const ft_encoding_big5 = FT_ENCODING_BIG5;
pub const ft_encoding_wansung = FT_ENCODING_WANSUNG;
pub const ft_encoding_johab = FT_ENCODING_JOHAB;
pub const ft_encoding_adobe_standard = FT_ENCODING_ADOBE_STANDARD;
pub const ft_encoding_adobe_expert = FT_ENCODING_ADOBE_EXPERT;
pub const ft_encoding_adobe_custom = FT_ENCODING_ADOBE_CUSTOM;
pub const ft_encoding_apple_roman = FT_ENCODING_APPLE_ROMAN;
pub const FT_FACE_FLAG_SCALABLE = @as(c_long, 1) << @as(c_int, 0);
pub const FT_FACE_FLAG_FIXED_SIZES = @as(c_long, 1) << @as(c_int, 1);
pub const FT_FACE_FLAG_FIXED_WIDTH = @as(c_long, 1) << @as(c_int, 2);
pub const FT_FACE_FLAG_SFNT = @as(c_long, 1) << @as(c_int, 3);
pub const FT_FACE_FLAG_HORIZONTAL = @as(c_long, 1) << @as(c_int, 4);
pub const FT_FACE_FLAG_VERTICAL = @as(c_long, 1) << @as(c_int, 5);
pub const FT_FACE_FLAG_KERNING = @as(c_long, 1) << @as(c_int, 6);
pub const FT_FACE_FLAG_FAST_GLYPHS = @as(c_long, 1) << @as(c_int, 7);
pub const FT_FACE_FLAG_MULTIPLE_MASTERS = @as(c_long, 1) << @as(c_int, 8);
pub const FT_FACE_FLAG_GLYPH_NAMES = @as(c_long, 1) << @as(c_int, 9);
pub const FT_FACE_FLAG_EXTERNAL_STREAM = @as(c_long, 1) << @as(c_int, 10);
pub const FT_FACE_FLAG_HINTER = @as(c_long, 1) << @as(c_int, 11);
pub const FT_FACE_FLAG_CID_KEYED = @as(c_long, 1) << @as(c_int, 12);
pub const FT_FACE_FLAG_TRICKY = @as(c_long, 1) << @as(c_int, 13);
pub const FT_FACE_FLAG_COLOR = @as(c_long, 1) << @as(c_int, 14);
pub const FT_FACE_FLAG_VARIATION = @as(c_long, 1) << @as(c_int, 15);
pub const FT_FACE_FLAG_SVG = @as(c_long, 1) << @as(c_int, 16);
pub const FT_FACE_FLAG_SBIX = @as(c_long, 1) << @as(c_int, 17);
pub const FT_FACE_FLAG_SBIX_OVERLAY = @as(c_long, 1) << @as(c_int, 18);
pub inline fn FT_HAS_HORIZONTAL(face: anytype) @TypeOf(!!((face.*.face_flags & FT_FACE_FLAG_HORIZONTAL) != 0)) {
    _ = &face;
    return !!((face.*.face_flags & FT_FACE_FLAG_HORIZONTAL) != 0);
}
pub inline fn FT_HAS_VERTICAL(face: anytype) @TypeOf(!!((face.*.face_flags & FT_FACE_FLAG_VERTICAL) != 0)) {
    _ = &face;
    return !!((face.*.face_flags & FT_FACE_FLAG_VERTICAL) != 0);
}
pub inline fn FT_HAS_KERNING(face: anytype) @TypeOf(!!((face.*.face_flags & FT_FACE_FLAG_KERNING) != 0)) {
    _ = &face;
    return !!((face.*.face_flags & FT_FACE_FLAG_KERNING) != 0);
}
pub inline fn FT_IS_SCALABLE(face: anytype) @TypeOf(!!((face.*.face_flags & FT_FACE_FLAG_SCALABLE) != 0)) {
    _ = &face;
    return !!((face.*.face_flags & FT_FACE_FLAG_SCALABLE) != 0);
}
pub inline fn FT_IS_SFNT(face: anytype) @TypeOf(!!((face.*.face_flags & FT_FACE_FLAG_SFNT) != 0)) {
    _ = &face;
    return !!((face.*.face_flags & FT_FACE_FLAG_SFNT) != 0);
}
pub inline fn FT_IS_FIXED_WIDTH(face: anytype) @TypeOf(!!((face.*.face_flags & FT_FACE_FLAG_FIXED_WIDTH) != 0)) {
    _ = &face;
    return !!((face.*.face_flags & FT_FACE_FLAG_FIXED_WIDTH) != 0);
}
pub inline fn FT_HAS_FIXED_SIZES(face: anytype) @TypeOf(!!((face.*.face_flags & FT_FACE_FLAG_FIXED_SIZES) != 0)) {
    _ = &face;
    return !!((face.*.face_flags & FT_FACE_FLAG_FIXED_SIZES) != 0);
}
pub inline fn FT_HAS_FAST_GLYPHS(face: anytype) @TypeOf(@as(c_int, 0)) {
    _ = &face;
    return @as(c_int, 0);
}
pub inline fn FT_HAS_GLYPH_NAMES(face: anytype) @TypeOf(!!((face.*.face_flags & FT_FACE_FLAG_GLYPH_NAMES) != 0)) {
    _ = &face;
    return !!((face.*.face_flags & FT_FACE_FLAG_GLYPH_NAMES) != 0);
}
pub inline fn FT_HAS_MULTIPLE_MASTERS(face: anytype) @TypeOf(!!((face.*.face_flags & FT_FACE_FLAG_MULTIPLE_MASTERS) != 0)) {
    _ = &face;
    return !!((face.*.face_flags & FT_FACE_FLAG_MULTIPLE_MASTERS) != 0);
}
pub inline fn FT_IS_NAMED_INSTANCE(face: anytype) @TypeOf(!!((face.*.face_index & @as(c_long, 0x7FFF0000)) != 0)) {
    _ = &face;
    return !!((face.*.face_index & @as(c_long, 0x7FFF0000)) != 0);
}
pub inline fn FT_IS_VARIATION(face: anytype) @TypeOf(!!((face.*.face_flags & FT_FACE_FLAG_VARIATION) != 0)) {
    _ = &face;
    return !!((face.*.face_flags & FT_FACE_FLAG_VARIATION) != 0);
}
pub inline fn FT_IS_CID_KEYED(face: anytype) @TypeOf(!!((face.*.face_flags & FT_FACE_FLAG_CID_KEYED) != 0)) {
    _ = &face;
    return !!((face.*.face_flags & FT_FACE_FLAG_CID_KEYED) != 0);
}
pub inline fn FT_IS_TRICKY(face: anytype) @TypeOf(!!((face.*.face_flags & FT_FACE_FLAG_TRICKY) != 0)) {
    _ = &face;
    return !!((face.*.face_flags & FT_FACE_FLAG_TRICKY) != 0);
}
pub inline fn FT_HAS_COLOR(face: anytype) @TypeOf(!!((face.*.face_flags & FT_FACE_FLAG_COLOR) != 0)) {
    _ = &face;
    return !!((face.*.face_flags & FT_FACE_FLAG_COLOR) != 0);
}
pub inline fn FT_HAS_SVG(face: anytype) @TypeOf(!!((face.*.face_flags & FT_FACE_FLAG_SVG) != 0)) {
    _ = &face;
    return !!((face.*.face_flags & FT_FACE_FLAG_SVG) != 0);
}
pub inline fn FT_HAS_SBIX(face: anytype) @TypeOf(!!((face.*.face_flags & FT_FACE_FLAG_SBIX) != 0)) {
    _ = &face;
    return !!((face.*.face_flags & FT_FACE_FLAG_SBIX) != 0);
}
pub inline fn FT_HAS_SBIX_OVERLAY(face: anytype) @TypeOf(!!((face.*.face_flags & FT_FACE_FLAG_SBIX_OVERLAY) != 0)) {
    _ = &face;
    return !!((face.*.face_flags & FT_FACE_FLAG_SBIX_OVERLAY) != 0);
}
pub const FT_STYLE_FLAG_ITALIC = @as(c_int, 1) << @as(c_int, 0);
pub const FT_STYLE_FLAG_BOLD = @as(c_int, 1) << @as(c_int, 1);
pub const FT_OPEN_MEMORY = @as(c_int, 0x1);
pub const FT_OPEN_STREAM = @as(c_int, 0x2);
pub const FT_OPEN_PATHNAME = @as(c_int, 0x4);
pub const FT_OPEN_DRIVER = @as(c_int, 0x8);
pub const FT_OPEN_PARAMS = @as(c_int, 0x10);
pub const ft_open_memory = FT_OPEN_MEMORY;
pub const ft_open_stream = FT_OPEN_STREAM;
pub const ft_open_pathname = FT_OPEN_PATHNAME;
pub const ft_open_driver = FT_OPEN_DRIVER;
pub const ft_open_params = FT_OPEN_PARAMS;
pub const FT_LOAD_DEFAULT = @as(c_int, 0x0);
pub const FT_LOAD_NO_SCALE = @as(c_long, 1) << @as(c_int, 0);
pub const FT_LOAD_NO_HINTING = @as(c_long, 1) << @as(c_int, 1);
pub const FT_LOAD_RENDER = @as(c_long, 1) << @as(c_int, 2);
pub const FT_LOAD_NO_BITMAP = @as(c_long, 1) << @as(c_int, 3);
pub const FT_LOAD_VERTICAL_LAYOUT = @as(c_long, 1) << @as(c_int, 4);
pub const FT_LOAD_FORCE_AUTOHINT = @as(c_long, 1) << @as(c_int, 5);
pub const FT_LOAD_CROP_BITMAP = @as(c_long, 1) << @as(c_int, 6);
pub const FT_LOAD_PEDANTIC = @as(c_long, 1) << @as(c_int, 7);
pub const FT_LOAD_IGNORE_GLOBAL_ADVANCE_WIDTH = @as(c_long, 1) << @as(c_int, 9);
pub const FT_LOAD_NO_RECURSE = @as(c_long, 1) << @as(c_int, 10);
pub const FT_LOAD_IGNORE_TRANSFORM = @as(c_long, 1) << @as(c_int, 11);
pub const FT_LOAD_MONOCHROME = @as(c_long, 1) << @as(c_int, 12);
pub const FT_LOAD_LINEAR_DESIGN = @as(c_long, 1) << @as(c_int, 13);
pub const FT_LOAD_SBITS_ONLY = @as(c_long, 1) << @as(c_int, 14);
pub const FT_LOAD_NO_AUTOHINT = @as(c_long, 1) << @as(c_int, 15);
pub const FT_LOAD_COLOR = @as(c_long, 1) << @as(c_int, 20);
pub const FT_LOAD_COMPUTE_METRICS = @as(c_long, 1) << @as(c_int, 21);
pub const FT_LOAD_BITMAP_METRICS_ONLY = @as(c_long, 1) << @as(c_int, 22);
pub const FT_LOAD_NO_SVG = @as(c_long, 1) << @as(c_int, 24);
pub const FT_LOAD_ADVANCE_ONLY = @as(c_long, 1) << @as(c_int, 8);
pub const FT_LOAD_SVG_ONLY = @as(c_long, 1) << @as(c_int, 23);
pub inline fn FT_LOAD_TARGET_(x: anytype) @TypeOf(FT_STATIC_CAST(FT_Int32, x & @as(c_int, 15)) << @as(c_int, 16)) {
    _ = &x;
    return FT_STATIC_CAST(FT_Int32, x & @as(c_int, 15)) << @as(c_int, 16);
}
pub const FT_LOAD_TARGET_NORMAL = FT_LOAD_TARGET_(FT_RENDER_MODE_NORMAL);
pub const FT_LOAD_TARGET_LIGHT = FT_LOAD_TARGET_(FT_RENDER_MODE_LIGHT);
pub const FT_LOAD_TARGET_MONO = FT_LOAD_TARGET_(FT_RENDER_MODE_MONO);
pub const FT_LOAD_TARGET_LCD = FT_LOAD_TARGET_(FT_RENDER_MODE_LCD);
pub const FT_LOAD_TARGET_LCD_V = FT_LOAD_TARGET_(FT_RENDER_MODE_LCD_V);
pub inline fn FT_LOAD_TARGET_MODE(x: anytype) @TypeOf(FT_STATIC_CAST(FT_Render_Mode, (x >> @as(c_int, 16)) & @as(c_int, 15))) {
    _ = &x;
    return FT_STATIC_CAST(FT_Render_Mode, (x >> @as(c_int, 16)) & @as(c_int, 15));
}
pub const ft_render_mode_normal = FT_RENDER_MODE_NORMAL;
pub const ft_render_mode_mono = FT_RENDER_MODE_MONO;
pub const ft_kerning_default = FT_KERNING_DEFAULT;
pub const ft_kerning_unfitted = FT_KERNING_UNFITTED;
pub const ft_kerning_unscaled = FT_KERNING_UNSCALED;
pub const FT_SUBGLYPH_FLAG_ARGS_ARE_WORDS = @as(c_int, 1);
pub const FT_SUBGLYPH_FLAG_ARGS_ARE_XY_VALUES = @as(c_int, 2);
pub const FT_SUBGLYPH_FLAG_ROUND_XY_TO_GRID = @as(c_int, 4);
pub const FT_SUBGLYPH_FLAG_SCALE = @as(c_int, 8);
pub const FT_SUBGLYPH_FLAG_XY_SCALE = @as(c_int, 0x40);
pub const FT_SUBGLYPH_FLAG_2X2 = @as(c_int, 0x80);
pub const FT_SUBGLYPH_FLAG_USE_MY_METRICS = @as(c_int, 0x200);
pub const FT_FSTYPE_INSTALLABLE_EMBEDDING = @as(c_int, 0x0000);
pub const FT_FSTYPE_RESTRICTED_LICENSE_EMBEDDING = @as(c_int, 0x0002);
pub const FT_FSTYPE_PREVIEW_AND_PRINT_EMBEDDING = @as(c_int, 0x0004);
pub const FT_FSTYPE_EDITABLE_EMBEDDING = @as(c_int, 0x0008);
pub const FT_FSTYPE_NO_SUBSETTING = @as(c_int, 0x0100);
pub const FT_FSTYPE_BITMAP_EMBEDDING_ONLY = @as(c_int, 0x0200);
pub const FREETYPE_MAJOR = @as(c_int, 2);
pub const FREETYPE_MINOR = @as(c_int, 13);
pub const FREETYPE_PATCH = @as(c_int, 3);
pub const FTBITMAP_H_ = "";
pub const FTCOLOR_H_ = "";
pub const FT_PALETTE_FOR_LIGHT_BACKGROUND = @as(c_int, 0x01);
pub const FT_PALETTE_FOR_DARK_BACKGROUND = @as(c_int, 0x02);
pub const __locale_struct = struct___locale_struct;
pub const _IO_FILE = struct__IO_FILE;
pub const _G_fpos64_t = union__G_fpos64_t;
pub const __jmp_buf_tag = struct___jmp_buf_tag;
pub const FT_MemoryRec_ = struct_FT_MemoryRec_;
pub const FT_StreamDesc_ = union_FT_StreamDesc_;
pub const FT_StreamRec_ = struct_FT_StreamRec_;
pub const FT_Vector_ = struct_FT_Vector_;
pub const FT_BBox_ = struct_FT_BBox_;
pub const FT_Pixel_Mode_ = enum_FT_Pixel_Mode_;
pub const FT_Bitmap_ = struct_FT_Bitmap_;
pub const FT_Outline_ = struct_FT_Outline_;
pub const FT_Outline_Funcs_ = struct_FT_Outline_Funcs_;
pub const FT_Glyph_Format_ = enum_FT_Glyph_Format_;
pub const FT_Span_ = struct_FT_Span_;
pub const FT_Raster_Params_ = struct_FT_Raster_Params_;
pub const FT_RasterRec_ = struct_FT_RasterRec_;
pub const FT_Raster_Funcs_ = struct_FT_Raster_Funcs_;
pub const FT_UnitVector_ = struct_FT_UnitVector_;
pub const FT_Matrix_ = struct_FT_Matrix_;
pub const FT_Data_ = struct_FT_Data_;
pub const FT_Generic_ = struct_FT_Generic_;
pub const FT_ListNodeRec_ = struct_FT_ListNodeRec_;
pub const FT_ListRec_ = struct_FT_ListRec_;
pub const FT_Glyph_Metrics_ = struct_FT_Glyph_Metrics_;
pub const FT_Bitmap_Size_ = struct_FT_Bitmap_Size_;
pub const FT_LibraryRec_ = struct_FT_LibraryRec_;
pub const FT_ModuleRec_ = struct_FT_ModuleRec_;
pub const FT_DriverRec_ = struct_FT_DriverRec_;
pub const FT_RendererRec_ = struct_FT_RendererRec_;
pub const FT_Encoding_ = enum_FT_Encoding_;
pub const FT_CharMapRec_ = struct_FT_CharMapRec_;
pub const FT_SubGlyphRec_ = struct_FT_SubGlyphRec_;
pub const FT_Slot_InternalRec_ = struct_FT_Slot_InternalRec_;
pub const FT_GlyphSlotRec_ = struct_FT_GlyphSlotRec_;
pub const FT_Size_Metrics_ = struct_FT_Size_Metrics_;
pub const FT_Size_InternalRec_ = struct_FT_Size_InternalRec_;
pub const FT_SizeRec_ = struct_FT_SizeRec_;
pub const FT_Face_InternalRec_ = struct_FT_Face_InternalRec_;
pub const FT_FaceRec_ = struct_FT_FaceRec_;
pub const FT_Parameter_ = struct_FT_Parameter_;
pub const FT_Open_Args_ = struct_FT_Open_Args_;
pub const FT_Size_Request_Type_ = enum_FT_Size_Request_Type_;
pub const FT_Size_RequestRec_ = struct_FT_Size_RequestRec_;
pub const FT_Render_Mode_ = enum_FT_Render_Mode_;
pub const FT_Kerning_Mode_ = enum_FT_Kerning_Mode_;
pub const FT_Color_ = struct_FT_Color_;
pub const FT_Palette_Data_ = struct_FT_Palette_Data_;
pub const FT_LayerIterator_ = struct_FT_LayerIterator_;
pub const FT_PaintFormat_ = enum_FT_PaintFormat_;
pub const FT_ColorStopIterator_ = struct_FT_ColorStopIterator_;
pub const FT_ColorIndex_ = struct_FT_ColorIndex_;
pub const FT_ColorStop_ = struct_FT_ColorStop_;
pub const FT_PaintExtend_ = enum_FT_PaintExtend_;
pub const FT_ColorLine_ = struct_FT_ColorLine_;
pub const FT_Affine_23_ = struct_FT_Affine_23_;
pub const FT_Composite_Mode_ = enum_FT_Composite_Mode_;
pub const FT_Opaque_Paint_ = struct_FT_Opaque_Paint_;
pub const FT_PaintColrLayers_ = struct_FT_PaintColrLayers_;
pub const FT_PaintSolid_ = struct_FT_PaintSolid_;
pub const FT_PaintLinearGradient_ = struct_FT_PaintLinearGradient_;
pub const FT_PaintRadialGradient_ = struct_FT_PaintRadialGradient_;
pub const FT_PaintSweepGradient_ = struct_FT_PaintSweepGradient_;
pub const FT_PaintGlyph_ = struct_FT_PaintGlyph_;
pub const FT_PaintColrGlyph_ = struct_FT_PaintColrGlyph_;
pub const FT_PaintTransform_ = struct_FT_PaintTransform_;
pub const FT_PaintTranslate_ = struct_FT_PaintTranslate_;
pub const FT_PaintScale_ = struct_FT_PaintScale_;
pub const FT_PaintRotate_ = struct_FT_PaintRotate_;
pub const FT_PaintSkew_ = struct_FT_PaintSkew_;
pub const FT_PaintComposite_ = struct_FT_PaintComposite_;
pub const FT_COLR_Paint_ = struct_FT_COLR_Paint_;
pub const FT_Color_Root_Transform_ = enum_FT_Color_Root_Transform_;
pub const FT_ClipBox_ = struct_FT_ClipBox_;
