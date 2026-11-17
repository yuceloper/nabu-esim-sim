#!/usr/bin/env bash
set -uo pipefail
out="${1:-nabu-diagnostics-v017-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$out" || exit 1
adb get-state >/dev/null || exit 1
capture() {
  local name="$1" cmd="$2" rc=0
  adb shell -T "$cmd" > "$out/$name.txt" 2>&1 || rc=$?
  printf '%s exit=%s\n' "$name" "$rc" | tee -a "$out/status.txt"
}
capture dt_paths 'su -c "ls -ld /proc/device-tree /sys/firmware/devicetree/base; find /sys/firmware/devicetree/base/ -type d"'
capture spmi 'su -c "ls -l /sys/bus/spmi/devices; ls -l /sys/bus/spmi/drivers"'
capture firmware 'su -c "ls -lR /vendor/firmware_mnt/image/"'
capture modem_meta 'su -c "cat /vendor/firmware_mnt/image/modemr.jsn /vendor/firmware_mnt/image/modemuw.jsn"'
capture kernel 'su -c dmesg'
grep -Ei 'rfsa|rffe|pmic|wtr|sdr|uim|mpss|modem' "$out/kernel.txt" > "$out/kernel-rf.txt" || true
capture qrtr 'su -c qrtr-lookup'
echo "Local reports: $out; review and redact before sharing."
