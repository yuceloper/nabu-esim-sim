#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
sdk="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
d8=""
for candidate in "$sdk"/build-tools/*/d8; do
  if [ -x "$candidate" ]; then d8="$candidate"; fi
done
[ -n "$d8" ] || { echo "Android build-tools d8 missing" >&2; exit 1; }
command -v javac >/dev/null || { echo "JDK missing" >&2; exit 1; }
mkdir -p build/java-probe/classes
javac --release 8 -d build/java-probe/classes probe/NabuVsimProbe.java
"$d8" --min-api 33 --output build/java-probe/nabu-vsim-probe.jar build/java-probe/classes/NabuVsimProbe.class
echo "Built build/java-probe/nabu-vsim-probe.jar"
