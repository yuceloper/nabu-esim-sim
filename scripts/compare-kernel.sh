#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
out="${1:-$PWD/nabu-diagnostics-v026}"
mkdir -p "$out"
out="$(cd "$out" && pwd)"
cache="$PWD/build/kernel-reference"
mkdir -p "$cache"
if [ ! -d "$cache/.git" ]; then git init "$cache"; fi
url=https://github.com/MiCode/Xiaomi_Kernel_OpenSource.git
git -C "$cache" fetch --depth=1 --filter=blob:none "$url" nabu-r-oss:refs/heads/nabu-r-oss vayu-r-oss:refs/heads/vayu-r-oss
pattern='apq|mpss|modem|rffe|rfsa|sdr8150|uim[0-9_]|sim[0-9]_'
for device in nabu vayu; do
 ref="$device-r-oss"
 git -C "$cache" rev-parse "$ref" > "$out/$device-commit.txt"
 git -C "$cache" ls-tree -r --name-only "$ref" -- arch/arm64/boot/dts arch/arm64/configs > "$out/$device-paths.txt"
 git -C "$cache" grep -inE "$pattern" "$ref" -- "arch/arm64/boot/dts/*$device*" "arch/arm64/configs/*$device*" > "$out/$device-matches.txt" || test "$?" -eq 1
done
git -C "$cache" diff --name-status nabu-r-oss vayu-r-oss -- arch/arm64/boot/dts arch/arm64/configs > "$out/changed-paths.txt"
echo "Reports: $out"
