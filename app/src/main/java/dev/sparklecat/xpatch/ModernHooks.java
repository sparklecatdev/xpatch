package dev.sparklecat.xpatch;

import java.util.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.builder.*;
import org.jf.dexlib2.builder.instruction.*;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.iface.reference.*;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.reference.*;

/** Semantic New X matchers adapted from Piko x-lite (GPL-3.0; see NOTICE). */
final class ModernHooks {
    final Map<String, Map<String, String>> models = new TreeMap<>();
    final Map<String, Integer> lists = new TreeMap<>();
    final Map<String, int[]> navigation = new TreeMap<>();
    final Map<String, String> hidden = new TreeMap<>();
    final Map<String, String> preroll = new TreeMap<>();
    final Set<String> mediaConstructors = new HashSet<>();
    private final SemanticFields semanticFields = new SemanticFields();
    private final Map<ClassDef, Method> mediaModels = new LinkedHashMap<>(), timelineModels = new LinkedHashMap<>();
    private final Map<String, String> kinds = new HashMap<>();
    private final List<Method> converters = new ArrayList<>();
    private final List<String> successes = new ArrayList<>();
    private final Map<String, List<String>> parents = new HashMap<>();
    private final List<Method> tabs = new ArrayList<>(), spaces = new ArrayList<>();
    String converter = "";
    private static final String F1 = "Lkotlin/jvm/functions/Function1;", F0 = "Lkotlin/jvm/functions/Function0;";
    private static final String MODIFIER = "Landroidx/compose/ui/Modifier;", COMPOSER = "Landroidx/compose/runtime/Composer;";
    static Set<String> strings(Method method) {
        Set<String> result = new HashSet<>();
        if (method.getImplementation() != null) for (Instruction ins : method.getImplementation().getInstructions()) {
            if (ins instanceof ReferenceInstruction && ((ReferenceInstruction) ins).getReference() instanceof StringReference) result.add(((StringReference) ((ReferenceInstruction) ins).getReference()).getString());
        }
        return result;
    }
    static boolean direct(String type, String scope) { return type.startsWith(scope) && !type.substring(scope.length()).contains("/"); }
    static boolean object(String type) { return type.startsWith("L") && type.endsWith(";"); }
    static List<String> params(Method method) {
        List<String> out = new ArrayList<>(); for (CharSequence p : method.getParameterTypes()) out.add(p.toString()); return out;
    }
    void scan(ClassDef cls, DexEngine.Plan plan) {
        if (cls.getType().startsWith("Lkotlinx/collections/immutable/")) {
            List<String> types = new ArrayList<>(cls.getInterfaces());
            if (cls.getSuperclass() != null) types.add(cls.getSuperclass());
            parents.put(cls.getType(), types);
        }
        String kind = "";
        Set<String> all = new HashSet<>();
        for (Method method : cls.getMethods()) {
            semanticFields.index(method);
            Set<String> text = strings(method); all.addAll(text);
            if (!method.getName().equals("toString") || method.getImplementation() == null) continue;
            if (cls.getType().startsWith("Lcom/x/models/")) {
                if (text.contains("MediaContentImage(mediaId=") || text.contains("MediaContentVideo(mediaId=") || text.contains("MediaContentGif(mediaId=") || text.contains("MediaVariant(url=")) mediaModels.put(cls, method);
                if (text.contains("UrtTimelinePost(postResult=")) kind = "post";
                else if (text.contains("UrtTimelineModule(innerContent=")) kind = "module";
                else if (text.contains("UrtTimelineModuleItem(item=")) kind = "item";
                else if (text.contains("UrtTimelineRtbImageAd(advertiserAvatarImageUrl=")) kind = "ad";
                else if (text.contains("UrtTimelineTrend(timelineTrend=")) kind = "trend";
                else if (text.contains("TimelineTrend(thumbnailImageUrl=")) kind = "trendData";
                else if (text.contains("UrtTimelineEventSummary(eventSummary=")) kind = "event";
                else if (text.contains("EventSummary(title=")) kind = "eventData";
                else if (text.contains("VerticalConversation(allTweetIds=")) kind = "conversation";
                else if (text.contains("ClientEventInfo(component=")) kind = "client";
                else if (text.contains(", entryId=")) kind = "entry";
                if (!kind.isEmpty()) { timelineModels.put(cls, method); kinds.put(cls.getType(), kind); }
            }
        }
        boolean success = cls.getType().startsWith("Lcom/x/urt/") && all.contains("Success(timelineType=") && all.contains(", timelineItems=");
        for (Method method : cls.getMethods()) {
            if (method.getImplementation() == null) continue;
            List<String> p = params(method);
            if (success && method.getName().equals("<init>") && p.size() == 5 && object(p.get(0)) && object(p.get(1)) && object(p.get(2)) && p.get(3).equals("Z") && p.get(4).equals("Z")) {
                successes.add(DexEngine.key(method)); lists.put(DexEngine.key(method), 1);
            }
            if (cls.getType().startsWith("Lcom/x/") && AccessFlags.STATIC.isSet(method.getAccessFlags()) && p.equals(Collections.singletonList("Ljava/lang/Iterable;")) && method.getReturnType().startsWith("Lkotlinx/collections/immutable/")) converters.add(method);
            if (AccessFlags.STATIC.isSet(method.getAccessFlags()) && method.getReturnType().equals("V") && localCount(method) > 0) {
                if (direct(cls.getType(), "Lcom/x/home/tabbed/") && tabShape(method, p)) tabs.add(method);
                if (direct(cls.getType(), "Lcom/x/spaces/ui/home/") && spacesShape(p)) spaces.add(method);
            }
            if (cls.getType().startsWith("Lcom/x/main/") && method.getName().equals("<init>")) {
                int[] site = navigationSite(method);
                if (site != null) navigation.put(DexEngine.key(method), site);
            }
        }
    }
    void finish(DexEngine.Plan plan) {
        for (Map.Entry<ClassDef, Method> entry : timelineModels.entrySet()) {
            ClassDef cls = entry.getKey(); String kind = kinds.get(cls.getType());
            Map<String, String> semantic = semanticFields.fields(cls, entry.getValue());
            if (kind.equals("conversation") && !semantic.containsKey("allTweetIds")) {
                Field match = null;
                for (Field field : cls.getInstanceFields()) if (field.getType().startsWith("Lkotlinx/collections/immutable/") || field.getType().equals("Ljava/util/List;")) {
                    if (match != null) { match = null; break; } match = field;
                }
                if (match != null) semantic.put("allTweetIds", match.getName());
            }
            semantic.put("$kind", kind); String name = DexEngine.javaName(cls.getType()); models.put(name, semantic);
            if (semantic.containsKey("entryId")) plan.entryFields.put(name, semantic.get("entryId"));
            if (semantic.containsKey("promotedMetadata")) plan.promotedFields.put(name, semantic.get("promotedMetadata"));
            if (kind.equals("post") && semantic.containsKey("prerollMetadata")) for (Method method : cls.getMethods()) {
                if (!method.getName().equals("<init>") || method.getImplementation() == null) continue;
                int writes = 0;
                for (Instruction ins : method.getImplementation().getInstructions()) if (ins.getOpcode() == Opcode.IPUT_OBJECT && ins instanceof ReferenceInstruction) {
                    FieldReference field = (FieldReference) ((ReferenceInstruction) ins).getReference();
                    if (field.getDefiningClass().equals(cls.getType()) && field.getName().equals(semantic.get("prerollMetadata"))) writes++;
                }
                if (writes == 1) preroll.put(DexEngine.key(method), semantic.get("prerollMetadata"));
            }
        }
        timelineModels.clear(); kinds.clear();
        for (Map.Entry<ClassDef, Method> entry : mediaModels.entrySet()) {
            ClassDef cls = entry.getKey(); Map<String, String> fields = semanticFields.fields(cls, entry.getValue());
            Map<String, String> mapped = new TreeMap<>();
            for (String[] alias : new String[][]{{"imageUrl","original_img_url"},{"variants","variants"},{"url","url"},{"bitRate","bitrate"},{"contentType","content_type"}}) if (fields.containsKey(alias[0])) mapped.put(alias[1], fields.get(alias[0]));
            boolean capture = mapped.containsKey("original_img_url") || mapped.containsKey("variants");
            if (!capture && !mapped.keySet().containsAll(Arrays.asList("url","content_type","bitrate"))) continue;
            plan.mediaFields.computeIfAbsent(DexEngine.javaName(cls.getType()), ignored -> new TreeMap<>()).putAll(mapped);
            if (capture) for (Method constructor : cls.getMethods()) if (constructor.getName().equals("<init>") && constructor.getImplementation() != null) mediaConstructors.add(DexEngine.key(constructor));
        }
        mediaModels.clear();
        List<Method> valid = new ArrayList<>();
        for (Method method : converters) {
            if (!listType(method.getReturnType(), new HashSet<>())) continue;
            int checks = 0, fallbacks = 0;
            for (Instruction ins : method.getImplementation().getInstructions()) if (ins instanceof ReferenceInstruction) {
                Reference ref = ((ReferenceInstruction) ins).getReference();
                if (ins.getOpcode() == Opcode.INSTANCE_OF && ref instanceof TypeReference && ((TypeReference) ref).getType().equals(method.getReturnType())) checks++;
                if (ins.getOpcode() == Opcode.INVOKE_STATIC && ref instanceof MethodReference) {
                    MethodReference call = (MethodReference) ref;
                    if (call.getDefiningClass().equals(method.getDefiningClass()) && call.getParameterTypes().toString().equals("[Ljava/lang/Iterable;]") && listType(call.getReturnType(), new HashSet<>())) fallbacks++;
                }
            }
            if (checks == 1 && fallbacks == 1) valid.add(method);
        }
        if (valid.size() == 1) converter = DexEngine.javaName(valid.get(0).getDefiningClass()) + "#" + valid.get(0).getName();
        if (successes.size() != 1 || converter.isEmpty()) lists.clear();
        if (navigation.size() != 1) navigation.clear();
        if (tabs.size() == 2 && tabs.get(0).getDefiningClass().equals(tabs.get(1).getDefiningClass()) && params(tabs.get(0)).size() != params(tabs.get(1)).size()) for (Method m : tabs) hidden.put(DexEngine.key(m), "hideTabs");
        if (spaces.size() == 1) hidden.put(DexEngine.key(spaces.get(0)), "hideSpaces");
    }
    private boolean listType(String type, Set<String> visited) {
        if (type.equals("Ljava/util/List;")) return true;
        if (!visited.add(type)) return false;
        for (String parent : parents.getOrDefault(type, Collections.emptyList())) if (listType(parent, visited)) return true;
        return false;
    }
    private static boolean tabShape(Method method, List<String> p) {
        for (int leading : new int[]{1, 2}) {
            if (p.size() != leading + 9) continue;
            boolean valid = true; for (int i = 0; i < leading; i++) valid &= object(p.get(i));
            if (!valid || !p.subList(leading, p.size()).equals(Arrays.asList(F1, F1, "Z", "Z", "Z", F0, MODIFIER, COMPOSER, "I"))) continue;
            int calls = 0;
            for (Instruction ins : method.getImplementation().getInstructions()) if (ins instanceof ReferenceInstruction && ((ReferenceInstruction) ins).getReference() instanceof MethodReference) {
                MethodReference ref = (MethodReference) ((ReferenceInstruction) ins).getReference();
                if (ins.getOpcode().name().startsWith("INVOKE_STATIC") && ref.getDefiningClass().startsWith("Lcom/x/ui/common/tabs/") && ref.getReturnType().equals("V")) calls++;
            }
            return calls == 1;
        }
        return false;
    }
    private static boolean spacesShape(List<String> p) {
        if (p.size() < 10 || !object(p.get(0)) || !p.subList(1, 4).equals(Arrays.asList(F1, MODIFIER, "Z")) || !p.subList(p.size() - 4, p.size()).equals(Arrays.asList(F0, COMPOSER, "I", "I"))) return false;
        for (String callback : p.subList(4, p.size() - 4)) if (!callback.equals(F1) && !callback.equals("Lkotlin/jvm/functions/Function2;")) return false;
        return true;
    }
    static int localCount(Method method) {
        int words = AccessFlags.STATIC.isSet(method.getAccessFlags()) ? 0 : 1;
        for (String p : params(method)) words += p.equals("J") || p.equals("D") ? 2 : 1;
        return method.getImplementation().getRegisterCount() - words;
    }
    static int[] navigationSite(Method method) {
        boolean communities = false, spaces = false, entries = false;
        Set<Integer> maps = new HashSet<>(); List<int[]> sites = new ArrayList<>(); int index = 0;
        for (Instruction ins : method.getImplementation().getInstructions()) {
            if (ins instanceof ReferenceInstruction) {
                Reference ref = ((ReferenceInstruction) ins).getReference();
                if (ref instanceof FieldReference && ins.getOpcode() == Opcode.SGET_OBJECT) {
                    communities |= ((FieldReference) ref).getName().equals("COMMUNITIES"); spaces |= ((FieldReference) ref).getName().equals("SPACES");
                }
                if (ref instanceof MethodReference) {
                    MethodReference call = (MethodReference) ref; List<Integer> regs = DexEngine.registers(ins);
                    entries |= call.getName().equals("getEntries") && call.getReturnType().equals("Lkotlin/enums/EnumEntries;");
                    if (call.getDefiningClass().equals("Ljava/util/Map;") && call.getName().equals("put") && regs.size() == 3) maps.add(regs.get(0));
                    if (call.getName().equals("<init>")) {
                        int word = 1;
                        for (CharSequence parameter : call.getParameterTypes()) {
                            String type = parameter.toString();
                            if (type.equals("Ljava/util/LinkedHashMap;") && word < regs.size() && maps.contains(regs.get(word)) && regs.get(word) <= 255) sites.add(new int[]{index, regs.get(word)});
                            word += type.equals("J") || type.equals("D") ? 2 : 1;
                        }
                    }
                }
            }
            index++;
        }
        return communities && spaces && entries && sites.size() == 1 ? sites.get(0) : null;
    }
    boolean contains(Method method) { String key = DexEngine.key(method); return lists.containsKey(key) || navigation.containsKey(key) || hidden.containsKey(key) || preroll.containsKey(key) || mediaConstructors.contains(key); }
    Method rewrite(Method method) {
        String key = DexEngine.key(method);
        MutableMethodImplementation body = new MutableMethodImplementation(method.getImplementation());
        if (mediaConstructors.contains(key)) {
            for (int i = body.getInstructions().size() - 1; i >= 0; i--) if (body.getInstructions().get(i).getOpcode() == Opcode.RETURN_VOID) body.addInstruction(i, new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, localCount(method), 1, new ImmutableMethodReference(DexEngine.RUNTIME, "capture", Collections.singletonList("Ljava/lang/Object;"), "V")));
        }
        if (preroll.containsKey(key)) {
            for (int i = body.getInstructions().size() - 1; i >= 0; i--) {
                Instruction ins = body.getInstructions().get(i);
                if (ins.getOpcode() != Opcode.IPUT_OBJECT || !(ins instanceof ReferenceInstruction)) continue;
                FieldReference field = (FieldReference) ((ReferenceInstruction) ins).getReference();
                if (!field.getDefiningClass().equals(method.getDefiningClass()) || !field.getName().equals(preroll.get(key))) continue;
                int r = ((TwoRegisterInstruction) ins).getRegisterA();
                body.addInstruction(i, new BuilderInstruction21c(Opcode.CHECK_CAST, r, new ImmutableTypeReference(field.getType())));
                body.addInstruction(i, new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, r));
                body.addInstruction(i, new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, r, 1, new ImmutableMethodReference(DexEngine.RUNTIME, "adMetadata", Collections.singletonList("Ljava/lang/Object;"), "Ljava/lang/Object;")));
            }
        }
        if (navigation.containsKey(key)) {
            int[] site = navigation.get(key); int i = site[0], r = site[1];
            body.addInstruction(i++, new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, r, 1, new ImmutableMethodReference(DexEngine.RUNTIME, "navigation", Collections.singletonList("Ljava/util/Map;"), "Ljava/util/Map;")));
            body.addInstruction(i++, new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, r));
            body.addInstruction(i, new BuilderInstruction21c(Opcode.CHECK_CAST, r, new ImmutableTypeReference("Ljava/util/LinkedHashMap;")));
        }
        if (lists.containsKey(key)) {
            int arg = lists.get(key), register = localCount(method) + 1;
            List<String> p = params(method); for (int i = 0; i < arg; i++) register += p.get(i).equals("J") || p.get(i).equals("D") ? 2 : 1;
            if (register > 255) throw new IllegalStateException("Timeline argument exceeds supported register range");
            body.addInstruction(0, new BuilderInstruction21c(Opcode.CHECK_CAST, register, new ImmutableTypeReference(p.get(arg))));
            body.addInstruction(0, new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, register));
            body.addInstruction(0, new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, register, 1, new ImmutableMethodReference(DexEngine.RUNTIME, "timeline", Collections.singletonList("Ljava/lang/Object;"), "Ljava/lang/Object;")));
        }
        if (hidden.containsKey(key)) {
            Label original = body.newLabelForIndex(0);
            body.addInstruction(0, new BuilderInstruction10x(Opcode.RETURN_VOID));
            body.addInstruction(0, new BuilderInstruction21t(Opcode.IF_EQZ, 0, original));
            body.addInstruction(0, new BuilderInstruction11x(Opcode.MOVE_RESULT, 0));
            body.addInstruction(0, new BuilderInstruction35c(Opcode.INVOKE_STATIC, 0, 0, 0, 0, 0, 0, new ImmutableMethodReference(DexEngine.RUNTIME, hidden.get(key), Collections.emptyList(), "Z")));
        }
        return new ImmutableMethod(method.getDefiningClass(), method.getName(), method.getParameters(), method.getReturnType(), method.getAccessFlags(), method.getAnnotations(), method.getHiddenApiRestrictions(), body);
    }
}
