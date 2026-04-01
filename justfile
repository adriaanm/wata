zig := env("ZIG", home_dir() / "zig-x86_64-linux-0.16.0-dev.3059+42e33db9d/zig")
device := "bq268"
device_dir := "/opt/wata"

# Cross-compile fbclient for ARM device
fb-build:
    cd src/fbclient && {{zig}} build -Dtarget=arm-linux-musleabihf -Doptimize=ReleaseSafe

# Build, deploy to device, and restart
fb-deploy: fb-build
    ssh {{device}} 'killall wata-fb 2>/dev/null; sleep 0.3'
    scp src/fbclient/zig-out/bin/wata-fb {{device}}:{{device_dir}}/wata-fb
    ssh {{device}} '{{device_dir}}/start.sh &'
