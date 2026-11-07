package com.twitter.android;
import kotlinx.serialization.descriptors.SerialDescriptor;
public final class MediaSerializer {
    static final TimelineSerializer.Descriptor descriptor;
    static { descriptor = new TimelineSerializer.Descriptor(); descriptor.j("media_url_https", false); descriptor.j("type", false); descriptor.j("video_info", true); }
    public static final class Media { public String a, b; public Info c; }
    public static final class Info { public java.util.List<Variant> a; }
    public static final class Variant { public String a, b; public Integer c; Variant(String url, String mime, int bitrate) { a = url; b = mime; c = bitrate; } }
    public Object deserialize(kotlinx.serialization.encoding.Decoder decoder) { Media photo = new Media(); photo.a = "https://pbs.twimg.com/media/fixture-photo.png:small"; photo.b = "photo"; return photo; }
    public void serialize(TimelineSerializer.Encoder encoder, Media media) { encoder.string(descriptor, 0, media.a); encoder.string(descriptor, 1, media.b); encoder.object(descriptor, 2, media.c); }
    public static final class VideoSerializer {
        public Object deserialize(kotlinx.serialization.encoding.Decoder decoder) { Media video = new Media(); video.b = "video"; video.c = new Info(); video.c.a = java.util.Arrays.asList(new Variant("https://video.twimg.com/fixture-low.mp4", "video/mp4", 100000), new Variant("https://video.twimg.com/fixture-high.mp4", "video/mp4", 4000000)); return video; }
        static final TimelineSerializer.Descriptor descriptor;
        static { descriptor = new TimelineSerializer.Descriptor(); descriptor.j("media_url_https", false); descriptor.j("type", false); descriptor.j("video_info", true); }
        public void serialize(TimelineSerializer.Encoder encoder, Media media) { encoder.string(descriptor, 0, media.a); encoder.string(descriptor, 1, media.b); encoder.object(descriptor, 2, media.c); }
    }
    public static final class InfoSerializer {
        static final TimelineSerializer.Descriptor descriptor;
        static { descriptor = new TimelineSerializer.Descriptor(); descriptor.j("aspect_ratio", true); descriptor.j("variants", false); }
        public void serialize(TimelineSerializer.Encoder encoder, Info media) { encoder.object(descriptor, 1, media.a); }
    }
    public static final class VariantSerializer {
        static final TimelineSerializer.Descriptor descriptor;
        static { descriptor = new TimelineSerializer.Descriptor(); descriptor.j("url", false); descriptor.j("content_type", false); descriptor.j("bitrate", false); }
        public void serialize(TimelineSerializer.Encoder encoder, Variant media) { encoder.string(descriptor, 0, media.a); encoder.string(descriptor, 1, media.b); encoder.object(descriptor, 2, media.c); }
    }
}
