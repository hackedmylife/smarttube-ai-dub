package com.liskovsoft.smartyoutubetv2.tv.ui.playback.actions;

import android.content.Context;

import androidx.leanback.widget.ArrayObjectAdapter;
import androidx.leanback.widget.ObjectAdapter;

import com.liskovsoft.smartyoutubetv2.common.aidub.AiDubState;
import com.liskovsoft.smartyoutubetv2.tv.ui.playback.other.VideoPlayerGlue;

public final class AiDubActionHost {
    private final AiDubAction action;
    private final VideoPlayerGlue playerGlue;
    private ArrayObjectAdapter adapter;

    public AiDubActionHost(Context context, VideoPlayerGlue playerGlue, boolean configured) {
        this.playerGlue = playerGlue;
        this.action = new AiDubAction(context, configured);
    }

    public boolean install() {
        ObjectAdapter candidate = playerGlue.getControlsRow().getSecondaryActionsAdapter();
        if (!(candidate instanceof ArrayObjectAdapter)) {
            return false;
        }
        adapter = (ArrayObjectAdapter) candidate;
        if (adapter.indexOf(action) < 0) {
            adapter.add(action);
        }
        return true;
    }

    public boolean handles(int actionId) {
        return AiDubAction.matches(actionId);
    }

    public void update(AiDubState state, Throwable error) {
        action.setState(state, error);
        if (adapter != null) {
            int index = adapter.indexOf(action);
            if (index >= 0) {
                adapter.notifyArrayItemRangeChanged(index, 1);
            }
        }
    }

    public void detach() {
        if (adapter != null) {
            int index = adapter.indexOf(action);
            if (index >= 0) {
                adapter.removeItems(index, 1);
            }
            adapter = null;
        }
    }
}
