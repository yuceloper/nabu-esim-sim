#!/usr/bin/env bash
set -euo pipefail
out="${1:-nabu-diagnostics-v018-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$out"
adb get-state >/dev/null
base=/vendor/firmware_mnt/image/modem_pr/mcfg/configs
for group in mcfg_hw mcfg_sw; do
  adb shell -T "su -c 'for f in $base/$group/*.txt; do echo ====\$f; cat \"\$f\"; done'" > "$out/$group.txt" 2>&1
done
adb shell -T 'getprop ro.build.version.incremental; getprop ro.product.device; getprop ro.vendor.build.fingerprint' > "$out/build.txt"
echo "Local reports: $out; inspect before sharing."
