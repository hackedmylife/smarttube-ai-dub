package com.liskovsoft.smartyoutubetv2.common.aidub;

import android.os.Handler;
import android.os.Looper;

import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;

import okhttp3.OkHttpClient;

public final class AiDubController implements AiDubSession.Listener, Player.EventListener {
    public interface Listener {
        void onStateChanged(AiDubState state, Throwable error);
        default void onDiagnostic(String message) {}
    }

    private final SimpleExoPlayer player;
    private final AiDubSession session;
    private final AiDubMutePolicy mutePolicy;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private boolean enabled;
    private boolean released;

    public AiDubController(
            SimpleExoPlayer player,
            OkHttpClient httpClient,
            GeminiEndpointProvider endpointProvider,
            Listener listener) {
        if (player == null || httpClient == null || endpointProvider == null || listener == null) {
            throw new IllegalArgumentException("arguments must not be null");
        }
        this.player = player;
        this.listener = listener;
        this.mutePolicy = new AiDubMutePolicy(new ExoPlayerAudioController(player));
        this.session = new AiDubSession(httpClient, endpointProvider, this);
        player.addListener(this);
    }

    public synchronized void toggle() {
        if (enabled) disable(); else enable();
    }

    public synchronized void enable() {
        if (released || enabled) return;
        enabled = true;
        mutePolicy.onEnabled();
        session.start();
        session.setPaused(!player.getPlayWhenReady());
    }

    public synchronized void disable() {
        if (!enabled) return;
        enabled = false;
        session.stop();
        mutePolicy.onDisabled();
    }

    public synchronized void release() {
        if (released) return;
        disable();
        player.removeListener(this);
        released = true;
    }

    @Override
    public void onStateChanged(AiDubState state, Throwable error) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyStateOnMain(state, error);
        } else {
            mainHandler.post(() -> applyStateOnMain(state, error));
        }
    }

    private void applyStateOnMain(AiDubState state, Throwable error) {
        synchronized (this) {
            if (released) return;
            if (!enabled && state != AiDubState.OFF) return;
            mutePolicy.onStateChanged(state);
        }
        listener.onStateChanged(state, error);
    }

    @Override
    public void onDiagnostic(String message) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            listener.onDiagnostic(message);
        } else {
            mainHandler.post(() -> listener.onDiagnostic(message));
        }
    }

    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
        if (enabled) session.setPaused(!playWhenReady);
    }

    @Override
    public void onPositionDiscontinuity(int reason) {
        if (enabled) session.restartAfterDiscontinuity();
    }
}
