pub const packages = struct {
    pub const @"N-V-__8AAADWXwF69u4_iufnV_gwfb3M2ATbbZF-yGFRrEFH" = struct {
        pub const available = false;
    };
    pub const @"N-V-__8AAIXaBgCxF6eJGl2-u82_CFMzLWeFnRxykudW0Zrt" = struct {
        pub const available = false;
    };
    pub const @"N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj" = struct {
        pub const available = true;
        pub const build_root = "/Users/adriaan/g/wata/src/fbclient/zig-pkg/N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj";
        pub const deps: []const struct { []const u8, []const u8 } = &.{};
    };
};

pub const root_deps: []const struct { []const u8, []const u8 } = &.{
    .{ "freetype", "N-V-__8AAK-ZJgGK3AtcJ7nDUwlNM53dDwX6rHJ_YgEhdGqj" },
    .{ "opus", "N-V-__8AAADWXwF69u4_iufnV_gwfb3M2ATbbZF-yGFRrEFH" },
    .{ "tinyalsa", "N-V-__8AAIXaBgCxF6eJGl2-u82_CFMzLWeFnRxykudW0Zrt" },
};
