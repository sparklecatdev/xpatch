package com.twitter.android;

public final class a {
    public b parse(String value) {
        String[] schema = {"entryId", "sortIndex", "content"};
        if (value == null || value.equals(schema[0])) return null;
        b result = new b(); result.q = value; return result;
    }
}
