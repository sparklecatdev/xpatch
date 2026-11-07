package com.twitter.android;
import kotlinx.serialization.descriptors.SerialDescriptor;
public final class TimelineSerializer {
    static final Descriptor descriptor;
    static { descriptor = new Descriptor(); descriptor.j("other", false); descriptor.j("entryId", false); descriptor.j("sortIndex", false); descriptor.j("promotedMetadata", true); }
    public void serialize(Encoder encoder, c model) { encoder.string(descriptor, 0, model.y); encoder.string(descriptor, 1, model.x); encoder.object(descriptor, 3, model.z); }
    public static class Descriptor implements SerialDescriptor { public void j(String key, boolean optional) { android.util.Log.d("fixture", key + optional); } }
    public interface Encoder { void string(SerialDescriptor descriptor, int index, String value); void object(SerialDescriptor descriptor, int index, Object value); }
}
