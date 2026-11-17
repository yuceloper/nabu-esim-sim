#!/usr/bin/env bash
set -euo pipefail
rom="${1:?Usage: bash scripts/inspect-vayu-rom.sh ROM.tgz}"
out="${2:-nabu-diagnostics-v019-$(date +%Y%m%d-%H%M%S)}"
expected=f76ca8a5c34ed4bd28d1c2729c864bbf
actual=$(md5 -q "$rom")
if [[ "$actual" != "$expected" ]]; then
  echo "ROM MD5 mismatch: $actual" >&2; exit 1
fi
mkdir -p "$out/reports" "$out/local"
printf '%s\n' "$actual" > "$out/reports/archive-md5.txt"
tar -tzf "$rom" > "$out/reports/archive-inventory.txt"
member=$(grep -E '(^|/)images/NON-HLOS\.bin$' "$out/reports/archive-inventory.txt" | head -n 1)
[[ -n "$member" ]] || { echo 'NON-HLOS.bin not found' >&2; exit 1; }
tar -xOzf "$rom" "$member" > "$out/local/NON-HLOS.bin"
file "$out/local/NON-HLOS.bin" > "$out/reports/image-type.txt"
shasum -a 256 "$out/local/NON-HLOS.bin" > "$out/reports/image-sha256.txt"
mountdir=$(mktemp -d)
cleanup() { rmdir "$mountdir" 2>/dev/null || true; }
trap cleanup EXIT
if hdiutil attach -readonly -nobrowse -mountpoint "$mountdir" -imagekey diskimage-class=CRawDiskImage "$out/local/NON-HLOS.bin" > "$out/reports/mount.txt" 2>&1; then
  trap 'hdiutil detach "$mountdir" >/dev/null 2>&1 || true; cleanup' EXIT
  (cd "$mountdir"; find . -type f -exec stat -f '%z %N' {} +) > "$out/reports/files.txt"
  (cd "$mountdir"; find . -type f -iname 'modem.b*' -exec stat -f '%z %N' {} +) > "$out/reports/modem-segments.txt"
  (cd "$mountdir"; find . -type f -iname 'modem.mdt' -exec stat -f '%z %N' {} +) >> "$out/reports/modem-segments.txt"
  awk '{s+=$1} END {print s+0}' "$out/reports/modem-segments.txt" > "$out/reports/modem-total-bytes.txt"
else
  echo 'Read-only image mount failed; see mount.txt. Do not modify image.'
fi
echo "Share only reviewed text reports under $out/reports; keep local image out of ZIP and Git."
