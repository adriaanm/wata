zig := env("ZIG", home_dir() / ".local/zig/zig")
device := "bq268"
device_dir := "/opt/wata"

# Cross-compile fbclient for ARM device
fb-build *FLAGS:
    cd src/fbclient && {{zig}} build -Dtarget=arm-linux-musleabihf -Doptimize=ReleaseSafe {{FLAGS}}

# Build and deploy to device. Does NOT launch — the system-menu owns the
# framebuffer VT and must be the one to spawn wata (it unbinds fbcon
# before launch and rebinds on exit). Kill any running wata so the user
# can re-launch from the menu to pick up the new binary.
fb-deploy *FLAGS: (fb-build FLAGS)
    ssh {{device}} 'killall wata-fb 2>/dev/null; sleep 0.3'
    scp src/fbclient/zig-out/bin/wata-fb {{device}}:{{device_dir}}/wata-fb
    @echo "Deployed. Launch from the system menu on the device."

# Cross-compile, deploy, and run the on-device audio self-test. Drives
# the real production audio thread via its command mailbox. Pass STAGE
# to run a subset: "echo" (echo_test only), "play" (ogg/opus play only),
# or "all" (default). User confirms tones are audible.
fb-audio-test STAGE="all" *FLAGS: (fb-build FLAGS)
    ssh {{device}} 'killall wata-fb 2>/dev/null; sleep 0.3'
    scp src/fbclient/zig-out/bin/wata-fb {{device}}:{{device_dir}}/wata-fb
    ssh {{device}} '{{device_dir}}/wata-fb --selftest {{STAGE}}'

# Run fbclient unit tests (no network).
fb-test:
    cd src/fbclient && {{zig}} build test --summary all

# Bring up the Conduit Matrix server (Docker) and register alice/bob.
# Idempotent — reuses test/docker/setup.sh which is the same harness used by
# `pnpm dev:server` and the TypeScript integration suite.
conduit-up:
    cd test/docker && ./setup.sh

# Tear down the Conduit Matrix server and its volumes.
conduit-down:
    cd test/docker && docker-compose down -v

# Run fbclient integration tests against a live Matrix homeserver.
# Wipes Conduit volumes first so every run starts from a deterministic
# state (no accumulated DM rooms / m.direct entries from prior runs).
# Override target with WATA_TEST_HOMESERVER / WATA_TEST_USER1 / WATA_TEST_PASS1 / WATA_TEST_USER2 / WATA_TEST_PASS2.
fb-test-integration: conduit-down conduit-up
    cd src/fbclient && {{zig}} build test-integration --summary all

# Fast iterative variant — reuses existing Conduit state (may carry over
# DM rooms from prior runs; tolerant tests only).
fb-test-integration-fast: conduit-up
    cd src/fbclient && {{zig}} build test-integration --summary all
