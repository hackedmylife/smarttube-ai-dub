# SmartTube AI Dub — v0.4 build automation

v0.4 turns the tested v0.3 AI-dubbing engine into a deterministic SmartTube build overlay pinned to **SmartTube 32.56 Stable (`32.56s`)**.

## Product behavior

- Source speech language: automatic detection.
- Target language: Turkish (`tr`).
- Original foreign program audio remains audible while connecting/waiting.
- On the first translated Turkish PCM packet, the original SmartTube program audio is muted and the Turkish AI stream plays.
- Seek/discontinuity clears local translated audio and reconnects Gemini with fresh credentials; original audio temporarily returns until new Turkish PCM arrives.
- Error/off/player destruction restores the exact pre-dub SmartTube volume.
- No API key/token file is packaged.

## One-command patch of an existing checkout

```sh
./tools/apply_all_v04.sh /path/to/SmartTube
```

This dry-runs both core patchers first, then applies them only if every anchor is unambiguous.

## Local build

Requirements: Git, Android SDK, JDK 17 and network access.

```sh
./tools/build_v04_local.sh
```

It clones SmartTube `32.56s` recursively, applies AI Dub, and runs:

```sh
./gradlew --no-daemon clean :smarttubetv:assembleStstableDebug
```

## GitHub Actions build

The included `.github/workflows/build-ai-dub-apk.yml` performs the complete build on an Android-capable GitHub runner and uploads all stable debug APKs as an artifact.

## Tests

```sh
./tests/v04/run_v04_tests.sh
```

No API key/token/keystore is committed to this repository.
