package com.liskovsoft.smartyoutubetv2.tv.ui.playback.actions;

import android.content.Context;
import androidx.core.content.ContextCompat;
import androidx.leanback.widget.Action;
import com.liskovsoft.smartyoutubetv2.common.aidub.AiDubState;
import com.liskovsoft.smartyoutubetv2.tv.R;

public final class AiDubAction extends Action {
    public static final int ACTION_ID = 0x0A1D0B;
    private final Context context;

    public AiDubAction(Context context, boolean configured) {
        super(ACTION_ID);
        this.context = context;
        setIcon(ContextCompat.getDrawable(context, R.drawable.action_ai_dub));
        setLabel1(context.getString(R.string.ai_dub_title));
        setLabel2(context.getString(configured ? R.string.ai_dub_off : R.string.ai_dub_setup_required));
    }

    public static boolean matches(int actionId) {
        return actionId == ACTION_ID;
    }

    public void setState(AiDubState state, Throwable error) {
        int statusRes;
        if (state == AiDubState.CONNECTING) statusRes = R.string.ai_dub_connecting;
        else if (state == AiDubState.READY) statusRes = R.string.ai_dub_ready;
        else if (state == AiDubState.DUBBING) statusRes = R.string.ai_dub_on;
        else if (state == AiDubState.ERROR) statusRes = R.string.ai_dub_error;
        else statusRes = R.string.ai_dub_off;
        setLabel2(context.getString(statusRes));
    }
}
