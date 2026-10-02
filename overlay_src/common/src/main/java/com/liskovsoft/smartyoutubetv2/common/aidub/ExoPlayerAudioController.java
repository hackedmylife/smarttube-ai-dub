package com.liskovsoft.smartyoutubetv2.common.aidub;

import com.google.android.exoplayer2.SimpleExoPlayer;

public final class ExoPlayerAudioController implements PlayerAudioController {
    private final SimpleExoPlayer player;

    public ExoPlayerAudioController(SimpleExoPlayer player) {
        if (player == null) {
            throw new IllegalArgumentException("player == null");
        }
        this.player = player;
    }

    @Override
    public void setOriginalVolume(float volume) {
        player.setVolume(Math.max(0.0f, Math.min(1.0f, volume)));
    }

    @Override
    public float getOriginalVolume() {
        return player.getVolume();
    }
}
