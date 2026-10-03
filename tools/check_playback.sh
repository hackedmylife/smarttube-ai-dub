#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$(mktemp -d)"
trap 'rm -rf "$DEST"' EXIT
SRC="$ROOT/overlay_src/common/src/main/java/com/liskovsoft/smartyoutubetv2/common/aidub"
javac -d "$DEST" "$ROOT"/tests/playback/android/media/*.java \
 "$SRC"/{AiDubConfig,AiDubAudioPlayer,AiDubMutePolicy,AiDubState,PlayerAudioController}.java \
 "$ROOT/tests/playback/com/liskovsoft/smartyoutubetv2/common/aidub/PlaybackCheck.java"
java -cp "$DEST" com.liskovsoft.smartyoutubetv2.common.aidub.PlaybackCheck
