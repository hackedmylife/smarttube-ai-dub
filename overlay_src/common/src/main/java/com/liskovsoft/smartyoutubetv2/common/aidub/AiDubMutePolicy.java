package com.liskovsoft.smartyoutubetv2.common.aidub;

public final class AiDubMutePolicy {
    private final PlayerAudioController audioController;
    private boolean enabled;
    private boolean originalMuted;
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
        originalMuted = false;
        restoreVolume = audioController.getOriginalVolume();
    }

    public synchronized void onStateChanged(AiDubState state) {
        if (!enabled) {
            return;
        }
        if (state == AiDubState.DUBBING) {
            if (!originalMuted) {
                audioController.setOriginalVolume(0.0f);
                originalMuted = true;
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
        if (originalMuted) {
            audioController.setOriginalVolume(restoreVolume);
            originalMuted = false;
        }
    }
}
