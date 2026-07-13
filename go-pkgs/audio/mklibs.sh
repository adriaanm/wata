#!/usr/bin/env bash
# Build static libopus.a + libtinyalsa.a from the vendored sources using
# `zig cc` as the C cross-toolchain. Faithful reproduction of the file lists
# and flags in wata's build.zig (the pinned opus 1.5.2 float build, no SIMD /
# no DNN; tinyalsa 2.0.0 with plugins disabled).
#
# Usage:
#   ./mklibs.sh <zig-target> <archdir>
#   ./mklibs.sh arm-linux-musleabihf arm       # device (armv7 musl)
#   ./mklibs.sh native host                     # host (darwin/native) — for cgo host builds
#
# Output: clib/<archdir>/libopus.a, clib/<archdir>/libtinyalsa.a
# clib/ is a build artifact (gitignored); re-run to regenerate.
#
# Vendored source provenance (vendor/{opus,tinyalsa}/, pinned to wata build.zig.zon):
#   opus 1.5.2
#     https://github.com/xiph/opus/releases/download/v1.5.2/opus-1.5.2.tar.gz
#     sha256 65c1d2f78b9f2fb20082c38cbe47c951ad5839345876e46941612ee87f9a7ce1
#     (official Xiph release; vendored subset: include/ src/ celt/ silk/ — dnn/
#      tests/ doc/ autotools dropped, none are in the float build's file list)
#   tinyalsa 2.0.0
#     https://github.com/tinyalsa/tinyalsa/archive/refs/tags/v2.0.0.tar.gz
#     sha256 573ae0b2d3480851c1d2a12503ead2beea27f92d44ed47b74b553ba947994ef1
#     (GitHub tag archive; vendored subset: include/ src/)
#     LOCAL PATCH (M8 chunk 1, grep "SGOLA PATCH" in src/pcm.c): pcm_state()
#     and pcm_start() called pcm_sync_ptr(pcm, 0) -- in SYNC_PTR flag
#     semantics a MISSING flag means "push user->kernel", so on kernels
#     without status/control mmap (sync_ptr fallback; the BQ268's MSM CAF
#     4.4) every such call ZEROED the kernel appl_ptr: pre-start writes were
#     invisible (auto-start never fired above one chunk, pcm_start EPIPEd on
#     an "empty" full buffer) and mid-stream state polls caused the
#     xrun-restart soup behind wata's echo/stutter artifact. The patch pushes
#     only for MMAP handles and pulls (GET) otherwise. NB: after editing the
#     C libs, Go's build cache does NOT see .a changes -- cross-build with
#     `go build -a` (or clean the cache) after re-running mklibs.sh.
set -euo pipefail

TARGET="${1:-arm-linux-musleabihf}"
ARCHDIR="${2:-arm}"

HERE="$(cd "$(dirname "$0")" && pwd)"
OPUS="$HERE/vendor/opus"
TALSA="$HERE/vendor/tinyalsa"
OUT="$HERE/clib/$ARCHDIR"
OBJ="$OUT/obj"
mkdir -p "$OBJ/opus" "$OBJ/tinyalsa"

if [ "$TARGET" = "native" ]; then
  CC=(zig cc)
else
  CC=(zig cc -target "$TARGET")
fi

OPUS_FLAGS=(-O2 -DOPUS_BUILD -DUSE_ALLOCA -DHAVE_LRINTF '-DPACKAGE_VERSION="1.5.2"' -fno-sanitize=undefined
  -I"$OPUS/include" -I"$OPUS/silk" -I"$OPUS/silk/float" -I"$OPUS/celt")

