package dev.sparklecat.xpatch.runtime;

import android.app.*;
import android.content.*;
import android.os.*;
import android.widget.*;
import dev.sparklecat.xpatch.*;
import org.json.JSONObject;
import java.io.*;
import java.lang.ref.WeakReference;

/** Opt-in release checks; installation and rebuilding always happen in XPatch. */
final class UpdateNotices {
    private final Handler main = new Handler(Looper.getMainLooper());
    private WeakReference<Activity> foreground = new WeakReference<>(null);
    private boolean checked, checking, noticeShown, dialogVisible;
    private UpdatePolicy.Release available;
    private SharedPreferences prefs(Activity activity) { return activity.getSharedPreferences("xpatch.updates.v1", 0); }
    void resume(Activity activity, JSONObject schema) {
        foreground = new WeakReference<>(activity);
        main.postDelayed(() -> {
            if (!ready(activity)) return;
            String version = schema.optString("patchVersion", "");
            SharedPreferences p = prefs(activity);
            String seen = p.getString("notesSeen", "");
            if (seen.isEmpty()) p.edit().putString("notesSeen", version).apply();
            else if (!seen.equals(version) && !dialogVisible) { notes(activity, schema); return; }
            if (available != null && !noticeShown && !dialogVisible) showUpdate(activity);
            else if (!checked && p.getBoolean("startup", false)) check(activity, schema, true);
        }, 1200);
    }
    void pause(Activity activity) { if (foreground.get() == activity) foreground.clear(); }
    private boolean ready(Activity activity) { return foreground.get() == activity && !activity.isFinishing() && !activity.isDestroyed() && activity.getWindow().getDecorView().hasWindowFocus(); }
    void menu(Activity activity, JSONObject schema) {
        LinearLayout form = new LinearLayout(activity); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(32, 16, 32, 16);
        TextView detail = new TextView(activity); detail.setText("Patches " + schema.optString("patchVersion", "unknown") + " · X " + schema.optString("inputVersion") + "\nNew engines ship with XPatch. Rebuild your saved stock APK in the patcher, then install it. Your live X settings are retained."); form.addView(detail);
        Switch startup = new Switch(activity); startup.setText("Check mod updates on launch"); startup.setChecked(prefs(activity).getBoolean("startup", false)); form.addView(startup);
        startup.setOnCheckedChangeListener((v, value) -> prefs(activity).edit().putBoolean("startup", value).apply());
        Switch preview = new Switch(activity); preview.setText("Include preview releases"); preview.setChecked(prefs(activity).getBoolean("previews", true)); form.addView(preview);
        preview.setOnCheckedChangeListener((v, value) -> { prefs(activity).edit().putBoolean("previews", value).apply(); available = null; checked = false; noticeShown = false; });
        new AlertDialog.Builder(activity).setTitle("XPatch updates").setView(form)
            .setPositiveButton("Check updates", (d, w) -> check(activity, schema, false))
            .setNeutralButton("What's new", (d, w) -> notes(activity, schema)).setNegativeButton("Close", null).show();
    }
    private void check(Activity activity, JSONObject schema, boolean quiet) {
        if (checking) { if (!quiet) Toast.makeText(activity, "Update check is running.", Toast.LENGTH_SHORT).show(); return; }
        checking = true; checked = true;
        String version = schema.optString("patchVersion", ""); boolean previews = prefs(activity).getBoolean("previews", true);
        if (!quiet) Toast.makeText(activity, "Checking XPatch releases…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            UpdatePolicy.Release release = null; String failure = null;
            try { release = UpdatePolicy.select(ReleaseFeed.read(), version, previews); }
            catch (Exception e) { failure = e.getMessage() == null ? "Could not check updates. Try again later." : e.getMessage(); }
            final UpdatePolicy.Release result = release; final String error = failure;
            main.post(() -> {
                checking = false; available = result;
                Activity current = foreground.get();
                if (current == null || !ready(current)) return;
                if (result != null && !dialogVisible) showUpdate(current);
                else if (!quiet) Toast.makeText(current, error == null ? "No newer patches in this channel." : error, Toast.LENGTH_LONG).show();
            });
        }, "xpatch-release-check").start();
    }
    private void showUpdate(Activity activity) {
        UpdatePolicy.Release release = available; if (release == null) return;
        noticeShown = true; dialogVisible = true;
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("XPatch " + release.version.name + " available")
            .setMessage(release.notes + "\n\nOpen XPatch to verify and install the patcher update. Then rebuild the saved stock APK to update X’s patches.")
            .setPositiveButton("Open patcher", (d, w) -> {
                try { activity.startActivity(new Intent("dev.sparklecat.xpatch.CHECK_UPDATES").setComponent(new ComponentName("dev.sparklecat.xpatch", "dev.sparklecat.xpatch.MainActivity"))); }
                catch (ActivityNotFoundException | SecurityException e) { Toast.makeText(activity, "Install or open XPatch, then check for updates.", Toast.LENGTH_LONG).show(); }
            }).setNegativeButton("Later", null).create();
        dialog.setOnDismissListener(d -> dialogVisible = false); dialog.show();
    }
    private void notes(Activity activity, JSONObject schema) {
        String text;
        try (InputStream in = activity.getAssets().open("xpatch/release-notes.txt"); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] block = new byte[4096]; int n;
            while ((n = in.read(block)) != -1) { if (bytes.size() + n > 16384) throw new IOException(); bytes.write(block, 0, n); }
            text = bytes.toString("UTF-8");
        } catch (Exception e) { text = "Release notes unavailable. Open XPatch for release details."; }
        dialogVisible = true;
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle("What's new · XPatch " + schema.optString("patchVersion"))
            .setMessage(text).setPositiveButton("Done", (d, w) -> prefs(activity).edit().putString("notesSeen", schema.optString("patchVersion")).apply()).create();
        dialog.setOnDismissListener(d -> dialogVisible = false); dialog.show();
    }
}
