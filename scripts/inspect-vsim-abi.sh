#!/usr/bin/env bash
set -euo pipefail
src="${1:?Usage: bash scripts/inspect-vsim-abi.sh SAMPLE_DIR [OUTPUT_DIR]}"
out="${2:-nabu-diagnostics-v012-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$out"
reader=""
for candidate in llvm-readelf greadelf readelf; do
  if command -v "$candidate" >/dev/null 2>&1; then reader=$(command -v "$candidate"); break; fi
done
if [ -z "$reader" ]; then
  for candidate in "$HOME"/Library/Android/sdk/ndk/*/toolchains/llvm/prebuilt/darwin-*/bin/llvm-readelf; do
    if [ -x "$candidate" ]; then reader="$candidate"; break; fi
  done
fi
for name in vsimd libvsim.so vendor.xiaomi.hardware.vsimapp@1.0.so; do
  test -s "$src/$name"
  file "$src/$name" >> "$out/file.txt"
  shasum -a 256 "$src/$name" >> "$out/local-hashes.txt"
  if [ -n "$reader" ]; then
    "$reader" -h -d --dyn-syms -W "$src/$name" > "$out/$name.elf.txt"
  fi
done
for name in hal-debug.txt radio.txt hashes.txt; do
  if [ -f "$src/$name" ]; then cp "$src/$name" "$out/$name"; fi
done
if [ -z "$reader" ]; then
  echo 'No ELF reader found. Install Android NDK or brew install binutils, then rerun.' | tee "$out/reader-status.txt"
else
  echo 'ELF metadata generated.' | tee "$out/reader-status.txt"
fi
echo "Review metadata in $out before sharing. No original ELF files copied."
