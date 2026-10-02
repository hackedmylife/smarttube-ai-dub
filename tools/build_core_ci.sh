#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${RUNNER_TEMP:-/tmp}/smarttube-ai-dub-build"
UPSTREAM="$WORK/SmartTube"

rm -rf "$WORK"
mkdir -p "$WORK"

git clone --recursive --branch 32.56s --depth 1   https://github.com/yuliskov/SmartTube.git "$UPSTREAM"

cp -R "$ROOT/overlay_src/common/src/main/java/com/liskovsoft/smartyoutubetv2/common/aidub"   "$UPSTREAM/common/src/main/java/com/liskovsoft/smartyoutubetv2/common/"

cp -R "$ROOT/overlay_src/smarttubetv/src/main/java/com/liskovsoft/smartyoutubetv2/tv/ui/playback/aidub" \
  "$UPSTREAM/smarttubetv/src/main/java/com/liskovsoft/smartyoutubetv2/tv/ui/playback/"

cp "$ROOT/overlay_src/smarttubetv/src/main/java/com/liskovsoft/smartyoutubetv2/tv/ui/playback/actions/AiDubAction.java" \
  "$UPSTREAM/smarttubetv/src/main/java/com/liskovsoft/smartyoutubetv2/tv/ui/playback/actions/AiDubAction.java"

python3 - "$UPSTREAM" <<'PY'
from pathlib import Path
import sys

root = Path(sys.argv[1])
path = root / "common/src/main/java/com/liskovsoft/smartyoutubetv2/common/exoplayer/versions/renderer/CustomOverridesRenderersFactory.java"
text = path.read_text(encoding="utf-8")

import_anchor = "import com.liskovsoft.smartyoutubetv2.common.exoplayer.versions.selector.BlacklistMediaCodecSelector;"
import_line = "import com.liskovsoft.smartyoutubetv2.common.aidub.AiDubAudioProcessors;"

if import_line not in text:
    if import_anchor not in text:
        raise SystemExit("Renderer import anchor not found")
    text = text.replace(import_anchor, import_line + "\n" + import_anchor, 1)

call_anchor = """                                       AudioRendererEventListener eventListener, ArrayList<Renderer> out) {
        super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, drmSessionManager, playClearSamplesWithoutKeys,
"""
patched = """                                       AudioRendererEventListener eventListener, ArrayList<Renderer> out) {
        audioProcessors = AiDubAudioProcessors.appendTap(audioProcessors);
        super.buildAudioRenderers(context, extensionRendererMode, mediaCodecSelector, drmSessionManager, playClearSamplesWithoutKeys,
"""

if "audioProcessors = AiDubAudioProcessors.appendTap(audioProcessors);" not in text:
    if call_anchor not in text:
        raise SystemExit("SmartTube 32.56s buildAudioRenderers anchor not found")
    text = text.replace(call_anchor, patched, 1)

path.write_text(text, encoding="utf-8")
print("Renderer patch applied")
PY

python3 "$ROOT/tools/patch_tv_ui.py" "$UPSTREAM"

cd "$UPSTREAM"
chmod +x gradlew
./gradlew --no-daemon :smarttubetv:assembleStstableDebug

mkdir -p "$ROOT/out"
find smarttubetv/build/outputs/apk/ststable/debug -type f -name '*.apk'   -exec cp {} "$ROOT/out/" \;

sha256sum "$ROOT"/out/*.apk
