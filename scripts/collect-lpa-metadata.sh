#!/usr/bin/env bash
set -euo pipefail
out="${1:-nabu-diagnostics-v030-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$out"
adb get-state >/dev/null
capture() {
  local name="$1" cmd="$2" rc=0
  adb shell -T "$cmd" > "$out/$name.txt" 2>&1 || rc=$?
  printf '%s exit=%s\n' "$name" "$rc" >> "$out/status.txt"
}
capture libraries 'pm list libraries'
capture package 'dumpsys package com.qualcomm.qti.lpa'
capture paths 'find /product/framework /system/framework /system_ext/framework /vendor/framework /product/etc/permissions /system/etc/permissions /system_ext/etc/permissions /vendor/etc/permissions -type f 2>/dev/null | grep -Ei "lpa|euicc|esim|uim|telephony|vsim"'
capture controllers 'service list | grep -Ei "econtroller|euicc|isub|secure_element"'
capture hal 'su -c lshal 2>/dev/null | grep -Ei "vsim|secure_element|radio|uim|esim|euicc|eid"'
capture lpa_state 'pm list packages -d | grep -Ei "lpa|euicc|esim"'
echo "Reports: $out; review and redact before sharing. Keep APKs and JARs local."
