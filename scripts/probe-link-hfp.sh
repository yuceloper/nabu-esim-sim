#!/usr/bin/env bash
set -euo pipefail
if [[ $# -ne 1 ]]; then echo "Usage: bash scripts/probe-link-hfp.sh ADB_SERIAL" >&2; exit 2; fi
device=(adb -s "$1")
"${device[@]}" get-state >/dev/null
echo "=== HFP capability report (read only) ==="
for p in ro.product.device ro.build.version.sdk bluetooth.profile.hfp.hf.enabled bluetooth.profile.hfp.ag.enabled bluetooth.headset_client.initial_audio_route.enabled; do
  printf '%s=' "$p"; "${device[@]}" shell getprop "$p" | tr -d '\r'
done
echo "=== Profile service markers (counts only) ==="
dump="$("${device[@]}" shell dumpsys bluetooth_manager 2>/dev/null || true)"
for marker in HeadsetClientService HfpClientConnectionService HeadsetService; do
  count="$(printf '%s\n' "$dump" | grep -ic "$marker" || true)"
  printf '%s=%s\n' "$marker" "$count"
done
echo "=== Audio policy marker counts (not proof of routing) ==="
policy="$("${device[@]}" shell dumpsys media.audio_policy 2>/dev/null || true)"
for marker in AUDIO_DEVICE_IN_BLUETOOTH_SCO_HEADSET AUDIO_DEVICE_OUT_BLUETOOTH_SCO AUDIO_DEVICE_IN_TELEPHONY_RX AUDIO_DEVICE_OUT_TELEPHONY_TX; do
  count="$(printf '%s\n' "$policy" | grep -ic "$marker" || true)"
  printf '%s=%s\n' "$marker" "$count"
done
echo "No device addresses, phone numbers, call contents or full dumps included."
