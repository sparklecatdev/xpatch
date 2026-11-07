package com.twitter.android;
public final class DisplayState {
    final boolean a, b;
    public DisplayState(boolean premium, boolean subscribe) { a = premium; b = subscribe; }
    @Override public String toString() {
        return new StringBuilder("DisplaySettingsState(darkModeState=").append("default")
            .append(", hasPremium=").append(a).append(", canSubscribe=").append(b).toString();
    }
}
