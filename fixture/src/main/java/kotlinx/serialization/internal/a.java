package kotlinx.serialization.internal;
import java.util.*;
import com.twitter.android.c;
import kotlinx.serialization.*;
import kotlinx.serialization.descriptors.SerialDescriptor;
import kotlinx.serialization.encoding.Decoder;
public final class a {
    public Object deserialize(Decoder decoder) {
        ArrayList<c> values = new ArrayList<>();
        values.add(new c("promoted-modern", null)); values.add(new c("modern-organic", null));
        values.add(new c("tweet-with-marker", new Object())); values.add(new c("rtb-modern", null));
        return values;
    }
    public Iterator<?> iterator(Collection<?> values) { return values.iterator(); }
    public SerialDescriptor descriptor(KSerializer serializer) { return serializer.getDescriptor(); }
}
