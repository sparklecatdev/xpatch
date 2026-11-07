package dev.sparklecat.xpatch;

import org.json.*;
import java.io.IOException;

public final class Profile {
    public static final String[] BAR_KEYS = {"navigation", "toolbar", "tabs", "sidebar", "actions", "compose"};
    public static final String[] BAR_LABELS = {"Bottom navigation", "Top toolbar", "Timeline tabs", "Sidebar", "Post action bar", "Compose button"};
    public static final String[][] BAR_IDS = {
        {"bottom_navigation", "bottom_navigation_view", "bottom_navigation_bar", "navigation_bar", "main_tabs"},
        {"toolbar", "top_toolbar", "app_bar", "top_app_bar"},
        {"tab_layout", "tabs", "timeline_tabs", "home_timeline_tabs"},
        {"drawer", "navigation_drawer", "side_nav", "sidebar"},
        {"tweet_actions", "tweet_action_bar", "inline_actions", "tweet_engagements"},
        {"composer_write", "composer_fab", "compose_fab", "floating_action_button"}
    };
    public static JSONObject defaults() throws JSONException {
        JSONObject root = new JSONObject().put("schema", 1).put("ads", true).put("bubble", true).put("premium", false).put("downloads", true).put("hiddenIds", "");
        root.put("navHidden", "").put("navOrder", "").put("mutedWords", "");
        for (String option : new String[]{"hideWhoToFollow", "hideDiscoverMore", "hideTopics", "hideSpaces"}) root.put(option, false);
        for (int i = 0; i < BAR_KEYS.length; i++) root.put(BAR_KEYS[i], new JSONObject().put("label", BAR_LABELS[i]).put("ids", String.join(",", BAR_IDS[i])).put("hide", false).put("scale", 100).put("color", "").put("order", ""));
        return root;
    }
    public static void validate(JSONObject root) throws IOException {
        if (root.optInt("schema", -1) != 1 || !(root.opt("ads") instanceof Boolean) || !(root.opt("bubble") instanceof Boolean)) throw new IOException("Invalid profile.");
        for (String option : new String[]{"premium", "downloads", "hideWhoToFollow", "hideDiscoverMore", "hideTopics", "hideSpaces"}) if (root.has(option) && !(root.opt(option) instanceof Boolean)) throw new IOException("Invalid option: " + option);
        for (String key : new String[]{"navHidden", "navOrder"}) {
            Object value = root.opt(key);
            if (value != null && (!(value instanceof String) || ((String) value).length() > 512 || !((String) value).matches("[A-Z_,\\s]*"))) throw new IOException("Use uppercase navigation tab names.");
        }
        for (String key : new String[]{"navHidden", "navOrder"}) for (String tab : root.optString(key, "").split("[,\\s]+")) {
            if (!tab.isEmpty() && !java.util.Arrays.asList("HOME", "EXPLORE", "GROK", "NOTIFICATIONS", "DM", "COMMUNITIES", "SPACES").contains(tab)) throw new IOException("Unknown navigation tab: " + tab);
            if (key.equals("navHidden") && tab.equals("HOME")) throw new IOException("Home must remain visible.");
        }
        Object words = root.opt("mutedWords");
        if (words != null && (!(words instanceof String) || ((String) words).length() > 4096 || ((String) words).split("\n").length > 64)) throw new IOException("Use at most 64 muted phrases and 4096 characters.");
        validateIds(root.optString("hiddenIds", ""));
        for (String key : BAR_KEYS) {
            JSONObject bar = root.optJSONObject(key);
            if (bar == null || !(bar.opt("hide") instanceof Boolean) || !(bar.opt("scale") instanceof Number)) throw new IOException("Missing bar: " + key);
            int scale = bar.optInt("scale");
            if (scale < 50 || scale > 150 || ((Number) bar.opt("scale")).doubleValue() != scale) throw new IOException("Bar scale must be a whole number from 50–150%.");
            String color = bar.optString("color", "");
            if (!color.isEmpty() && !color.matches("#[0-9a-fA-F]{6}")) throw new IOException("Use a six-digit hex color.");
            validateIds(bar.optString("order", ""));
            validateIds(bar.optString("ids", ""));
        }
    }
    static void validateIds(String text) throws IOException {
        if (text.length() > 4096) throw new IOException("Too many resource names.");
        for (String name : text.split("[,\\s]+")) if (!name.isEmpty() && !name.matches("[a-zA-Z_][a-zA-Z0-9_]*")) throw new IOException("Use resource names separated by commas.");
    }
}
