package dev.sparklecat.xpatch.runtime;

import org.json.JSONObject;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class MediaCatalogTest {
    static class Photo { String renamed = "https://pbs.twimg.com/media/example.png:small"; }
    static class Video { Info info = new Info(); }
    static class Info { List<Variant> choices = Arrays.asList(new Variant(100, "https://video.twimg.com/low.mp4", "video/mp4"), new Variant(900, "https://video.twimg.com/high.mp4?tag=12", "video/mp4"), new Variant(9999, "https://video.twimg.com/playlist.m3u8", "application/x-mpegURL"), new Variant(99999, "https://evil.example/stolen.mp4", "video/mp4")); }
    static class Variant { int rate; String url, mime; Variant(int rate, String url, String mime) { this.rate = rate; this.url = url; this.mime = mime; } }
    JSONObject schema() throws Exception {
        return new JSONObject().put(Photo.class.getName(), new JSONObject().put("original_img_url", "renamed"))
            .put(Video.class.getName(), new JSONObject().put("video_info", "info"))
            .put(Info.class.getName(), new JSONObject().put("variants", "choices"))
            .put(Variant.class.getName(), new JSONObject().put("bit_rate", "rate").put("url", "url").put("content_type", "mime"));
    }
    @Test public void requestsOriginalAndPreservesFormat() {
        assertEquals("https://pbs.twimg.com/media/example?format=png&name=orig", MediaCatalog.photo("https://pbs.twimg.com/media/example.png:small").url);
        assertEquals("https://pbs.twimg.com/media/example?format=webp&name=orig", MediaCatalog.photo("https://pbs.twimg.com/media/example?format=webp&name=small").url);
    }
    @Test public void rejectsUntrustedAndNonMediaSources() {
        for (String url : Arrays.asList("http://pbs.twimg.com/media/x.jpg", "https://pbs.twimg.com.evil.test/media/x.jpg", "https://user@pbs.twimg.com/media/x.jpg", "https://pbs.twimg.com:443/media/x.jpg", "https://pbs.twimg.com/media/x.jpg#frag", "https://pbs.twimg.com/profile_images/x.jpg", "bad url")) assertNull(url, MediaCatalog.photo(url));
    }
    @Test public void selectsHighestBitrateMp4AcrossRenamedSchemas() throws Exception {
        MediaCatalog catalog = new MediaCatalog(schema()); catalog.capture(new Video());
        assertEquals(1, catalog.snapshot().size()); assertEquals("https://video.twimg.com/high.mp4?tag=12", catalog.snapshot().get(0).url);
        catalog.clear(); catalog.capture(new Info()); assertEquals(1, catalog.snapshot().size());
    }
    @Test public void deduplicatesBoundsAndClearsMemory() throws Exception {
        MediaCatalog catalog = new MediaCatalog(schema());
        for (int i = 0; i < 150; i++) { Photo photo = new Photo(); photo.renamed = "https://pbs.twimg.com/media/photo" + i + ".jpg"; catalog.capture(photo); }
        assertEquals(120, catalog.snapshot().size()); assertTrue(catalog.snapshot().get(0).url.contains("photo149?"));
        Photo repeated = new Photo(); repeated.renamed = "https://pbs.twimg.com/media/photo100.jpg"; catalog.capture(repeated);
        assertTrue(catalog.snapshot().get(0).url.contains("photo149?")); catalog.capture(new Object()); assertEquals(120, catalog.snapshot().size());
        catalog.clear(); assertTrue(catalog.snapshot().isEmpty());
    }
    @Test public void neverDownloadsVideoThumbnailWhenVariantsAreMissing() throws Exception {
        MediaCatalog catalog = new MediaCatalog(schema()); Video video = new Video(); video.info.choices = Collections.emptyList(); catalog.capture(video); assertTrue(catalog.snapshot().isEmpty());
    }
}
