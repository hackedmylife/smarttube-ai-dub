#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$(mktemp -d)"
trap 'rm -rf "$DEST"' EXIT
SRC="$ROOT/overlay_src/common/src/main/java/com/liskovsoft/smartyoutubetv2/common/aidub"
javac -d "$DEST" "$ROOT"/tests/playback/android/media/*.java \
 "$SRC"/{AiDubConfig,AiDubAudioPlayer,AiDubMutePolicy,AiDubState,PlayerAudioController,GeminiTranslationSetup}.java \
 "$ROOT"/tests/playback/com/liskovsoft/smartyoutubetv2/common/aidub/*.java
java -cp "$DEST" com.liskovsoft.smartyoutubetv2.common.aidub.PlaybackCheck
java -cp "$DEST" com.liskovsoft.smartyoutubetv2.common.aidub.TranslationSetupCheck | python3 -c '
import json,sys
s=json.load(sys.stdin)["setup"]
assert s["model"] == "models/gemini-3.5-live-translate-preview"
g=s["generationConfig"]
assert g["responseModalities"] == ["AUDIO"]
assert g["translationConfig"] == {"targetLanguageCode":"tr", "echoTargetLanguage":True}
assert set(s)=={"model","generationConfig"}
assert set(g)=={"responseModalities","translationConfig"}
print("PASS: translator WebSocket setup, Turkish echo and PCM profile")
'
