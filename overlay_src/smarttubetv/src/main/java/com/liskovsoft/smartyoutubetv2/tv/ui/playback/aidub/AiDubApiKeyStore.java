package com.liskovsoft.smartyoutubetv2.tv.ui.playback.aidub;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

public final class AiDubApiKeyStore {
    private static final String PREFS_NAME = "ai_dub_private";
    private static final String KEY_API_KEY = "gemini_api_key";

    private final SharedPreferences preferences;

    public AiDubApiKeyStore(Context context) {
        preferences = context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public String getApiKey() {
        return preferences.getString(KEY_API_KEY, "");
    }

    public boolean hasApiKey() {
        return !TextUtils.isEmpty(getApiKey());
    }

    public void setApiKey(String apiKey) {
        String value = apiKey == null ? "" : apiKey.trim();
        preferences.edit().putString(KEY_API_KEY, value).apply();
    }

    public void clear() {
        preferences.edit().remove(KEY_API_KEY).apply();
    }
}
