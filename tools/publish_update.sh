#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/out"
DIST="$ROOT/update-dist"
REPO="hackedmylife/smarttube-ai-dub"
TAG="ai-dub-latest"

: "${GH_TOKEN:?GH_TOKEN is required}"
: "${GITHUB_RUN_NUMBER:?GITHUB_RUN_NUMBER is required}"

VERSION_CODE=$((900000 + GITHUB_RUN_NUMBER))
VERSION_NAME="0.8.${GITHUB_RUN_NUMBER}"

rm -rf "$DIST"
mkdir -p "$DIST"

copy_match() {
  local pattern="$1"
  local target="$2"
  local src
  src="$(find "$OUT" -maxdepth 1 -type f -name "$pattern" | head -n1 || true)"
  if [[ -n "$src" ]]; then
    cp "$src" "$DIST/$target"
  fi
}

copy_match '*_universal.apk' 'smarttube_ai_dub.apk'
copy_match '*_arm64-v8a.apk' 'smarttube_ai_dub_arm64-v8a.apk'
copy_match '*_armeabi-v7a.apk' 'smarttube_ai_dub_armeabi-v7a.apk'
copy_match '*_x86.apk' 'smarttube_ai_dub_x86.apk'

if [[ ! -f "$DIST/smarttube_ai_dub.apk" ]]; then
  echo "Universal APK was not produced" >&2
  exit 1
fi

cat > "$DIST/smarttube_ai_dub.json" <<EOF
{
  "package": {
    "downloadUrlList_arm64-v8a": [
      "https://github.com/$REPO/releases/download/$TAG/smarttube_ai_dub_arm64-v8a.apk",
      "https://github.com/$REPO/releases/download/$TAG/smarttube_ai_dub.apk"
    ],
    "downloadUrlList_armeabi-v7a": [
      "https://github.com/$REPO/releases/download/$TAG/smarttube_ai_dub_armeabi-v7a.apk",
      "https://github.com/$REPO/releases/download/$TAG/smarttube_ai_dub.apk"
    ],
    "downloadUrlList_x86": [
      "https://github.com/$REPO/releases/download/$TAG/smarttube_ai_dub_x86.apk",
      "https://github.com/$REPO/releases/download/$TAG/smarttube_ai_dub.apk"
    ],
    "downloadUrlList": [
      "https://github.com/$REPO/releases/download/$TAG/smarttube_ai_dub.apk"
    ]
  },
  "$VERSION_NAME": {
    "versionCode": $VERSION_CODE,
    "changelog": [
      "SmartTube AI Dub $VERSION_NAME",
      "Gemini Live Turkish dubbing updates",
      "Automatic in-app updater enabled"
    ],
    "changelog_tr": [
      "SmartTube AI Dub $VERSION_NAME",
      "Gemini Live Türkçe dublaj geliştirmeleri",
      "Uygulama içi otomatik güncelleme etkin"
    ]
  }
}
EOF

if ! gh release view "$TAG" --repo "$REPO" >/dev/null 2>&1; then
  gh release create "$TAG"     --repo "$REPO"     --title "SmartTube AI Dub - Latest"     --notes "Automatically published signed SmartTube AI Dub build."
fi

gh release upload "$TAG"   "$DIST/smarttube_ai_dub.json"   "$DIST/smarttube_ai_dub.apk"   --repo "$REPO"   --clobber

for apk in   "$DIST/smarttube_ai_dub_arm64-v8a.apk"   "$DIST/smarttube_ai_dub_armeabi-v7a.apk"   "$DIST/smarttube_ai_dub_x86.apk"; do
  if [[ -f "$apk" ]]; then
    gh release upload "$TAG" "$apk" --repo "$REPO" --clobber
  fi
done

gh release edit "$TAG"   --repo "$REPO"   --title "SmartTube AI Dub $VERSION_NAME"   --notes "Latest permanently signed SmartTube AI Dub build. In-app update manifest is included."

echo "Published SmartTube AI Dub $VERSION_NAME (versionCode $VERSION_CODE)"
cat "$DIST/smarttube_ai_dub.json"
