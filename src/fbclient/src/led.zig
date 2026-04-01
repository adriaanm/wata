/// LED and backlight control via sysfs.
/// Writes to /sys/class/leds/*/brightness. No-ops silently on failure (e.g. dev host).
const std = @import("std");
const linux = std.os.linux;

pub fn setBacklight(brightness: u8) void {
    writeSysfs("/sys/class/leds/lcd-bl/brightness", brightness);
}

pub fn setRedLed(on: bool) void {
    writeSysfs("/sys/class/leds/red/brightness", if (on) 255 else 0);
}

pub fn setGreenLed(on: bool) void {
    writeSysfs("/sys/class/leds/green/brightness", if (on) 255 else 0);
}

pub fn setButtonBacklight(on: bool) void {
    writeSysfs("/sys/class/leds/button-backlight/brightness", if (on) 255 else 0);
}

/// Read battery capacity (0–100%) from sysfs. Returns null on failure.
pub fn readBatteryPercent() ?u8 {
    const fd = std.posix.openatZ(std.posix.AT.FDCWD, "/sys/class/power_supply/battery/capacity", .{ .ACCMODE = .RDONLY }, 0) catch return null;
    defer _ = linux.close(fd);
    var buf: [4]u8 = undefined;
    const n = std.posix.read(fd, &buf) catch return null;
    const trimmed = std.mem.trimRight(u8, buf[0..n], "\n ");
    return std.fmt.parseInt(u8, trimmed, 10) catch null;
}

fn writeSysfs(path: [*:0]const u8, value: u8) void {
    const fd = std.posix.openatZ(std.posix.AT.FDCWD, path, .{ .ACCMODE = .WRONLY }, 0) catch return;
    defer _ = linux.close(fd);
    var buf: [4]u8 = undefined;
    const str = std.fmt.bufPrint(&buf, "{d}", .{value}) catch return;
    _ = linux.write(fd, str.ptr, str.len);
}
