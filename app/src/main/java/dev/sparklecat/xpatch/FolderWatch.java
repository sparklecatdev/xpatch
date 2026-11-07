package dev.sparklecat.xpatch;

import android.app.job.*;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import org.json.JSONObject;
import java.io.*;
import java.util.*;

public final class FolderWatch extends JobService {
    static final int JOB = 1842;
    private volatile boolean stopped;
    @Override public boolean onStartJob(JobParameters params) {
        stopped = false;
        new Thread(() -> {
            try { synchronized (Pipeline.LOCK) { scan(); } }
            catch (Exception e) { getSharedPreferences("xpatch", 0).edit().putString("status", "Folder watch: " + e.getMessage()).apply(); }
            catch (OutOfMemoryError e) { getSharedPreferences("xpatch", 0).edit().putString("status", "Folder watch: not enough memory. Open XPatch and try a foreground build.").apply(); }
            finally { jobFinished(params, false); }
        }, "xpatch-folder").start();
        return true;
    }
    private void scan() throws Exception {
        SharedPreferences prefs = getSharedPreferences("xpatch", 0);
        String folder = prefs.getString("folder", ""); if (folder.isEmpty()) return;
        Uri tree = Uri.parse(folder);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        Set<String> seen = new LinkedHashSet<>(prefs.getStringSet("seen.engine" + Pipeline.ENGINE, Collections.emptySet()));
        File source = new File(getCacheDir(), "watch-input.apk");
        String latestId = null, latestName = null; long latestTime = Long.MIN_VALUE;
        try (Cursor cursor = getContentResolver().query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED}, null, null, null)) {
            if (cursor == null) throw new IOException("Folder is unavailable.");
            while (!stopped && cursor.moveToNext()) {
                String name = cursor.getString(1);
                if (name == null || !name.toLowerCase(Locale.ROOT).matches(".*\\.(apk|apkm|apks|xapk)$") || cursor.getLong(2) > ApkPatcher.MAX_APK) continue;
                long modified = cursor.getLong(3);
                if (modified > latestTime) { latestId = cursor.getString(0); latestName = name; latestTime = modified; }
            }
        }
        if (latestId == null || stopped) return;
        try {
            Uri uri = DocumentsContract.buildDocumentUriUsingTree(tree, latestId);
            try (InputStream in = getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(source)) {
                if (in == null) throw new IOException("File is unavailable."); ApkPatcher.copy(in, out, ApkPatcher.MAX_APK);
            }
            JSONObject profile = Pipeline.profile(this);
            String digest = ApkPatcher.sha256(source) + ":" + profile;
            if (seen.contains(digest) || stopped) return;
            try (Pipeline.Input input = Pipeline.inspect(this, source)) {
                if (stopped) return;
                Pipeline.build(this, source, input, profile, message -> { });
            }
            seen.add(digest);
        } catch (Exception e) {
            prefs.edit().putString("status", "Folder watch · " + latestName + "\n" + e.getMessage()).apply();
        } finally {
            source.delete();
            while (seen.size() > 100) seen.remove(seen.iterator().next());
            prefs.edit().putStringSet("seen.engine" + Pipeline.ENGINE, seen).apply();
        }
    }
    @Override public boolean onStopJob(JobParameters params) { stopped = true; return true; }
}
