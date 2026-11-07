package dev.sparklecat.xpatch;

import android.content.Context;
import android.content.pm.*;
import android.os.Build;
import android.os.SystemClock;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.net.ssl.HttpsURLConnection;
import org.json.*;

/** Public HTTPS releases only. Downloads stay private until hash, package and signer checks pass. */
// Adapted from sparklecatdev/plural-star-mods (AGPL-3.0).
public final class UpdateClient {
    private final Context context;
    private String releaseJson;
    private long releaseCheckedAt;
    interface ReleasePageReader { String read(String url, long limit) throws Exception; }
    public interface Progress { void update(String message); }
    public UpdateClient(Context context) { this.context = context.getApplicationContext(); }
    public File updateFile() { return new File(context.getFilesDir(), "self-update.apk"); }
    public UpdatePolicy.Release check(boolean previews) throws Exception {
        return UpdatePolicy.select(releases(), BuildConfig.VERSION_NAME, previews);
    }
    private synchronized String releases() throws Exception {
        if (releaseJson == null || SystemClock.elapsedRealtime() - releaseCheckedAt >= 60000) {
            String fetched = ReleaseFeed.read();
            releaseJson = fetched;
            releaseCheckedAt = SystemClock.elapsedRealtime();
        }
        return releaseJson;
    }
    static String readReleases(ReleasePageReader reader) throws Exception {
        return ReleaseFeed.read(reader::read);
    }
    public File download(UpdatePolicy.Release release, Progress progress) throws Exception {
        String hash = UpdatePolicy.checksum(readText(release.checksumsUrl, 64 * 1024, false), release.filename);
        File temporary = new File(context.getFilesDir(), "self-update.part.apk");
        try {
            progress.update("Downloading v" + release.version.name + "…");
            HttpsURLConnection connection = connect(release.apkUrl, false);
            long count = 0, last = 0;
            try (InputStream in = connection.getInputStream(); OutputStream out = new FileOutputStream(temporary)) {
                byte[] bytes = new byte[65536]; int n;
                while ((n = in.read(bytes)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Download canceled.");
                    count += n;
                    if (count > release.bytes || count > UpdatePolicy.MAX_APK) throw new IOException("Update exceeds its published size.");
                    out.write(bytes, 0, n);
                    long now = System.currentTimeMillis();
                    if (now - last >= 250) { progress.update("Downloading update · " + (count * 100 / release.bytes) + "%"); last = now; }
                }
            } finally { connection.disconnect(); }
            UpdateTrust.checkDownload(temporary, release.bytes, hash);
            progress.update("Verifying update…");
            verify(temporary, release);
            File target = updateFile();
            if (target.exists() && !target.delete()) throw new IOException("Could not replace the previous download.");
            if (!temporary.renameTo(target)) throw new IOException("Could not save the verified update.");
            return target;
        } finally { temporary.delete(); }
    }
    public String verify(File file, UpdatePolicy.Release release) throws Exception {
        if (!file.isFile() || file.length() < 1 || file.length() > UpdatePolicy.MAX_APK) throw new IOException("No complete update is downloaded.");
        PackageManager pm = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo installed = pm.getPackageInfo(context.getPackageName(), flags);
        Signature[] current = Build.VERSION.SDK_INT >= 28 ? installed.signingInfo.getApkContentsSigners() : installed.signatures;
        UpdateTrust.checkSigner(file, current == null ? null : Arrays.stream(current).map(Signature::toByteArray).toArray(byte[][]::new));
        PackageInfo candidate = pm.getPackageArchiveInfo(file.getAbsolutePath(), 0);
        UpdateTrust.checkPackage(candidate == null ? null : candidate.packageName, context.getPackageName());
        long currentCode = Build.VERSION.SDK_INT >= 28 ? installed.getLongVersionCode() : installed.versionCode;
        long candidateCode = Build.VERSION.SDK_INT >= 28 ? candidate.getLongVersionCode() : candidate.versionCode;
        UpdatePolicy.checkVersion(candidate.versionName, candidateCode, installed.versionName, currentCode, release);
        return candidate.versionName;
    }
    private String readText(String url, long limit, boolean api) throws Exception {
        HttpsURLConnection connection = connect(url, api);
        try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ApkPatcher.copy(in, out, limit);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally { connection.disconnect(); }
    }
    private static HttpsURLConnection connect(String value, boolean api) throws Exception {
        URI uri = URI.create(value);
        for (int redirects = 0; redirects <= 5; redirects++) {
            boolean allowed = api ? "https".equals(uri.getScheme()) && "api.github.com".equals(uri.getHost()) && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443)
                : UpdatePolicy.downloadUrlAllowed(uri);
            if (!allowed) throw new IOException("Download redirected outside the trusted HTTPS hosts.");
            HttpsURLConnection c = (HttpsURLConnection) uri.toURL().openConnection();
            c.setConnectTimeout(15000); c.setReadTimeout(30000); c.setInstanceFollowRedirects(false);
            c.setRequestProperty("User-Agent", "sparklecat-xpatch/" + BuildConfig.VERSION_NAME);
            c.setRequestProperty("Accept", api ? "application/vnd.github+json" : "application/octet-stream");
            if (api) c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            int status;
            try { status = c.getResponseCode(); } catch (Exception e) { c.disconnect(); throw e; }
            if (status == 200) return c;
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                String location = c.getHeaderField("Location"); c.disconnect();
                if (location == null) throw new IOException("Download redirect has no destination.");
                uri = uri.resolve(location); continue;
            }
            c.disconnect();
            if (status == 403 || status == 429) throw new IOException("GitHub is limiting requests. Try again later.");
            throw new IOException("Download server returned HTTP " + status + ". Try again later.");
        }
        throw new IOException("Too many download redirects.");
    }
}
