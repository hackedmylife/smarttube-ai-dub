#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${RUNNER_TEMP:-/tmp}/smarttube-ai-dub-build"
UPSTREAM="$WORK/SmartTube"

rm -rf "$WORK"
mkdir -p "$WORK"

git clone --recursive --branch 32.56s --depth 1   https://github.com/yuliskov/SmartTube.git "$UPSTREAM"

cp -R "$ROOT/overlay_src/common/src/main/java/com/liskovsoft/smartyoutubetv2/common/aidub"   "$UPSTREAM/common/src/main/java/com/liskovsoft/smartyoutubetv2/common/"

python3 "$ROOT/tools/patch_renderer.py" "$UPSTREAM"

cd "$UPSTREAM"
chmod +x gradlew
./gradlew --no-daemon :smarttubetv:assembleStstableDebug

mkdir -p "$ROOT/out"
find smarttubetv/build/outputs/apk/ststable/debug -type f -name '*.apk'   -exec cp {} "$ROOT/out/" ;

sha256sum "$ROOT"/out/*.apk
