package dev.sparklecat.xpatch;

import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import static org.junit.Assert.*;

public class ProfileTest {
    @Test public void defaultsAreValid() throws Exception { Profile.validate(Profile.defaults()); }
    @Test public void rejectsMalformedControls() throws Exception {
        for (Object invalid : new Object[]{49, 151, 100.5, "100"}) {
            JSONObject profile = Profile.defaults(); profile.getJSONObject("navigation").put("scale", invalid);
            try { Profile.validate(profile); fail("Accepted " + invalid); } catch (IOException expected) { }
        }
        for (String invalid : new String[]{"@id/navigation", "a/../b", "navigation;commands"}) {
            try { Profile.validateIds(invalid); fail(invalid); } catch (IOException expected) { }
        }
        JSONObject profile = Profile.defaults(); profile.getJSONObject("toolbar").put("color", "red");
        try { Profile.validate(profile); fail(); } catch (IOException expected) { }
    }
}
