package dev.sparklecat.xpatch.runtime;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Injected runtime; only uses Android APIs and schema information discovered in this APK. */
public final class Mods implements Application.ActivityLifecycleCallbacks {
    private static final String[] BARS = {"navigation", "toolbar", "tabs", "sidebar", "actions", "compose"};
    private static final String BUBBLE = "xpatch.settings";
    private static volatile JSONObject config;
    private static JSONObject schema;
    private static SharedPreferences preferences;
    private static final Map<String, Field> entryFields = new ConcurrentHashMap<>();
    private final Map<Activity, ViewTreeObserver.OnGlobalLayoutListener> listeners = new WeakHashMap<>();
    private final Map<View, Original> originals = new WeakHashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<Activity, Runnable> pending = new WeakHashMap<>();

    public static synchronized void install(Application application) {
        if (config != null) return;
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = application.getAssets().open("xpatch/profile.json")) {
                byte[] block = new byte[8192]; int n;
                while ((n = in.read(block)) != -1) { if (bytes.size() + n > 128 * 1024) throw new IOException("Profile too large"); bytes.write(block, 0, n); }
            }
            schema = new JSONObject(bytes.toString("UTF-8"));
            preferences = application.getSharedPreferences("xpatch.mods.v1", Context.MODE_PRIVATE);
            JSONObject initial = schema.getJSONObject("profile");
            try { config = new JSONObject(preferences.getString("profile", initial.toString())); }
            catch (JSONException badSavedProfile) { config = initial; }
            application.registerActivityLifecycleCallbacks(new Mods());
        } catch (Exception ignored) {
            // A failed optional runtime must leave the original app usable.
        }
    }
    public static Object filter(Object entry) {
        JSONObject options = config;
        if (entry == null || options == null || !options.optBoolean("ads", false)) return entry;
        if (entry instanceof List<?>) {
            List<?> list = (List<?>) entry;
            ArrayList<Object> kept = null;
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                boolean remove = item != null && filter(item) == null;
                if (remove && kept == null) kept = new ArrayList<>(list.subList(0, i));
                if (!remove && kept != null) kept.add(item);
            }
            return kept == null ? entry : kept;
        }
        try {
            Class<?> type = entry.getClass();
            String promoted = schema.optJSONObject("promotedFields") == null ? "" : schema.getJSONObject("promotedFields").optString(type.getName(), "");
            if (!promoted.isEmpty()) {
                Field marker = type.getDeclaredField(promoted); marker.setAccessible(true);
                if (marker.get(entry) != null) return null;
            }
            String name = schema.getJSONObject("entryFields").optString(type.getName(), "");
            if (name.isEmpty()) return entry;
            Field field = entryFields.get(type.getName());
            if (field == null) { field = type.getDeclaredField(name); field.setAccessible(true); entryFields.put(type.getName(), field); }
            Object value = field.get(entry);
            if (value instanceof String) {
                String id = ((String) value).toLowerCase(Locale.ROOT);
                if (id.startsWith("promoted-") || id.startsWith("promoted_") || id.startsWith("promotedtweet-") || id.startsWith("rtb-")) return null;
            }
        } catch (Exception ignored) { }
        return entry;
    }
    private static String id(View view) {
        if (view.getId() == View.NO_ID) return "";
        try { return view.getResources().getResourceEntryName(view.getId()); }
        catch (Exception ignored) { return ""; }
    }
    private static List<String> names(String text) {
        List<String> result = new ArrayList<>();
        for (String value : text.split("[,\\s]+")) if (!value.isEmpty()) result.add(value);
        return result;
    }
    private static final class Original {
        final int visibility;
        final float x, y;
        final Drawable background;
        final List<WeakReference<View>> children = new ArrayList<>();
        Original(View view) {
            visibility = view.getVisibility(); x = view.getScaleX(); y = view.getScaleY(); background = view.getBackground();
            if (view instanceof LinearLayout) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) children.add(new WeakReference<>(((ViewGroup) view).getChildAt(i)));
        }
        void restore(View view) {
            view.setVisibility(visibility); view.setScaleX(x); view.setScaleY(y); view.setBackground(background);
            if (view instanceof LinearLayout) for (WeakReference<View> child : children) { View v = child.get(); if (v != null && v.getParent() == view) ((ViewGroup) view).bringChildToFront(v); }
        }
    }
    private void apply(View view, Set<String> hidden, int depth, int[] budget) {
        if (depth > 60 || --budget[0] < 0 || BUBBLE.equals(view.getTag())) return;
        String name = id(view);
        JSONObject current = config;
        if (hidden.contains(name)) {
            if (!originals.containsKey(view)) originals.put(view, new Original(view));
            if (view.getVisibility() != View.GONE) view.setVisibility(View.GONE);
        }
        for (String key : BARS) {
            JSONObject bar = current.optJSONObject(key);
            if (bar == null || !names(bar.optString("ids")).contains(name)) continue;
            boolean hide = bar.optBoolean("hide");
            int scale = Math.max(50, Math.min(150, bar.optInt("scale", 100)));
            String color = bar.optString("color", "");
            List<String> order = names(bar.optString("order", ""));
            if (!hide && scale == 100 && color.isEmpty() && order.isEmpty()) continue;
            if (!originals.containsKey(view)) originals.put(view, new Original(view));
            Original original = originals.get(view);
            if (hide && view.getVisibility() != View.GONE) view.setVisibility(View.GONE);
            float factor = scale / 100f;
            if (view.getScaleX() != original.x * factor) view.setScaleX(original.x * factor);
            if (view.getScaleY() != original.y * factor) view.setScaleY(original.y * factor);
            if (color.matches("#[0-9a-fA-F]{6}")) {
                int tint = Color.parseColor(color);
                if (!(view.getBackground() instanceof android.graphics.drawable.ColorDrawable) || ((android.graphics.drawable.ColorDrawable) view.getBackground()).getColor() != tint) view.setBackgroundColor(tint);
            }
            if (view instanceof LinearLayout && !order.isEmpty()) {
                ViewGroup group = (ViewGroup) view;
                List<View> children = new ArrayList<>();
                for (int i = 0; i < group.getChildCount(); i++) children.add(group.getChildAt(i));
                List<View> sorted = new ArrayList<>();
                for (String childId : order) for (View child : children) if (id(child).equals(childId) && !sorted.contains(child)) sorted.add(child);
                for (View child : children) if (!sorted.contains(child)) sorted.add(child);
                if (!sorted.equals(children)) for (View child : sorted) group.bringChildToFront(child);
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) apply(group.getChildAt(i), hidden, depth + 1, budget);
        }
    }
    private void scan(Activity activity) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        View root = activity.getWindow().getDecorView();
        apply(root, new HashSet<>(names(config.optString("hiddenIds", ""))), 0, new int[]{6000});
        if (config.optBoolean("bubble", true) && root instanceof FrameLayout && root.findViewWithTag(BUBBLE) == null) {
            TextView bubble = new TextView(activity);
            bubble.setTag(BUBBLE); bubble.setText("XP"); bubble.setTextColor(0xff21182f); bubble.setTextSize(12); bubble.setGravity(Gravity.CENTER); bubble.setContentDescription("XPatch mods");
            GradientDrawable background = new GradientDrawable(); background.setColor(0xffd7b8ff); background.setCornerRadius(100); bubble.setBackground(background);
            int size = (int) (36 * activity.getResources().getDisplayMetrics().density);
            FrameLayout.LayoutParams layout = new FrameLayout.LayoutParams(size, size, Gravity.END | Gravity.CENTER_VERTICAL); layout.rightMargin = size / 3;
            ((FrameLayout) root).addView(bubble, layout);
            bubble.setOnClickListener(v -> menu(activity));
        }
    }
    private void save(Activity activity, JSONObject next) {
        if (!preferences.edit().putString("profile", next.toString()).commit()) { Toast.makeText(activity, "Could not save mods", Toast.LENGTH_LONG).show(); return; }
        config = next;
        for (Map.Entry<View, Original> entry : originals.entrySet()) entry.getValue().restore(entry.getKey());
        originals.clear();
        scan(activity);
    }
    private void menu(Activity activity) {
        List<String> items = new ArrayList<>();
        items.add("Promoted posts: " + (config.optBoolean("ads") ? "hidden" : "shown"));
        for (String key : BARS) items.add(config.optJSONObject(key).optString("label", key));
        items.add("Inspect this screen"); items.add("Reset mods");
        new AlertDialog.Builder(activity).setTitle("XPatch mods").setItems(items.toArray(new String[0]), (dialog, which) -> {
            try {
                JSONObject next = new JSONObject(config.toString());
                if (which == 0) {
                    if (schema.getJSONObject("entryFields").length() == 0) { Toast.makeText(activity, "No compatible ad hook in this build", Toast.LENGTH_LONG).show(); return; }
                    next.put("ads", !next.optBoolean("ads")); save(activity, next);
                } else if (which <= BARS.length) editBar(activity, BARS[which - 1]);
                else if (which == BARS.length + 1) inspect(activity);
                else save(activity, new JSONObject(schema.getJSONObject("profile").toString()));
            } catch (JSONException e) { Toast.makeText(activity, "Invalid mod settings", Toast.LENGTH_LONG).show(); }
        }).setNegativeButton("Close", null).show();
    }
    private static EditText input(Activity activity, LinearLayout parent, String hint, String value) {
        EditText field = new EditText(activity); field.setHint(hint); field.setText(value); field.setSingleLine(true); parent.addView(field); return field;
    }
    private void editBar(Activity activity, String key) {
        JSONObject bar = config.optJSONObject(key);
        LinearLayout layout = new LinearLayout(activity); layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(32, 16, 32, 16);
        Switch hide = new Switch(activity); hide.setText("Hide this bar"); hide.setChecked(bar.optBoolean("hide")); layout.addView(hide);
        EditText ids = input(activity, layout, "Resource names, comma separated", bar.optString("ids"));
        EditText scale = input(activity, layout, "Visual scale: 50–150%", "" + bar.optInt("scale", 100)); scale.setInputType(2);
        EditText color = input(activity, layout, "Background: #RRGGBB or empty", bar.optString("color"));
        EditText order = input(activity, layout, "Child resource names in order (LinearLayout)", bar.optString("order"));
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(bar.optString("label", key)).setView(layout).setPositiveButton("Save", null).setNegativeButton("Cancel", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(-1).setOnClickListener(v -> {
            try {
                int percent = Integer.parseInt(scale.getText().toString().trim());
                String tint = color.getText().toString().trim();
                if (percent < 50 || percent > 150 || !tint.isEmpty() && !tint.matches("#[0-9a-fA-F]{6}")) throw new IllegalArgumentException();
                for (String text : Arrays.asList(ids.getText().toString(), order.getText().toString())) {
                    if (text.length() > 4096) throw new IllegalArgumentException();
                    for (String name : names(text)) if (!name.matches("[a-zA-Z_][a-zA-Z0-9_]*")) throw new IllegalArgumentException();
                }
                JSONObject next = new JSONObject(config.toString());
                next.getJSONObject(key).put("hide", hide.isChecked()).put("ids", ids.getText().toString()).put("scale", percent).put("color", tint).put("order", order.getText().toString());
                save(activity, next); dialog.dismiss();
            } catch (Exception e) { Toast.makeText(activity, "Check resource names, scale and hex color", Toast.LENGTH_LONG).show(); }
        })); dialog.show();
    }
    private static void collect(View view, Set<String> ids, int depth, int[] budget) {
        if (depth > 60 || --budget[0] < 0 || BUBBLE.equals(view.getTag())) return;
        String name = id(view); if (!name.isEmpty()) ids.add(name);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) collect(((ViewGroup) view).getChildAt(i), ids, depth + 1, budget);
    }
    private void inspect(Activity activity) {
        Set<String> found = new TreeSet<>(); collect(activity.getWindow().getDecorView(), found, 0, new int[]{6000});
        if (found.isEmpty()) { Toast.makeText(activity, "No named Android views on this screen", Toast.LENGTH_LONG).show(); return; }
        String[] ids = found.toArray(new String[0]);
        new AlertDialog.Builder(activity).setTitle("Choose a view").setItems(ids, (d, index) -> {
            List<String> actions = new ArrayList<>(); actions.add("Toggle hidden");
            for (String key : BARS) actions.add("Assign to " + config.optJSONObject(key).optString("label", key));
            new AlertDialog.Builder(activity).setTitle(ids[index]).setItems(actions.toArray(new String[0]), (dialog, choice) -> {
                try {
                    JSONObject next = new JSONObject(config.toString());
                    if (choice == 0) {
                        List<String> hidden = names(next.optString("hiddenIds"));
                        if (!hidden.remove(ids[index])) hidden.add(ids[index]);
                        next.put("hiddenIds", android.text.TextUtils.join(",", hidden));
                    } else {
                        JSONObject bar = next.getJSONObject(BARS[choice - 1]);
                        List<String> names = names(bar.optString("ids")); if (!names.contains(ids[index])) names.add(ids[index]);
                        bar.put("ids", android.text.TextUtils.join(",", names));
                    }
                    save(activity, next);
                } catch (JSONException ignored) { Toast.makeText(activity, "Could not update this view", Toast.LENGTH_LONG).show(); }
            }).setNegativeButton("Cancel", null).show();
        }).setNegativeButton("Close", null).show();
    }
    @Override public void onActivityResumed(Activity activity) {
        if (listeners.containsKey(activity)) return;
        WeakReference<Activity> reference = new WeakReference<>(activity);
        Runnable scan = () -> { Activity current = reference.get(); if (current != null) { pending.remove(current); scan(current); } };
        ViewTreeObserver.OnGlobalLayoutListener listener = () -> { Activity current = reference.get(); if (current != null && !pending.containsKey(current)) { pending.put(current, scan); handler.postDelayed(scan, 200); } };
        listeners.put(activity, listener); activity.getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(listener); scan(activity);
    }
    @Override public void onActivityPaused(Activity activity) {
        ViewTreeObserver.OnGlobalLayoutListener listener = listeners.remove(activity);
        if (listener != null) activity.getWindow().getDecorView().getViewTreeObserver().removeOnGlobalLayoutListener(listener);
        Runnable task = pending.remove(activity); if (task != null) handler.removeCallbacks(task);
    }
    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
    @Override public void onActivityDestroyed(Activity activity) { onActivityPaused(activity); }
}
