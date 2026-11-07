package com.twitter.android;

import android.app.Activity;
import android.os.Bundle;
import android.widget.*;

/** Synthetic APK only. This does not contain or emulate the X client. */
public final class FixtureActivity extends Activity {
    private TextView label(String value) {
        TextView view = new TextView(this); view.setText(value); view.setTextSize(20); view.setPadding(24, 24, 24, 24); return view;
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout page = new LinearLayout(this); page.setOrientation(1); page.setPadding(16, 90, 16, 80); setContentView(page);
        TextView toolbar = label("Synthetic timeline fixture"); toolbar.setId(R.id.toolbar); page.addView(toolbar);
        int count = 0;
        for (String id : new String[]{"promoted-tweet-1", "tweet-2", "rtb-3"}) {
            b entry = new a().parse(id);
            if (entry != null) { page.addView(label(entry.q)); count++; }
        }
        page.addView(label("Visible entries: " + count));
        java.util.List<?> modern = (java.util.List<?>) new kotlinx.serialization.internal.a().deserialize(null);
        for (Object item : modern) page.addView(label(((c) item).x));
        page.addView(label("Modern entries: " + modern.size()));
        LinearLayout navigation = new LinearLayout(this); navigation.setId(R.id.bottom_navigation);
        String[] names = {"Home", "Search", "Profile"}; int[] ids = {R.id.home, R.id.search, R.id.profile};
        for (int i = 0; i < names.length; i++) { TextView child = label(names[i]); child.setId(ids[i]); navigation.addView(child); }
        page.addView(navigation);
        new MediaSerializer().deserialize(null); new MediaSerializer.VideoSerializer().deserialize(null);
        page.addView(label("Premium display: " + new DisplayState(false, true).a));
    }
}
