package dev.sparklecat.xpatch;

import java.io.*;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;
import org.json.*;

/** Bounded, unauthenticated requests to this project's public release feed. */
public final class ReleaseFeed {
    public interface Reader { String read(String url, long limit) throws Exception; }
    private ReleaseFeed() { }
    public static String read(Reader reader) throws Exception {
        JSONArray releases = new JSONArray();
        for (int page = 1; releases.length() < UpdatePolicy.MAX_RELEASES; page++) {
            JSONArray rows = new JSONArray(reader.read(UpdatePolicy.API + "&page=" + page, 2 * 1024 * 1024));
            if (rows.length() > UpdatePolicy.RELEASE_PAGE_SIZE) throw new IOException("Release page exceeds the supported size.");
            for (int i = 0; i < rows.length(); i++) releases.put(rows.getJSONObject(i));
            if (rows.length() < UpdatePolicy.RELEASE_PAGE_SIZE) break;
        }
        return releases.toString();
    }
    public static String read() throws Exception {
        return read((url, limit) -> {
            HttpsURLConnection connection = (HttpsURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(15000); connection.setReadTimeout(15000); connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "sparklecat-xpatch");
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            try {
                int status = connection.getResponseCode();
                if (status == 403 || status == 429) throw new IOException("GitHub is limiting requests. Try again later.");
                if (status != 200) throw new IOException("Release server returned HTTP " + status + ". Try again later.");
                try (InputStream in = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] block = new byte[8192]; int n;
                    while ((n = in.read(block)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Update check canceled.");
                        if (bytes.size() + n > limit) throw new IOException("Release information exceeds the supported size.");
                        bytes.write(block, 0, n);
                    }
                    return bytes.toString("UTF-8");
                }
            } finally { connection.disconnect(); }
        });
    }
}
