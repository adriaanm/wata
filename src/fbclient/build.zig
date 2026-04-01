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
    const use_audio = b.option(bool, "audio", "Enable audio capture/playback (default: non-SDL)") orelse !use_sdl;

    // Git version
    const git_sha = b.option([]const u8, "version", "Version string (default: git short SHA)") orelse blk: {
        var code: u8 = 0;
        const result = b.runAllowFail(&.{ "git", "rev-parse", "--short", "HEAD" }, &code, .inherit);
        break :blk if (result) |output| std.mem.trimEnd(u8, output, "\n \r") else |_| "unknown";
    };

    const options = b.addOptions();
    options.addOption(bool, "use_sdl", use_sdl);
    options.addOption(bool, "debug_mode", debug_mode);
    options.addOption(bool, "use_freetype", use_freetype);
    options.addOption(bool, "use_audio", use_audio);
    options.addOption([]const u8, "version", git_sha);

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

    // Audio — tinyalsa + opus from vendored source
    if (use_audio) {
        if (b.lazyDependency("tinyalsa", .{})) |tinyalsa_dep| {
            const tinyalsa_lib = buildTinyalsa(b, tinyalsa_dep, target, optimize);
            root_mod.linkLibrary(tinyalsa_lib);
            root_mod.addIncludePath(tinyalsa_dep.path("include"));
        }
        if (b.lazyDependency("opus", .{})) |opus_dep| {
            const opus_lib = buildOpus(b, opus_dep, target, optimize);
            root_mod.linkLibrary(opus_lib);
            root_mod.addIncludePath(opus_dep.path("include"));
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

/// Build tinyalsa 2.0.0 — tiny ALSA library (~8 C files, no libasound needed).
fn buildTinyalsa(
    b: *std.Build,
    dep: *std.Build.Dependency,
    target: std.Build.ResolvedTarget,
    optimize: std.builtin.OptimizeMode,
) *std.Build.Step.Compile {
    const mod = b.createModule(.{ .target = target, .optimize = optimize });
    mod.link_libc = true;
    mod.addIncludePath(dep.path("include"));

    mod.addCSourceFiles(.{
        .root = dep.path(""),
        .files = &.{
            "src/mixer.c",
            "src/mixer_hw.c",
            "src/mixer_plugin.c",
            "src/pcm.c",
            "src/pcm_hw.c",
            "src/pcm_plugin.c",
            "src/snd_card_plugin.c",
        },
        .flags = &.{ "-DTINYALSA_USES_PLUGINS=0", "-fno-sanitize=undefined" },
    });

    return b.addLibrary(.{ .linkage = .static, .name = "tinyalsa", .root_module = mod });
}

/// Build libopus 1.5.2 — float build, no SIMD, no DNN features.
fn buildOpus(
    b: *std.Build,
    dep: *std.Build.Dependency,
    target: std.Build.ResolvedTarget,
    optimize: std.builtin.OptimizeMode,
) *std.Build.Step.Compile {
    const mod = b.createModule(.{ .target = target, .optimize = optimize });
    mod.link_libc = true;

    mod.addIncludePath(dep.path("include"));
    mod.addIncludePath(dep.path("silk"));
    mod.addIncludePath(dep.path("silk/float"));
    mod.addIncludePath(dep.path("celt"));

    const flags: []const []const u8 = &.{
        "-DOPUS_BUILD",
        "-DUSE_ALLOCA",
        "-DHAVE_LRINTF",
        "-DPACKAGE_VERSION=\"1.5.2\"",
        "-fno-sanitize=undefined",
    };

    mod.addCSourceFiles(.{ .root = dep.path(""), .files = &opus_srcs, .flags = flags });

    return b.addLibrary(.{ .linkage = .static, .name = "opus", .root_module = mod });
}

/// Opus source files — float build (no SIMD, no DNN).
const opus_srcs = [_][]const u8{
    // Core
    "src/opus.c",
    "src/opus_decoder.c",
    "src/opus_encoder.c",
    "src/extensions.c",
    "src/opus_multistream.c",
    "src/opus_multistream_encoder.c",
    "src/opus_multistream_decoder.c",
    "src/repacketizer.c",
    "src/opus_projection_encoder.c",
    "src/opus_projection_decoder.c",
    "src/mapping_matrix.c",
    // Float analysis
    "src/analysis.c",
    "src/mlp.c",
    "src/mlp_data.c",
    // CELT
    "celt/bands.c",
    "celt/celt.c",
    "celt/celt_encoder.c",
    "celt/celt_decoder.c",
    "celt/cwrs.c",
    "celt/entcode.c",
    "celt/entdec.c",
    "celt/entenc.c",
    "celt/kiss_fft.c",
    "celt/laplace.c",
    "celt/mathops.c",
    "celt/mdct.c",
    "celt/modes.c",
    "celt/pitch.c",
    "celt/celt_lpc.c",
    "celt/quant_bands.c",
    "celt/rate.c",
    "celt/vq.c",
    // SILK core
    "silk/CNG.c",
    "silk/code_signs.c",
    "silk/init_decoder.c",
    "silk/decode_core.c",
    "silk/decode_frame.c",
    "silk/decode_parameters.c",
    "silk/decode_indices.c",
    "silk/decode_pulses.c",
    "silk/decoder_set_fs.c",
    "silk/dec_API.c",
    "silk/enc_API.c",
    "silk/encode_indices.c",
    "silk/encode_pulses.c",
    "silk/gain_quant.c",
    "silk/interpolate.c",
    "silk/LP_variable_cutoff.c",
    "silk/NLSF_decode.c",
    "silk/NSQ.c",
    "silk/NSQ_del_dec.c",
    "silk/PLC.c",
    "silk/shell_coder.c",
    "silk/tables_gain.c",
    "silk/tables_LTP.c",
    "silk/tables_NLSF_CB_NB_MB.c",
    "silk/tables_NLSF_CB_WB.c",
    "silk/tables_other.c",
    "silk/tables_pitch_lag.c",
    "silk/tables_pulses_per_block.c",
    "silk/VAD.c",
    "silk/control_audio_bandwidth.c",
    "silk/quant_LTP_gains.c",
    "silk/VQ_WMat_EC.c",
    "silk/HP_variable_cutoff.c",
    "silk/NLSF_encode.c",
    "silk/NLSF_VQ.c",
    "silk/NLSF_unpack.c",
    "silk/NLSF_del_dec_quant.c",
    "silk/process_NLSFs.c",
    "silk/stereo_LR_to_MS.c",
    "silk/stereo_MS_to_LR.c",
    "silk/check_control_input.c",
    "silk/control_SNR.c",
    "silk/init_encoder.c",
    "silk/control_codec.c",
    "silk/A2NLSF.c",
    "silk/ana_filt_bank_1.c",
    "silk/biquad_alt.c",
    "silk/bwexpander_32.c",
    "silk/bwexpander.c",
    "silk/debug.c",
    "silk/decode_pitch.c",
    "silk/inner_prod_aligned.c",
    "silk/lin2log.c",
    "silk/log2lin.c",
    "silk/LPC_analysis_filter.c",
    "silk/LPC_inv_pred_gain.c",
    "silk/table_LSF_cos.c",
    "silk/NLSF2A.c",
    "silk/NLSF_stabilize.c",
    "silk/NLSF_VQ_weights_laroia.c",
    "silk/pitch_est_tables.c",
    "silk/resampler.c",
    "silk/resampler_down2_3.c",
    "silk/resampler_down2.c",
    "silk/resampler_private_AR2.c",
    "silk/resampler_private_down_FIR.c",
    "silk/resampler_private_IIR_FIR.c",
    "silk/resampler_private_up2_HQ.c",
    "silk/resampler_rom.c",
    "silk/sigm_Q15.c",
    "silk/sort.c",
    "silk/sum_sqr_shift.c",
    "silk/stereo_decode_pred.c",
    "silk/stereo_encode_pred.c",
    "silk/stereo_find_predictor.c",
    "silk/stereo_quant_pred.c",
    "silk/LPC_fit.c",
    // SILK float
    "silk/float/apply_sine_window_FLP.c",
    "silk/float/corrMatrix_FLP.c",
    "silk/float/encode_frame_FLP.c",
    "silk/float/find_LPC_FLP.c",
    "silk/float/find_LTP_FLP.c",
    "silk/float/find_pitch_lags_FLP.c",
    "silk/float/find_pred_coefs_FLP.c",
    "silk/float/LPC_analysis_filter_FLP.c",
    "silk/float/LTP_analysis_filter_FLP.c",
    "silk/float/LTP_scale_ctrl_FLP.c",
    "silk/float/noise_shape_analysis_FLP.c",
    "silk/float/process_gains_FLP.c",
    "silk/float/regularize_correlations_FLP.c",
    "silk/float/residual_energy_FLP.c",
    "silk/float/warped_autocorrelation_FLP.c",
    "silk/float/wrappers_FLP.c",
    "silk/float/autocorrelation_FLP.c",
    "silk/float/burg_modified_FLP.c",
    "silk/float/bwexpander_FLP.c",
    "silk/float/energy_FLP.c",
    "silk/float/inner_product_FLP.c",
    "silk/float/k2a_FLP.c",
    "silk/float/LPC_inv_pred_gain_FLP.c",
    "silk/float/pitch_analysis_core_FLP.c",
    "silk/float/scale_copy_vector_FLP.c",
    "silk/float/scale_vector_FLP.c",
    "silk/float/schur_FLP.c",
    "silk/float/sort_FLP.c",
};

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
