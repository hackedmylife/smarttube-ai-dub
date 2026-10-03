package com.liskovsoft.smartyoutubetv2.common.aidub;

public final class AiDubMutePolicy {
    // Keep the original mix audible enough for ambience/music/effects while the
    // Turkish native-audio dub stays dominant. This is still whole-mix ducking,
    // so raising it too far would also make the source-language dialogue louder.
    private static final float DUBBED_ORIGINAL_VOLUME_FACTOR = 0.08f;

    private final PlayerAudioController audioController;
    private boolean enabled;
    private boolean originalDucked;
    private float restoreVolume = 1.0f;

    public AiDubMutePolicy(PlayerAudioController audioController) {
        if (audioController == null) {
            throw new IllegalArgumentException("audioController == null");
        }
        this.audioController = audioController;
    }

    public synchronized void onEnabled() {
        if (enabled) return;
        enabled = true;
        originalDucked = false;
        restoreVolume = audioController.getOriginalVolume();
    }

    public synchronized void onStateChanged(AiDubState state) {
        if (!enabled) return;

        // Duck as soon as the Live session is ready so the viewer doesn't hear
        // a loud source phrase immediately followed by the Turkish equivalent.
        if (state == AiDubState.READY || state == AiDubState.DUBBING) {
            if (!originalDucked) {
                float dubbedBackgroundVolume = Math.max(
                        0.0f,
                        Math.min(1.0f, restoreVolume * DUBBED_ORIGINAL_VOLUME_FACTOR));
                audioController.setOriginalVolume(dubbedBackgroundVolume);
                originalDucked = true;
            }
        } else {
            restoreOriginal();
        }
    }

    public synchronized void onDisabled() {
        if (!enabled) return;
        restoreOriginal();
        enabled = false;
    }

    private void restoreOriginal() {
        if (originalDucked) {
            audioController.setOriginalVolume(restoreVolume);
            originalDucked = false;
        }
    }
}
