package com.liskovsoft.smartyoutubetv2.tv.ui.playback.aidub;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.EditText;
import android.widget.Toast;

import com.google.android.exoplayer2.SimpleExoPlayer;
import com.liskovsoft.smartyoutubetv2.common.aidub.AiDubController;
import com.liskovsoft.smartyoutubetv2.common.aidub.AiDubServices;
import com.liskovsoft.smartyoutubetv2.common.aidub.AiDubState;
import com.liskovsoft.smartyoutubetv2.tv.ui.playback.actions.AiDubAction;
import com.liskovsoft.smartyoutubetv2.tv.ui.playback.actions.TwoStateAction;
import com.liskovsoft.smartyoutubetv2.tv.ui.playback.other.VideoPlayerGlue;

public final class AiDubUiController {
    private final Context context;
    private final SimpleExoPlayer player;
    private final VideoPlayerGlue playerGlue;
    private final AiDubApiKeyStore keyStore;

    private AiDubController controller;
    private boolean enabled;

    public AiDubUiController(
            Context context,
            SimpleExoPlayer player,
            VideoPlayerGlue playerGlue) {
        if (context == null || player == null || playerGlue == null) {
            throw new IllegalArgumentException("context/player/playerGlue must not be null");
        }
        this.context = context;
        this.player = player;
        this.playerGlue = playerGlue;
        this.keyStore = new AiDubApiKeyStore(context);
        setButtonEnabled(false);
    }

    public boolean handles(int actionId) {
        return AiDubAction.ACTION_ID == actionId;
    }

    public void onAction() {
        if (!keyStore.hasApiKey()) {
            showSetupDialog();
            return;
        }

        ensureController();

        if (enabled) {
            controller.disable();
            enabled = false;
            setButtonEnabled(false);
            Toast.makeText(context, "AI Türkçe Dublaj kapatıldı", Toast.LENGTH_SHORT).show();
        } else {
            enabled = true;
            setButtonEnabled(true);
            Toast.makeText(context, "AI Türkçe Dublaj bağlanıyor…", Toast.LENGTH_SHORT).show();
            controller.enable();
        }
    }

    public void release() {
        enabled = false;
        setButtonEnabled(false);
        if (controller != null) {
            controller.release();
            controller = null;
        }
    }

    private void showSetupDialog() {
        EditText input = new EditText(context);
        input.setSingleLine(true);
        input.setHint("Gemini API key");
        input.setInputType(
                InputType.TYPE_CLASS_TEXT |
                        InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setText(keyStore.getApiKey());
        input.setSelectAllOnFocus(true);

        new AlertDialog.Builder(context)
                .setTitle("AI Türkçe Dublaj")
                .setMessage("Gemini API anahtarını gir. Anahtar yalnızca bu cihazda saklanır; APK içine eklenmez.")
                .setView(input)
                .setPositiveButton("Kaydet ve Başlat", (dialog, which) -> {
                    String value = input.getText() == null
                            ? ""
                            : input.getText().toString().trim();
                    if (TextUtils.isEmpty(value)) {
                        Toast.makeText(context, "API anahtarı boş olamaz", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    keyStore.setApiKey(value);
                    resetController();
                    ensureController();

                    enabled = true;
                    setButtonEnabled(true);
                    Toast.makeText(context, "AI Türkçe Dublaj bağlanıyor…", Toast.LENGTH_SHORT).show();
                    controller.enable();
                })
                .setNeutralButton("Anahtarı Sil", (dialog, which) -> {
                    keyStore.clear();
                    enabled = false;
                    resetController();
                    setButtonEnabled(false);
                    Toast.makeText(context, "AI Dublaj anahtarı silindi", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("İptal", null)
                .show();
    }

    private void ensureController() {
        if (controller != null) {
            return;
        }

        AiDubServices.setEndpointProvider(new GeminiApiKeyEndpointProvider(keyStore));
        controller = AiDubServices.createController(player, this::onStateChanged);
    }

    private void resetController() {
        if (controller != null) {
            controller.release();
            controller = null;
        }
    }

    private void onStateChanged(AiDubState state, Throwable error) {
        if (state == AiDubState.ERROR) {
            enabled = false;
            setButtonEnabled(false);
            String detail = error != null && !TextUtils.isEmpty(error.getMessage())
                    ? ": " + error.getMessage()
                    : "";
            Toast.makeText(
                    context,
                    "AI Dublaj bağlantı hatası" + detail,
                    Toast.LENGTH_LONG).show();
        } else if (state == AiDubState.OFF) {
            setButtonEnabled(false);
        } else if (state == AiDubState.CONNECTING
                || state == AiDubState.READY
                || state == AiDubState.DUBBING) {
            setButtonEnabled(true);
        }
    }

    private void setButtonEnabled(boolean value) {
        playerGlue.setButtonState(
                AiDubAction.ACTION_ID,
                value ? TwoStateAction.INDEX_ON : TwoStateAction.INDEX_OFF);
    }
}
