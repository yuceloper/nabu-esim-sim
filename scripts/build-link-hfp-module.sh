#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build/link-apk
out="$(pwd)/build/link-apk/nabu-link-hfp-experimental.zip"
rm -f "$out"
(cd link/hfp-module && zip -q "$out" module.prop system.prop customize.sh)
unzip -t "$out"
echo "Experimental module: $out (not installed)"
