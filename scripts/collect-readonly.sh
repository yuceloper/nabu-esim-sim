#!/usr/bin/env bash
set -uo pipefail
OUT="${1:-nabu-diagnostics-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$OUT"
adb get-state || exit 1
capture() { local name="$1"; shift; adb shell "$@" > "$OUT/$name.txt" 2>&1 || true; }
capture root 'su -c id'
capture modem 'su -c "cat /sys/bus/msm_subsys/devices/subsys0/name /sys/bus/msm_subsys/devices/subsys0/state"'
capture qrtr 'su -c qrtr-lookup'
capture hal 'su -c lshal'
capture vsim 'su -c "getprop init.svc.vendor.vsimservice; getprop init.svc.vendor.qcrild; pidof vsimd"'
capture hashes 'su -c "sha256sum /vendor/bin/vsimd /vendor/lib/libvsim.so /vendor/lib/vendor.xiaomi.hardware.vsimapp@1.0.so /vendor/firmware_mnt/image/vsimapp*"'
printf 'Local diagnostics: %s\nReview and redact before sharing.\n' "$OUT"
