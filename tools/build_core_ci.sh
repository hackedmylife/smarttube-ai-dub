#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${RUNNER_TEMP:-/tmp}/smarttube-ai-dub-build"
UPSTREAM="$WORK/SmartTube"

: "${AIDUB_KEYSTORE_B64:?AIDUB_KEYSTORE_B64 secret is required}"
: "${AIDUB_KEYSTORE_PASSWORD:?AIDUB_KEYSTORE_PASSWORD secret is required}"
AIDUB_BUILD_NUMBER="${GITHUB_RUN_NUMBER:-1}"

rm -rf "$WORK"
mkdir -p "$WORK"

git clone --recursive --branch 32.56s --depth 1   https://github.com/yuliskov/SmartTube.git "$UPSTREAM"

SIGNING_KEYSTORE="$WORK/smarttube-ai-dub-release.jks"
printf '%s' "$AIDUB_KEYSTORE_B64" | base64 --decode > "$SIGNING_KEYSTORE"
chmod 600 "$SIGNING_KEYSTORE"
cat > "$UPSTREAM/keystore.properties" <<EOF
storeFile=$SIGNING_KEYSTORE
storePassword=$AIDUB_KEYSTORE_PASSWORD
keyAlias=aidub
keyPassword=$AIDUB_KEYSTORE_PASSWORD
EOF

cp -R "$ROOT/overlay_src/common/src/main/java/com/liskovsoft/smartyoutubetv2/common/aidub"   "$UPSTREAM/common/src/main/java/com/liskovsoft/smartyoutubetv2/common/"

cp -R "$ROOT/overlay_src/smarttubetv/src/main/java/com/liskovsoft/smartyoutubetv2/tv/ui/playback/aidub" \
  "$UPSTREAM/smarttubetv/src/main/java/com/liskovsoft/smartyoutubetv2/tv/ui/playback/"

cp "$ROOT/overlay_src/smarttubetv/src/main/java/com/liskovsoft/smartyoutubetv2/tv/ui/playback/actions/AiDubAction.java" \
  "$UPSTREAM/smarttubetv/src/main/java/com/liskovsoft/smartyoutubetv2/tv/ui/playback/actions/AiDubAction.java"

mkdir -p "$UPSTREAM/common/src/stbeta/res/values"
cp "$ROOT/overlay_src/common/src/stbeta/res/values/update_urls.xml" \
  "$UPSTREAM/common/src/stbeta/res/values/update_urls.xml"

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

# Route SmartTube's built-in updater to the AI Dub release channel.
cat > "$UPSTREAM/common/src/stbeta/res/values/update_urls.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string-array name="update_urls">
        <item>https://github.com/hackedmylife/smarttube-ai-dub/releases/download/ai-dub-latest/smarttube_ai_dub.json</item>
    </string-array>
</resources>
EOF

python3 - "$UPSTREAM" <<'PY'
from pathlib import Path
import sys
p = Path(sys.argv[1]) / "smarttubetv/src/stbeta/res/values/strings.xml"
text = p.read_text(encoding="utf-8")
text = text.replace("SmartTube beta", "SmartTube AI Dub")
p.write_text(text, encoding="utf-8")
print("Renamed beta flavor to SmartTube AI Dub")
PY

python3 - "$UPSTREAM" "$AIDUB_BUILD_NUMBER" <<'PY'
from pathlib import Path
import sys
root = Path(sys.argv[1])
run = int(sys.argv[2])
path = root / "smarttubetv/build.gradle"
text = path.read_text(encoding="utf-8")
text = text.replace("versionCode 2446", f"versionCode {900000 + run}", 1)
text = text.replace('versionName "32.56"', f'versionName "0.8.{run}"', 1)
path.write_text(text, encoding="utf-8")
print(f"SmartTube AI Dub versionCode={900000 + run}, versionName=0.8.{run}")
PY

cd "$UPSTREAM"
chmod +x gradlew
./gradlew --no-daemon :smarttubetv:assembleStbetaDebug

mkdir -p "$ROOT/out"
find smarttubetv/build/outputs/apk/stbeta/debug -type f -name '*.apk'   -exec cp {} "$ROOT/out/" \;

sha256sum "$ROOT"/out/*.apk
