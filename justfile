zig := env("ZIG", home_dir() / ".local/zig/zig")
device := "bq268"
device_dir := "/opt/wata"

# Cross-compile fbclient for ARM device
fb-build *FLAGS:
    cd src/fbclient && {{zig}} build -Dtarget=arm-linux-musleabihf -Doptimize=ReleaseSafe {{FLAGS}}

# Build, deploy to device, and restart
fb-deploy *FLAGS: (fb-build FLAGS)
    ssh {{device}} 'killall wata-fb 2>/dev/null; sleep 0.3'
    scp src/fbclient/zig-out/bin/wata-fb {{device}}:{{device_dir}}/wata-fb
    ssh {{device}} '{{device_dir}}/start.sh &'

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
