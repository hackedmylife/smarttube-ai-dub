package com.liskovsoft.smartyoutubetv2.tv.ui.playback.actions;

import android.content.Context;

import com.liskovsoft.smartyoutubetv2.tv.R;

public final class AiDubAction extends TwoStateAction {
    public static final int ACTION_ID = 0x0A1D0B;

    public AiDubAction(Context context) {
        super(context, ACTION_ID, R.drawable.action_sound_off, false);

        String[] labels = new String[2];
        labels[INDEX_OFF] = "AI Türkçe Dublaj";
        labels[INDEX_ON] = "AI Türkçe Dublaj";
        setLabels(labels);
    }
}
