package dev.sparklecat.xpatch;

import java.util.regex.*;

/** Semantic version ordering for release tags; numeric versions never use string ordering. */
// Adapted from sparklecatdev/plural-star-mods (AGPL-3.0).
public final class ReleaseVersion implements Comparable<ReleaseVersion> {
    private static final Pattern FORMAT = Pattern.compile("^v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*))?$");
    public final String name;
    private final long[] numbers;
    private final String[] preview;
    private ReleaseVersion(String name, long[] numbers, String[] preview) { this.name = name; this.numbers = numbers; this.preview = preview; }
    public static ReleaseVersion parse(String value) {
        if (value == null || value.length() > 96) return null;
        Matcher m = FORMAT.matcher(value);
        if (!m.matches()) return null;
        try {
            String[] pre = m.group(4) == null ? new String[0] : m.group(4).split("\\.");
            for (String part : pre) if (part.matches("[0-9]+") && part.length() > 1 && part.startsWith("0")) return null;
            return new ReleaseVersion(value.startsWith("v") ? value.substring(1) : value,
                new long[]{Long.parseLong(m.group(1)), Long.parseLong(m.group(2)), Long.parseLong(m.group(3))}, pre);
        } catch (NumberFormatException e) { return null; }
    }
    public boolean isPreview() { return preview.length > 0; }
    @Override public int compareTo(ReleaseVersion other) {
        for (int i = 0; i < 3; i++) { int n = Long.compare(numbers[i], other.numbers[i]); if (n != 0) return n; }
        if (preview.length == 0 || other.preview.length == 0) return Integer.compare(other.preview.length == 0 ? 0 : 1, preview.length == 0 ? 0 : 1);
        for (int i = 0; i < Math.min(preview.length, other.preview.length); i++) {
            String a = preview[i], b = other.preview[i];
            boolean an = a.matches("[0-9]+"), bn = b.matches("[0-9]+");
            int n;
            if (an && bn) n = a.length() == b.length() ? a.compareTo(b) : Integer.compare(a.length(), b.length());
            else if (an != bn) n = an ? -1 : 1;
            else n = a.compareTo(b);
            if (n != 0) return n;
        }
        return Integer.compare(preview.length, other.preview.length);
    }
}
