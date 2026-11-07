package dev.sparklecat.xpatch;

import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.regex.*;
import org.json.*;

// Adapted from sparklecatdev/plural-star-mods (AGPL-3.0).
public final class UpdatePolicy {
    public static final String REPO = "sparklecatdev/xpatch";
    static final int RELEASE_PAGE_SIZE = 20, MAX_RELEASES = 100;
    public static final String API = "https://api.github.com/repos/" + REPO + "/releases?per_page=" + RELEASE_PAGE_SIZE;
    public static final String RELEASES = "https://github.com/" + REPO + "/releases";
    public static final long MAX_APK = 128L * 1024 * 1024;
    public static final class Release {
        public final ReleaseVersion version;
        public final String tag, filename, apkUrl, checksumsUrl, notes;
        public final long bytes;
        Release(ReleaseVersion version, String tag, String filename, String apkUrl, String checksumsUrl, long bytes, String notes) {
            this.version = version; this.tag = tag; this.filename = filename; this.apkUrl = apkUrl; this.checksumsUrl = checksumsUrl; this.bytes = bytes;
            this.notes = notes;
        }
    }
    public static Release select(String json, String installed, boolean previews) throws Exception {
        ReleaseVersion current = ReleaseVersion.parse(installed);
        if (current == null) throw new IOException("The installed version cannot be checked.");
        JSONArray list = new JSONArray(json);
        Release best = null;
        for (int i = 0; i < list.length(); i++) {
            JSONObject row = list.optJSONObject(i);
            if (row == null || row.optBoolean("draft", true)) continue;
            String tag = row.optString("tag_name", "");
            ReleaseVersion version = ReleaseVersion.parse(tag);
            if (version == null || !tag.startsWith("v") || version.compareTo(current) <= 0) continue;
            if (!previews && (row.optBoolean("prerelease", false) || version.isPreview())) continue;
            JSONArray assets = row.optJSONArray("assets");
            if (assets == null) continue;
            String filename = "xpatch.apk";
            String apk = null, sums = null;
            long size = 0;
            boolean duplicate = false;
            Set<String> seen = new HashSet<>();
            for (int j = 0; j < assets.length(); j++) {
                JSONObject asset = assets.optJSONObject(j);
                if (asset == null || !asset.optString("state", "").equals("uploaded")) continue;
                String name = asset.optString("name", "");
                if (name.equals(filename)) {
                    if (!seen.add(name)) duplicate = true;
                    apk = trustedAsset(asset.optString("browser_download_url", ""), tag, name);
                    size = asset.optLong("size", 0);
                } else if (name.equals("SHA256SUMS")) {
                    if (!seen.add(name)) duplicate = true;
                    sums = trustedAsset(asset.optString("browser_download_url", ""), tag, name);
                }
            }
            if (duplicate || apk == null || sums == null || size < 1 || size > MAX_APK) continue;
            if (best == null || version.compareTo(best.version) > 0) best = new Release(version, tag, filename, apk, sums, size, releaseNotes(row.opt("body")));
        }
        return best;
    }
    static String releaseNotes(Object body) {
        if (!(body instanceof String)) return "No release notes were provided.";
        String notes = ((String) body).substring(0, Math.min(((String) body).length(), 6000))
            .replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "").trim();
        return notes.isEmpty() ? "No release notes were provided." : notes;
    }
    static String trustedAsset(String url, String tag, String name) {
        return trustedAsset(url, REPO, tag, name);
    }
    static String trustedAsset(String url, String repo, String tag, String name) {
        try {
            URI uri = URI.create(url);
            if (!validHttps(uri) || !uri.getHost().equals("github.com") || uri.getRawQuery() != null || uri.getRawFragment() != null) return null;
            return uri.getPath().equals("/" + repo + "/releases/download/" + tag + "/" + name) ? url : null;
        } catch (RuntimeException e) { return null; }
    }
    public static boolean downloadUrlAllowed(URI uri) {
        return validHttps(uri) && Arrays.asList("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com").contains(uri.getHost());
    }
    private static boolean validHttps(URI uri) {
        return uri != null && "https".equals(uri.getScheme()) && uri.getHost() != null && uri.getUserInfo() == null && (uri.getPort() == -1 || uri.getPort() == 443);
    }
    public static String checksum(String text, String filename) throws IOException {
        String found = null;
        Pattern line = Pattern.compile("^([0-9a-fA-F]{64})[ \\t]+\\*?(.+)$");
        for (String value : text.split("\\r?\\n")) {
            Matcher m = line.matcher(value);
            if (m.matches() && m.group(2).equals(filename)) {
                if (found != null) throw new IOException("Update checksum is ambiguous.");
                found = m.group(1).toLowerCase(Locale.ROOT);
            }
        }
        if (found == null) throw new IOException("No checksum was published for this update.");
        return found;
    }
    public static void checkVersion(String candidateName, long candidateCode, String currentName, long currentCode, Release release) throws IOException {
        ReleaseVersion candidate = ReleaseVersion.parse(candidateName), current = ReleaseVersion.parse(currentName);
        if (candidate == null || current == null || candidateCode <= currentCode || candidate.compareTo(current) <= 0)
            throw new IOException("This APK is not a newer update.");
        if (release != null && candidate.compareTo(release.version) != 0) throw new IOException("The APK version does not match the release.");
    }
}