# Exact file list from wata build.zig `opus_srcs` (pin: opus 1.5.2 float build).
OPUS_SRCS=(
  src/opus.c src/opus_decoder.c src/opus_encoder.c src/extensions.c
  src/opus_multistream.c src/opus_multistream_encoder.c src/opus_multistream_decoder.c
  src/repacketizer.c src/opus_projection_encoder.c src/opus_projection_decoder.c
  src/mapping_matrix.c src/analysis.c src/mlp.c src/mlp_data.c
  celt/bands.c celt/celt.c celt/celt_encoder.c celt/celt_decoder.c celt/cwrs.c
  celt/entcode.c celt/entdec.c celt/entenc.c celt/kiss_fft.c celt/laplace.c
  celt/mathops.c celt/mdct.c celt/modes.c celt/pitch.c celt/celt_lpc.c
  celt/quant_bands.c celt/rate.c celt/vq.c
  silk/CNG.c silk/code_signs.c silk/init_decoder.c silk/decode_core.c
  silk/decode_frame.c silk/decode_parameters.c silk/decode_indices.c
  silk/decode_pulses.c silk/decoder_set_fs.c silk/dec_API.c silk/enc_API.c
  silk/encode_indices.c silk/encode_pulses.c silk/gain_quant.c silk/interpolate.c
  silk/LP_variable_cutoff.c silk/NLSF_decode.c silk/NSQ.c silk/NSQ_del_dec.c
  silk/PLC.c silk/shell_coder.c silk/tables_gain.c silk/tables_LTP.c
  silk/tables_NLSF_CB_NB_MB.c silk/tables_NLSF_CB_WB.c silk/tables_other.c
  silk/tables_pitch_lag.c silk/tables_pulses_per_block.c silk/VAD.c
  silk/control_audio_bandwidth.c silk/quant_LTP_gains.c silk/VQ_WMat_EC.c
  silk/HP_variable_cutoff.c silk/NLSF_encode.c silk/NLSF_VQ.c silk/NLSF_unpack.c
  silk/NLSF_del_dec_quant.c silk/process_NLSFs.c silk/stereo_LR_to_MS.c
  silk/stereo_MS_to_LR.c silk/check_control_input.c silk/control_SNR.c
  silk/init_encoder.c silk/control_codec.c silk/A2NLSF.c silk/ana_filt_bank_1.c
  silk/biquad_alt.c silk/bwexpander_32.c silk/bwexpander.c silk/debug.c
  silk/decode_pitch.c silk/inner_prod_aligned.c silk/lin2log.c silk/log2lin.c
  silk/LPC_analysis_filter.c silk/LPC_inv_pred_gain.c silk/table_LSF_cos.c
  silk/NLSF2A.c silk/NLSF_stabilize.c silk/NLSF_VQ_weights_laroia.c
  silk/pitch_est_tables.c silk/resampler.c silk/resampler_down2_3.c
  silk/resampler_down2.c silk/resampler_private_AR2.c
  silk/resampler_private_down_FIR.c silk/resampler_private_IIR_FIR.c
  silk/resampler_private_up2_HQ.c silk/resampler_rom.c silk/sigm_Q15.c
  silk/sort.c silk/sum_sqr_shift.c silk/stereo_decode_pred.c
  silk/stereo_encode_pred.c silk/stereo_find_predictor.c silk/stereo_quant_pred.c
  silk/LPC_fit.c
  silk/float/apply_sine_window_FLP.c silk/float/corrMatrix_FLP.c
  silk/float/encode_frame_FLP.c silk/float/find_LPC_FLP.c silk/float/find_LTP_FLP.c
  silk/float/find_pitch_lags_FLP.c silk/float/find_pred_coefs_FLP.c
  silk/float/LPC_analysis_filter_FLP.c silk/float/LTP_analysis_filter_FLP.c
  silk/float/LTP_scale_ctrl_FLP.c silk/float/noise_shape_analysis_FLP.c
  silk/float/process_gains_FLP.c silk/float/regularize_correlations_FLP.c
  silk/float/residual_energy_FLP.c silk/float/warped_autocorrelation_FLP.c
  silk/float/wrappers_FLP.c silk/float/autocorrelation_FLP.c
  silk/float/burg_modified_FLP.c silk/float/bwexpander_FLP.c
  silk/float/energy_FLP.c silk/float/inner_product_FLP.c silk/float/k2a_FLP.c
  silk/float/LPC_inv_pred_gain_FLP.c silk/float/pitch_analysis_core_FLP.c
  silk/float/scale_copy_vector_FLP.c silk/float/scale_vector_FLP.c
  silk/float/schur_FLP.c silk/float/sort_FLP.c
)

echo "[mklibs] target=$TARGET arch=$ARCHDIR"
echo "[mklibs] compiling ${#OPUS_SRCS[@]} opus objects..."
OPUS_OBJS=()
for f in "${OPUS_SRCS[@]}"; do
  o="$OBJ/opus/$(echo "$f" | tr '/' '_').o"
  "${CC[@]}" "${OPUS_FLAGS[@]}" -c "$OPUS/$f" -o "$o"
  OPUS_OBJS+=("$o")
done
rm -f "$OUT/libopus.a"
zig ar rcs "$OUT/libopus.a" "${OPUS_OBJS[@]}"
echo "[mklibs] libopus.a $(du -h "$OUT/libopus.a" | cut -f1)"

TALSA_FLAGS=(-O2 -DTINYALSA_USES_PLUGINS=0 -fno-sanitize=undefined -I"$TALSA/include")
TALSA_SRCS=(mixer.c mixer_hw.c mixer_plugin.c pcm.c pcm_hw.c pcm_plugin.c snd_card_plugin.c)
echo "[mklibs] compiling ${#TALSA_SRCS[@]} tinyalsa objects..."
TALSA_OBJS=()
for f in "${TALSA_SRCS[@]}"; do
  o="$OBJ/tinyalsa/${f%.c}.o"
  "${CC[@]}" "${TALSA_FLAGS[@]}" -c "$TALSA/src/$f" -o "$o"
  TALSA_OBJS+=("$o")
done
rm -f "$OUT/libtinyalsa.a"
zig ar rcs "$OUT/libtinyalsa.a" "${TALSA_OBJS[@]}"
echo "[mklibs] libtinyalsa.a $(du -h "$OUT/libtinyalsa.a" | cut -f1)"
echo "[mklibs] done -> $OUT"
