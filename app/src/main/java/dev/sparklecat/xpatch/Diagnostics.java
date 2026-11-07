package dev.sparklecat.xpatch;

import android.content.Context;
import android.os.Build;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Bounded local operation history; no logs, paths, accounts or keys. */
final class Diagnostics {
    static synchronized void record(Context context, String message) {
        try {
            JSONArray old = new JSONArray(context.getSharedPreferences("xpatch", 0).getString("operations", "[]")), next = new JSONArray();
            for (int i = Math.max(0, old.length() - 19); i < old.length(); i++) next.put(old.get(i));
            // Exception text can include provider paths; retain only the operation's first line.
            String first = message.split("\n", 2)[0].replaceAll("(?:/[^\\s]+)+", "[path]");
            next.put(new JSONObject().put("time", System.currentTimeMillis()).put("result", first.substring(0, Math.min(first.length(), 300))));
            context.getSharedPreferences("xpatch", 0).edit().putString("operations", next.toString()).apply();
        } catch (JSONException ignored) { }
    }
    static String export(Context context, Pipeline.Input input) throws Exception {
        StringBuilder out = new StringBuilder("XPatch diagnostics\nVersion: " + BuildConfig.VERSION_NAME + "\nEngine: " + Pipeline.ENGINE + "\nAndroid: " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")\nDevice: " + Build.MANUFACTURER + " " + Build.MODEL + "\n");
        if (input != null) out.append("\nSelected X: ").append(input.version).append("\nInput SHA-256: ").append(input.sha).append('\n').append(input.plan.summary()).append('\n');
        File report = Pipeline.report(context);
        if (report.isFile()) try (InputStream in = new FileInputStream(report); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            ApkPatcher.copy(in, bytes, 64 * 1024); out.append("\nLast completed build\n").append(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        }
        out.append("\nRecent operations\n").append(new JSONArray(context.getSharedPreferences("xpatch", 0).getString("operations", "[]")).toString(2));
        return out.toString();
    }
}
