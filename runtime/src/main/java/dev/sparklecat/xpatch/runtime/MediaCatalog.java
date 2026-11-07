/*
 * Media traversal and original-image handling adapted from Piko ExtMediaEntities.
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 * GPL-3.0; see third_party/piko/NOTICE for the preserved §7(b) terms.
 * Adaptation: discovered schema fields, explicit bitrate ranking, bounded storage,
 * URL validation and preservation of the original image format.
 */
package dev.sparklecat.xpatch.runtime;

import org.json.JSONObject;
import java.lang.reflect.Field;
import java.net.URI;
import java.util.*;

final class MediaCatalog {
    static final class Item {
        final String url, extension, quality;
        Item(String url, String extension, String quality) { this.url = url; this.extension = extension; this.quality = quality; }
        String label() {
            String path = URI.create(url).getPath();
            String name = path.substring(path.lastIndexOf('/') + 1);
            return quality + " · " + name;
        }
    }
    private final JSONObject schema;
    private final LinkedHashMap<String, Item> items = new LinkedHashMap<>();
    MediaCatalog(JSONObject schema) { this.schema = schema; }
    private Object field(Object model, String key) throws Exception {
        if (model == null) return null;
        JSONObject fields = schema.optJSONObject(model.getClass().getName());
        if (fields == null) return null;
        String name = fields.optString(key, "");
        if (name.isEmpty()) return null;
        Field field = model.getClass().getDeclaredField(name); field.setAccessible(true);
        return field.get(model);
    }
    static boolean trusted(String url, String host) {
        try {
            URI uri = new URI(url);
            return "https".equalsIgnoreCase(uri.getScheme()) && host.equalsIgnoreCase(uri.getHost()) && uri.getRawUserInfo() == null && uri.getPort() == -1 && uri.getRawFragment() == null;
        } catch (Exception ignored) { return false; }
    }
    static Item photo(String source) {
        if (!trusted(source, "pbs.twimg.com")) return null;
        URI uri = URI.create(source);
        String path = uri.getPath();
        if (!path.startsWith("/media/")) return null;
        String format = "jpg";
        String query = uri.getRawQuery();
        if (query != null) for (String part : query.split("&")) if (part.matches("format=(jpg|jpeg|png|webp)")) format = part.substring(7);
        if (path.matches(".*\\.(jpg|jpeg|png|webp)(:[a-z0-9]+)?")) {
            format = path.substring(path.lastIndexOf('.') + 1).split(":")[0];
            path = path.substring(0, path.lastIndexOf('.'));
        }
        return new Item("https://pbs.twimg.com" + path + "?format=" + format + "&name=orig", format, "Photo · original");
    }
    synchronized void capture(Object model) {
        try {
            Object info = field(model, "video_info");
            if (info == null && field(model, "variants") instanceof Iterable<?>) info = model;
            Item item = null;
            if (info != null) {
                Object variants = field(info, "variants");
                long best = -1;
                if (variants instanceof Iterable<?>) for (Object variant : (Iterable<?>) variants) {
                    Object url = field(variant, "url"), mime = field(variant, "content_type"), bitrate = field(variant, "bitrate");
                    if (bitrate == null) bitrate = field(variant, "bit_rate");
                    long rate = bitrate instanceof Number ? ((Number) bitrate).longValue() : 0;
                    if ("video/mp4".equals(mime) && url instanceof String && trusted((String) url, "video.twimg.com") && URI.create((String) url).getPath().endsWith(".mp4") && rate > best) {
                        best = rate; item = new Item((String) url, "mp4", "Video · " + (rate > 0 ? rate / 1000 + " kbps" : "MP4"));
                    }
                }
            } else {
                Object source = field(model, "original_img_url");
                if (source == null) source = field(model, "media_url_https");
                Object type = field(model, "type");
                if (source instanceof String && !"video".equals(type) && !"animated_gif".equals(type)) item = photo((String) source);
            }
            if (item == null) return;
            // Re-decoding a cached item must not displace newer media.
            if (!items.containsKey(item.url)) items.put(item.url, item);
            while (items.size() > 120) items.remove(items.keySet().iterator().next());
        } catch (Exception ignored) { /* Unknown models leave X untouched. */ }
    }
    synchronized List<Item> snapshot() { List<Item> result = new ArrayList<>(items.values()); Collections.reverse(result); return result; }
    synchronized void clear() { items.clear(); }
}
