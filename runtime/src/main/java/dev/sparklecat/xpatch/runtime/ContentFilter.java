package dev.sparklecat.xpatch.runtime;

import org.json.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Schema-bound filtering; no traversal into unrelated app objects. */
final class ContentFilter {
    private final JSONObject schema;
    private final Map<String, Field> fields = new ConcurrentHashMap<>();
    ContentFilter(JSONObject schema) { this.schema = schema; }
    private JSONObject model(Object value) {
        JSONObject models = schema.optJSONObject("modernModels");
        return value == null || models == null ? null : models.optJSONObject(value.getClass().getName());
    }
    private Object read(Object value, String name) throws Exception {
        if (name.isEmpty()) return null;
        String key = value.getClass().getName() + "#" + name;
        Field field = fields.get(key);
        if (field == null) { field = value.getClass().getDeclaredField(name); field.setAccessible(true); fields.put(key, field); }
        return field.get(value);
    }
    private Object property(Object value, JSONObject model, String name) throws Exception { return model == null ? null : read(value, model.optString(name, "")); }
    private String mapped(Object value, String group) {
        JSONObject map = schema.optJSONObject(group); return map == null ? "" : map.optString(value.getClass().getName(), "");
    }
    Object filter(Object value, JSONObject options) { return filter(value, options, 0); }
    private Object filter(Object value, JSONObject options, int depth) {
        if (value == null || depth > 12) return value;
        try {
            if (value instanceof List<?>) return list((List<?>) value, options, depth);
            JSONObject m = model(value);
            if (options.optBoolean("ads") && read(value, mapped(value, "promotedFields")) != null) return null;
            Object id = read(value, mapped(value, "entryFields"));
            if (id instanceof String && removeId((String) id, options)) return null;
            if (m == null) return value;
            String kind = m.optString("$kind");
            if (options.optBoolean("ads")) {
                if (kind.equals("ad") || property(value, m, "promotedMetadata") != null || property(value, m, "promotedDescription") != null) return null;
                Object client = property(value, m, "clientEventInfo");
                Object component = property(client, model(client), "component");
                if (component instanceof String && ((String) component).toLowerCase(Locale.ROOT).contains("promoted")) return null;
            }
            if (kind.equals("module")) {
                Object children = property(value, m, "innerContent");
                if (!(children instanceof List<?>)) return value;
                List<?> before = (List<?>) children, after = (List<?>) list(before, options, depth + 1);
                if (after.isEmpty()) return null;
                if (before == after) return value;
                // Preserve headers, sorting and conversation IDs when rebuilding the immutable module.
                try {
                Object display = property(value, m, "displayType");
                JSONObject dm = model(display);
                if (dm != null && dm.optString("$kind").equals("conversation")) {
                    Object ids = property(display, dm, "allTweetIds");
                    if (ids instanceof List<?>) {
                        Set<Object> removed = new HashSet<>();
                        for (Object child : before) if (!after.contains(child)) {
                            JSONObject cm = model(child); Object post = property(child, cm, "item");
                            if (post != null) try { removed.add(post.getClass().getMethod("getId").invoke(post)); } catch (ReflectiveOperationException ignored) { }
                        }
                        List<Object> kept = new ArrayList<>((List<?>) ids); kept.removeAll(removed);
                        display = construct(display.getClass(), new Object[]{immutable(kept, display.getClass().getClassLoader())});
                    }
                }
                return construct(value.getClass(), new Object[]{immutable(after, value.getClass().getClassLoader()), property(value, m, "moduleHeader"), property(value, m, "moduleFooter"), display, property(value, m, "sortIndex"), property(value, m, "entryId"), property(value, m, "clientEventInfo")});
                }
                catch (Exception invalidModule) { return null; }
            }
            String nested = kind.equals("item") ? "item" : kind.equals("trend") ? "timelineTrend" : kind.equals("event") ? "eventSummary" : "";
            if (!nested.isEmpty()) {
                Object child = property(value, m, nested);
                if (child != null && filter(child, options, depth + 1) == null) return null;
            }
            if (kind.equals("post")) {
                String keywords = options.optString("mutedWords", "").trim();
                if (!keywords.isEmpty()) {
                    Object text = value.getClass().getMethod("getText").invoke(value);
                    if (text instanceof String) for (String word : keywords.split("\n")) if (!word.trim().isEmpty() && ((String) text).toLowerCase(Locale.ROOT).contains(word.trim().toLowerCase(Locale.ROOT))) return null;
                }
            }
        } catch (Exception ignored) { /* Unknown model variants retain their original behavior. */ }
        return value;
    }
    private Object list(List<?> before, JSONObject options, int depth) {
        ArrayList<Object> after = null;
        for (int i = 0; i < before.size(); i++) {
            Object item = before.get(i), filtered = filter(item, options, depth + 1);
            if (item != filtered && after == null) after = new ArrayList<>(before.subList(0, i));
            if (after != null && filtered != null) after.add(filtered);
        }
        return after == null ? before : after;
    }
    private static boolean removeId(String value, JSONObject options) {
        String id = value.toLowerCase(Locale.ROOT);
        if (options.optBoolean("ads") && (id.contains("promoted") || id.startsWith("ad-") || id.contains("-ad-") || id.contains("superhero") || id.startsWith("rtb-") || id.contains("-rtb-"))) return true;
        return options.optBoolean("hideWhoToFollow") && (id.startsWith("who-to-follow") || id.startsWith("who_to_follow"))
            || options.optBoolean("hideDiscoverMore") && id.startsWith("tweetdetailrelatedtweets")
            || options.optBoolean("hideTopics") && (id.startsWith("topics-to-follow") || id.startsWith("topics_to_follow"));
    }
    private static Object construct(Class<?> type, Object[] args) throws Exception {
        Constructor<?> match = null;
        for (Constructor<?> c : type.getDeclaredConstructors()) {
            Class<?>[] p = c.getParameterTypes(); if (p.length != args.length) continue;
            boolean valid = true;
            for (int i = 0; i < p.length; i++) if (args[i] != null && !(p[i].isInstance(args[i]) || p[i] == long.class && args[i] instanceof Long || p[i] == boolean.class && args[i] instanceof Boolean)) valid = false;
            if (valid) { if (match != null) throw new IllegalStateException("Ambiguous model constructor"); match = c; }
        }
        if (match == null) throw new NoSuchMethodException(); match.setAccessible(true); return match.newInstance(args);
    }
    Object immutable(List<?> list, ClassLoader loader) throws Exception {
        String converter = schema.optString("immutableConverter", ""); int split = converter.indexOf('#');
        if (split < 1) return list;
        Method method = Class.forName(converter.substring(0, split), false, loader == null ? ContentFilter.class.getClassLoader() : loader).getDeclaredMethod(converter.substring(split + 1), Iterable.class);
        method.setAccessible(true); return method.invoke(null, list);
    }
    Object timeline(Object value, JSONObject options) {
        if (!(value instanceof List<?>)) return value;
        Object filtered = filter(value, options);
        if (filtered == value) return value;
        try { return immutable((List<?>) filtered, value.getClass().getClassLoader()); }
        catch (Exception ignored) { return value; }
    }
    static void validateNavigation(String hidden, String order, String words) {
        Set<String> allowed = new HashSet<>(Arrays.asList("HOME", "EXPLORE", "GROK", "NOTIFICATIONS", "DM", "COMMUNITIES", "SPACES"));
        for (String value : Arrays.asList(hidden, order)) {
            if (value.length() > 512 || !value.matches("[A-Z_,\\s]*")) throw new IllegalArgumentException("Use uppercase tab names.");
            for (String tab : value.split("[,\\s]+")) if (!tab.isEmpty() && !allowed.contains(tab)) throw new IllegalArgumentException("Unknown navigation tab: " + tab);
        }
        if (Arrays.asList(hidden.split("[,\\s]+")).contains("HOME")) throw new IllegalArgumentException("Home must remain visible.");
        if (words.length() > 4096 || words.split("\n").length > 64) throw new IllegalArgumentException("Use at most 64 muted phrases and 4096 characters.");
    }
    static Map<?, ?> navigation(Map<?, ?> original, JSONObject options) {
        if (original == null) return null;
        Set<String> hidden = new HashSet<>(Arrays.asList(options.optString("navHidden", "").toUpperCase(Locale.ROOT).split("[,\\s]+")));
        hidden.remove("HOME");
        LinkedHashMap<Object, Object> result = new LinkedHashMap<>();
        for (String key : options.optString("navOrder", "").toUpperCase(Locale.ROOT).split("[,\\s]+")) for (Map.Entry<?, ?> e : original.entrySet()) if (name(e.getKey()).equals(key) && !hidden.contains(key)) result.put(e.getKey(), e.getValue());
        for (Map.Entry<?, ?> e : original.entrySet()) if (!hidden.contains(name(e.getKey()))) result.put(e.getKey(), e.getValue());
        return result.isEmpty() || new ArrayList<>(original.keySet()).equals(new ArrayList<>(result.keySet())) ? original : result;
    }
    private static String name(Object key) { return key instanceof Enum<?> ? ((Enum<?>) key).name() : String.valueOf(key); }
}
