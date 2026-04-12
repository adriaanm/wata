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
