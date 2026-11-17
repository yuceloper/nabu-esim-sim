#!/usr/bin/env bash
set -euo pipefail
out="${1:-nabu-diagnostics-v031-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$out"
adb get-state >/dev/null
capture() {
  local name="$1" cmd="$2" rc=0
  adb shell -T "$cmd" > "$out/$name.txt" 2>&1 || rc=$?
  printf '%s exit=%s\n' "$name" "$rc" >> "$out/status.txt"
}
capture features 'pm list features | grep -Ei "omapi|secure_element|euicc|telephony"'
capture packages 'pm list packages | grep -Ei "secureelement|secure_element|euicc|esim|lpa"'
capture manifests 'grep -RiEn "secure_element|secureelement|euicc|esim|omapi" /vendor/etc/vintf /odm/etc/vintf /system/etc/vintf /product/etc/vintf /system_ext/etc/vintf 2>/dev/null'
capture hals 'su -c lshal 2>/dev/null | grep -Ei "secure_element|secureelement|esepower|euicc|uim|vsim"'
capture omapi_service 'service check secure_element'
echo "Reports: $out; review before sharing."
