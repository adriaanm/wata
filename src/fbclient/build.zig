const std = @import("std");

pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{});

    const resolved = target.result;

    // Platform selection: SDL on macOS (dev), fbdev on ARM Linux (device)
    const use_sdl = resolved.os.tag == .macos or resolved.os.tag == .windows;

    // Build options
    const debug_mode = b.option(bool, "debug", "Run in headless debug mode (log to stderr)") orelse false;
    const use_freetype = b.option(bool, "freetype", "Enable FreeType font rendering (default: true)") orelse true;

    const options = b.addOptions();
    options.addOption(bool, "use_sdl", use_sdl);
    options.addOption(bool, "debug_mode", debug_mode);
    options.addOption(bool, "use_freetype", use_freetype);

    // Create root module
    const root_mod = b.createModule(.{
        .root_source_file = b.path("src/main.zig"),
        .target = target,
        .optimize = optimize,
    });
    root_mod.addOptions("build_options", options);

    root_mod.link_libc = true;

    // FreeType — build from vendored source for cross-compilation
    if (use_freetype) {
        if (b.lazyDependency("freetype", .{})) |freetype_dep| {
            const freetype_lib = buildFreetype(b, freetype_dep, target, optimize);
            root_mod.linkLibrary(freetype_lib);
            // Ensure @cImport finds ft2build.h
            root_mod.addIncludePath(freetype_dep.path("include"));
        } else {
            // Lazy dep not yet fetched — fall back to system library
            root_mod.linkSystemLibrary("freetype2", .{});
        }
    }

    if (use_sdl) {
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

/// Build FreeType 2.13.3 as a static C library from vendored source.
fn buildFreetype(
    b: *std.Build,
    freetype_dep: *std.Build.Dependency,
    target: std.Build.ResolvedTarget,
    optimize: std.builtin.OptimizeMode,
) *std.Build.Step.Compile {
    const ft_mod = b.createModule(.{
        .target = target,
        .optimize = optimize,
    });
    ft_mod.link_libc = true;

    const ft_include = freetype_dep.path("include");
    ft_mod.addIncludePath(ft_include);

    const flags: []const []const u8 = &.{
        "-DFT2_BUILD_LIBRARY",
        "-DHAVE_UNISTD_H",
        "-DHAVE_FCNTL_H",
        "-fno-sanitize=undefined",
    };

    ft_mod.addCSourceFiles(.{
        .root = freetype_dep.path(""),
        .files = &freetype_srcs,
        .flags = flags,
    });

    // Platform-specific ftsystem.c and ftdebug.c
    const os = target.result.os.tag;
    if (os == .linux or os == .macos or os.isBSD()) {
        ft_mod.addCSourceFile(.{ .file = freetype_dep.path("builds/unix/ftsystem.c"), .flags = flags });
    } else {
        ft_mod.addCSourceFile(.{ .file = freetype_dep.path("src/base/ftsystem.c"), .flags = flags });
    }
    ft_mod.addCSourceFile(.{ .file = freetype_dep.path("src/base/ftdebug.c"), .flags = flags });

    const freetype = b.addLibrary(.{
        .linkage = .static,
        .name = "freetype",
        .root_module = ft_mod,
    });

    return freetype;
}

/// FreeType source files — one "driver" .c per module (DO NOT compile all .c files).
const freetype_srcs = [_][]const u8{
    // Base
    "src/base/ftbase.c",
    "src/base/ftinit.c",
    "src/base/ftbbox.c",
    "src/base/ftbdf.c",
    "src/base/ftbitmap.c",
    "src/base/ftcid.c",
    "src/base/ftfstype.c",
    "src/base/ftgasp.c",
    "src/base/ftglyph.c",
    "src/base/ftgxval.c",
    "src/base/ftmm.c",
    "src/base/ftotval.c",
    "src/base/ftpatent.c",
    "src/base/ftpfr.c",
    "src/base/ftstroke.c",
    "src/base/ftsynth.c",
    "src/base/fttype1.c",
    "src/base/ftwinfnt.c",
    // Font drivers
    "src/bdf/bdf.c",
    "src/cff/cff.c",
    "src/cid/type1cid.c",
    "src/pcf/pcf.c",
    "src/pfr/pfr.c",
    "src/sfnt/sfnt.c",
    "src/truetype/truetype.c",
    "src/type1/type1.c",
    "src/type42/type42.c",
    "src/winfonts/winfnt.c",
    // Rasterizers
    "src/smooth/smooth.c",
    "src/raster/raster.c",
    "src/sdf/sdf.c",
    // Auxiliary
    "src/autofit/autofit.c",
    "src/cache/ftcache.c",
    "src/gzip/ftgzip.c",
    "src/lzw/ftlzw.c",
    "src/psaux/psaux.c",
    "src/pshinter/pshinter.c",
    "src/psnames/psnames.c",
    "src/svg/svg.c",
};
