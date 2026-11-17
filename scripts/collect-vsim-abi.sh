#!/usr/bin/env bash
set -euo pipefail
out="${1:-nabu-diagnostics-v011-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$out"
adb get-state >/dev/null
adb shell -T 'su -c "lshal debug vendor.xiaomi.hardware.vsimapp@1.0::IVSIMService/default"' > "$out/hal-debug.txt" 2>&1 || true
adb shell -T 'su -c "getprop ro.radio.noril; getprop ro.vendor.radio.noril; getprop ro.baseband; getenforce"' > "$out/radio.txt"
for p in /vendor/bin/vsimd /vendor/lib/libvsim.so /vendor/lib/vendor.xiaomi.hardware.vsimapp@1.0.so; do
  name="${p##*/}"
  expected=$(adb shell -T "su -c 'sha256sum $p'" | awk '{print $1}')
  adb shell -T "su -c 'cat $p'" > "$out/$name"
  actual=$(shasum -a 256 "$out/$name" | awk '{print $1}')
  if [ "$expected" != "$actual" ]; then
    echo "Hash mismatch: $name" >&2
    exit 1
  fi
  printf '%s  %s\n' "$actual" "$name" >> "$out/hashes.txt"
  file "$out/$name"
done
echo "Verified local samples: $out"
echo "Keep binaries local; do not commit or redistribute."
