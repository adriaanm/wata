const std = @import("std");

pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{});

    const resolved = target.result;

    // Platform selection: SDL on macOS (dev), fbdev on ARM Linux (device)
    const use_sdl = resolved.os.tag == .macos or resolved.os.tag == .windows;

    // Build options module (must be created before the root module)
    const options = b.addOptions();
    options.addOption(bool, "use_sdl", use_sdl);

    // Create root module
    const root_mod = b.createModule(.{
        .root_source_file = b.path("src/main.zig"),
        .target = target,
        .optimize = optimize,
    });
    root_mod.addOptions("build_options", options);

    if (use_sdl) {
        root_mod.linkSystemLibrary("SDL2", .{});
        root_mod.link_libc = true;
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
}
