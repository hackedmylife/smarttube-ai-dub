package com.liskovsoft.smartyoutubetv2.common.aidub;

public final class AiDubMutePolicy {
    private static final float DUBBED_ORIGINAL_VOLUME_FACTOR = 0.18f;

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
        if (enabled) {
            return;
        }
        enabled = true;
        originalDucked = false;
        restoreVolume = audioController.getOriginalVolume();
    }

    public synchronized void onStateChanged(AiDubState state) {
        if (!enabled) {
            return;
        }
        if (state == AiDubState.DUBBING) {
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
        if (!enabled) {
            return;
        }
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
