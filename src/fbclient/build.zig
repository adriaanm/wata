const std = @import("std");

pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{});

    const resolved = target.result;

    // Platform selection: SDL on macOS (dev), fbdev on ARM Linux (device)
    const use_sdl = resolved.os.tag == .macos or resolved.os.tag == .windows;

    // Build options
    const debug_mode = b.option(bool, "debug", "Run in headless debug mode (log to stderr)") orelse false;

    const options = b.addOptions();
    options.addOption(bool, "use_sdl", use_sdl);
    options.addOption(bool, "debug_mode", debug_mode);

    // Create root module
    const root_mod = b.createModule(.{
        .root_source_file = b.path("src/main.zig"),
        .target = target,
        .optimize = optimize,
    });
    root_mod.addOptions("build_options", options);

    if (use_sdl) {
        root_mod.link_libc = true;
        root_mod.linkSystemLibrary("SDL2", .{});
    }

    const exe = b.addExecutable(.{
        .name = "wata-fb",
        .root_module = root_mod,
    });

    b.installArtifact(exe);

    // Run step
    const run_cmd = b.addRunArtifact(exe);
    run_cmd.step.dependOn(b.getInstallStep());
    if (b.args) |args| run_cmd.addArgs(args);
    const run_step = b.step("run", "Run wata-fb");
    run_step.dependOn(&run_cmd.step);

    // Test step — test runner that imports all test-bearing modules
    const test_mod = b.createModule(.{
        .root_source_file = b.path("src/test_main.zig"),
        .target = target,
        .optimize = optimize,
    });

    const unit_tests = b.addTest(.{
        .root_module = test_mod,
    });

    const run_tests = b.addRunArtifact(unit_tests);
    const test_step = b.step("test", "Run unit tests");
    test_step.dependOn(&run_tests.step);
}
