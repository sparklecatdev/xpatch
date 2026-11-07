package dev.sparklecat.xpatch;

import android.app.*;
import android.app.job.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<View> controls = new ArrayList<>();
    private TextView status, identity;
    private Button build, install, export;
    private JSONObject profile;
    private Pipeline.Input input;
    private boolean busy;
    private static final int INK = 0xfffaf5ff, MUTED = 0xffb9aec9, ACCENT = 0xffd7b8ff;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        try { profile = Pipeline.profile(this); Profile.validate(profile); } catch (Exception e) { try { profile = Profile.defaults(); } catch (JSONException impossible) { throw new IllegalStateException(impossible); } }
        render();
    }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + .5f); }
    private GradientDrawable background(int color, int radius) { GradientDrawable d = new GradientDrawable(); d.setColor(color); d.setCornerRadius(dp(radius)); return d; }
    private TextView text(String value, int size, int color) {
        TextView v = new TextView(this); v.setText(value); v.setTextSize(size); v.setTextColor(color); v.setPadding(0, dp(5), 0, dp(5)); return v;
    }
    private LinearLayout column() { LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.VERTICAL); return v; }
    private LinearLayout card(LinearLayout parent, String number, String title) {
        LinearLayout panel = column(); panel.setPadding(dp(20), dp(16), dp(20), dp(18)); panel.setBackground(background(0xff211a2c, 22));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = dp(14); parent.addView(panel, params);
        panel.addView(text(number + "  /  " + title.toUpperCase(Locale.ROOT), 12, ACCENT)); return panel;
    }
    private Button button(LinearLayout parent, String label, Runnable action) {
        Button v = new Button(this); v.setText(label); v.setAllCaps(false); v.setTextColor(INK); v.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xff3d2e50));
        parent.addView(v, new LinearLayout.LayoutParams(-1, dp(52))); v.setOnClickListener(w -> action.run()); controls.add(v); return v;
    }
    private void render() {
        controls.clear();
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        LinearLayout page = column(); page.setPadding(dp(22), dp(18), dp(22), dp(36)); scroll.addView(page); setContentView(scroll);
        if (Build.VERSION.SDK_INT >= 30) {
            page.setOnApplyWindowInsetsListener((v, insets) -> { android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars()); page.setPadding(dp(22) + bars.left, dp(18) + bars.top, dp(22) + bars.right, dp(36) + bars.bottom); return insets; });
            getWindow().setDecorFitsSystemWindows(false);
        }
        page.addView(text("SPARKLECAT'S ANDROID MODS", 11, 0xffefd597));
        TextView title = text("X, your way.", 39, INK); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD); page.addView(title);
        page.addView(text("Independent patches. Built on your phone.", 16, MUTED));
        TextView badge = text("XPATCH  /  EARLY PREVIEW", 11, ACCENT); badge.setPadding(0, dp(12), 0, dp(20)); page.addView(badge);
        LinearLayout source = card(page, "01", "Choose your APK");
        source.addView(text("Start with stock X", 22, INK));
        identity = text(input == null ? "Choose an unmodified APK, XAPK, APKM or APKS set. Each version is checked before patching." : "X " + input.version + "\n" + input.plan.summary(), 14, MUTED); source.addView(identity);
        button(source, "Choose APK or bundle", () -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), 10));
        button(source, "Use installed X", this::installed);
        LinearLayout mods = card(page, "02", "Make it yours");
        mods.addView(text("Less noise. More control.", 22, INK));
        Switch ads = new Switch(this); ads.setText("Remove promoted timeline entries"); ads.setTextColor(INK); ads.setChecked(profile.optBoolean("ads")); mods.addView(ads); controls.add(ads);
        ads.setOnCheckedChangeListener((v, checked) -> { try { profile.put("ads", checked); saveProfile(); } catch (JSONException ignored) { } });
        mods.addView(text("Ads require a matching timeline schema. Other ad formats are not covered yet.", 12, MUTED));
        for (int i = 0; i < Profile.BAR_KEYS.length; i++) {
            final String key = Profile.BAR_KEYS[i]; button(mods, Profile.BAR_LABELS[i] + "  ›", () -> editBar(key));
        }
        button(mods, "Hide custom resource names", this::editHidden);
        mods.addView(text("Tap the XP button inside patched X for live controls and a view inspector. Compose-only elements may have no resource names.", 12, MUTED));
        LinearLayout patch = card(page, "03", "Build & install");
        build = button(patch, "Build patched APK", this::build); build.setTextColor(0xff21182f); build.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        install = button(patch, "Install patched X", this::install);
        export = button(patch, "Save patched APK / set", () -> startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream").putExtra(Intent.EXTRA_TITLE, Pipeline.output(this).getName()), 11));
        patch.addView(text("Your device keeps the signing key. Keep XPatch installed to sign future updates with the same key. A stock X installation has a different signature.", 12, MUTED));
        LinearLayout updates = card(page, "04", "New versions");
        button(updates, "Watch an APK folder", () -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 12));
        button(updates, "Stop folder watch", () -> { ((JobScheduler) getSystemService(JOB_SCHEDULER_SERVICE)).cancel(FolderWatch.JOB); getSharedPreferences("xpatch", 0).edit().remove("folder").apply(); status.setText("Folder watch stopped."); });
        updates.addView(text("Checks your selected folder about every 15 minutes. New APKs and bundles use your saved profile; unmatched patches stop the build. Installation stays manual.", 12, MUTED));
        status = text(getSharedPreferences("xpatch", 0).getString("status", "Ready. Select an APK to inspect its compatibility."), 13, MUTED); status.setTextIsSelectable(true); page.addView(status);
        page.addView(text("Offline · No root · No account access", 12, ACCENT));
        availability();
    }
    private void availability() {
        for (View view : controls) view.setEnabled(!busy);
        build.setEnabled(!busy && input != null);
        boolean output = Pipeline.output(this).isFile(); install.setEnabled(!busy && output); export.setEnabled(!busy && output);
    }
    private void run(String message, Work task) {
        if (busy) return; busy = true; availability(); status.setText(message);
        worker.execute(() -> {
            try { synchronized (Pipeline.LOCK) { task.run(); } }
            catch (Exception e) { showStatus("Could not finish: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage())); }
            catch (OutOfMemoryError e) { showStatus("Not enough memory to rebuild this APK. Close other apps and try again on a device with more memory."); }
            finally { runOnUiThread(() -> { if (!isDestroyed()) { busy = false; availability(); } }); }
        });
    }
    interface Work { void run() throws Exception; }
    private void showStatus(String value) { runOnUiThread(() -> { if (!isDestroyed()) status.setText(value); }); }
    private void importApk(InputStream stream) throws Exception {
        File temp = new File(getCacheDir(), "import.apk"), source = new File(getFilesDir(), "source.apk");
        try {
            try (InputStream in = stream; OutputStream out = new FileOutputStream(temp)) { if (in == null) throw new IOException("File provider returned no data."); ApkPatcher.copy(in, out, ApkPatcher.MAX_APK); }
            Pipeline.Input checked = Pipeline.inspect(this, temp);
            if (!temp.renameTo(source)) { checked.close(); throw new IOException("Could not retain the selected APK."); }
            if (input != null) input.close();
            input = checked;
            runOnUiThread(() -> identity.setText("X " + checked.version + "\n" + checked.plan.summary()));
            showStatus("APK signature verified.\n" + checked.plan.summary() + "\nSHA-256: " + checked.sha + "\n\nStructural matches do not guarantee runtime compatibility.");
        } finally { temp.delete(); }
    }
    private void installed() {
        run("Reading installed X…", () -> {
            ApplicationInfo app = getPackageManager().getApplicationInfo("com.twitter.android", 0);
            if (app.splitSourceDirs == null || app.splitSourceDirs.length == 0) { importApk(new FileInputStream(app.sourceDir)); return; }
            File set = new File(getCacheDir(), "installed.apks");
            try {
                List<File> parts = new ArrayList<>(); parts.add(new File(app.sourceDir));
                for (String path : app.splitSourceDirs) parts.add(new File(path));
                ApkSet.archive(parts, set); importApk(new FileInputStream(set));
            } finally { set.delete(); }
        });
    }
    private void build() {
        final JSONObject selected;
        try { selected = new JSONObject(profile.toString()); } catch (JSONException e) { showStatus("Invalid profile"); return; }
        run("Preparing patch job…", () -> showStatus(Pipeline.build(this, new File(getFilesDir(), "source.apk"), input, selected, this::showStatus)));
    }
    private void install() {
        if (!getPackageManager().canRequestPackageInstalls()) { startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()))); return; }
        run("Opening Android installer…", () -> Installer.install(this));
    }
    private void saveProfile() { getSharedPreferences("xpatch", 0).edit().putString("profile", profile.toString()).apply(); }
    private EditText edit(LinearLayout parent, String label, String value) {
        parent.addView(text(label, 12, MUTED)); EditText v = new EditText(this); v.setText(value); v.setTextColor(INK); v.setSingleLine(true); parent.addView(v); return v;
    }
    private void editBar(String key) {
        JSONObject bar = profile.optJSONObject(key); LinearLayout form = column(); form.setPadding(dp(22), dp(8), dp(22), dp(16));
        Switch hidden = new Switch(this); hidden.setText("Hide bar"); hidden.setTextColor(INK); hidden.setChecked(bar.optBoolean("hide")); form.addView(hidden);
        EditText ids = edit(form, "Resource names (comma separated)", bar.optString("ids"));
        EditText scale = edit(form, "Visual scale (50–150%)", "" + bar.optInt("scale", 100)); scale.setInputType(2);
        EditText color = edit(form, "Background (#RRGGBB, empty for original)", bar.optString("color"));
        EditText order = edit(form, "Child resource names in order (LinearLayout only)", bar.optString("order"));
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(bar.optString("label", key)).setView(scroll).setPositiveButton("Save", null).setNegativeButton("Cancel", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(-1).setOnClickListener(v -> {
            try {
                JSONObject next = new JSONObject(profile.toString());
                next.getJSONObject(key).put("hide", hidden.isChecked()).put("ids", ids.getText().toString().trim()).put("scale", Integer.parseInt(scale.getText().toString().trim())).put("color", color.getText().toString().trim()).put("order", order.getText().toString().trim());
                Profile.validate(next); profile = next; saveProfile(); dialog.dismiss();
            } catch (Exception e) { Toast.makeText(this, "Check resource names, scale and hex color.", Toast.LENGTH_LONG).show(); }
        })); dialog.show();
    }
    private void editHidden() {
        LinearLayout form = column(); form.setPadding(dp(22), 0, dp(22), 0);
        EditText field = edit(form, "Resource names separated by commas", profile.optString("hiddenIds"));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Hide individual views").setView(form).setPositiveButton("Save", null).setNegativeButton("Cancel", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(-1).setOnClickListener(v -> {
            try { JSONObject next = new JSONObject(profile.toString()).put("hiddenIds", field.getText().toString().trim()); Profile.validate(next); profile = next; saveProfile(); dialog.dismiss(); }
            catch (Exception e) { field.setError(e.getMessage()); }
        })); dialog.show();
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (request == 10) run("Verifying APK and matching bytecode…", () -> importApk(getContentResolver().openInputStream(uri)));
        if (request == 11) run("Saving APK…", () -> {
            try (InputStream in = new FileInputStream(Pipeline.output(this)); OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new IOException("Destination is unavailable."); ApkPatcher.copy(in, out, ApkPatcher.MAX_APK);
            } showStatus("APK saved.");
        });
        if (request == 12) {
            try {
                if ((data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) throw new IOException("Folder did not grant read access.");
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                getSharedPreferences("xpatch", 0).edit().putString("folder", uri.toString()).apply();
                JobInfo job = new JobInfo.Builder(FolderWatch.JOB, new ComponentName(this, FolderWatch.class)).setPeriodic(15 * 60 * 1000L).setPersisted(true).build();
                if (((JobScheduler) getSystemService(JOB_SCHEDULER_SERVICE)).schedule(job) != JobScheduler.RESULT_SUCCESS) throw new IOException("Android could not schedule folder checks.");
                status.setText("Folder watch enabled. Android schedules checks about every 15 minutes.");
            } catch (Exception e) { showStatus("Could not watch folder: " + e.getMessage()); }
        }
    }
    @Override protected void onResume() { super.onResume(); if (status != null && !busy) { availability(); String saved = getSharedPreferences("xpatch", 0).getString("status", ""); if (!saved.isEmpty()) status.setText(saved); } }
    @Override protected void onDestroy() { worker.shutdown(); super.onDestroy(); }
}
