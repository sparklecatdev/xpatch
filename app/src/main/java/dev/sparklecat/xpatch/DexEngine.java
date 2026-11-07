package dev.sparklecat.xpatch;

import org.jf.dexlib2.*;
import org.jf.dexlib2.builder.MutableMethodImplementation;
import org.jf.dexlib2.builder.BuilderInstruction;
import org.jf.dexlib2.builder.instruction.*;
import org.jf.dexlib2.dexbacked.DexBackedDexFile;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.iface.reference.*;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.reference.*;
import org.jf.dexlib2.writer.pool.DexPool;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/** Independent structural patches. No upstream patch bundles or fixed register numbers. */
public final class DexEngine {
    static final String RUNTIME = "Ldev/sparklecat/xpatch/runtime/Mods;";
    static final ImmutableMethodReference INSTALL = new ImmutableMethodReference(RUNTIME, "install", Collections.singletonList("Landroid/app/Application;"), "V");
    static final ImmutableMethodReference FILTER = new ImmutableMethodReference(RUNTIME, "filter", Collections.singletonList("Ljava/lang/Object;"), "Ljava/lang/Object;");
    public static final class Plan {
        public final String application;
        public final Map<String, String> entryFields = new TreeMap<>();
        public final Map<String, String> promotedFields = new TreeMap<>();
        final Set<String> collectionHooks = new HashSet<>();
        final Set<String> hooks = new HashSet<>();
        boolean applicationFound;
        Plan(String application) { this.application = application; }
        public boolean adsAvailable() { return !hooks.isEmpty() || !collectionHooks.isEmpty(); }
        public String summary() { return (hooks.size() + collectionHooks.size()) + " timeline parser(s) · " + entryFields.size() + " model(s)\nApplication hook: " + (applicationFound ? "matched" : "missing"); }
    }
    static String key(Method method) {
        return method.getDefiningClass() + "->" + method.getName() + method.getParameterTypes() + method.getReturnType();
    }
    static List<ZipEntry> dexEntries(ZipFile zip) throws IOException {
        List<ZipEntry> entries = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Enumeration<? extends ZipEntry> all = zip.entries();
        while (all.hasMoreElements()) {
            ZipEntry entry = all.nextElement();
            if (!names.add(entry.getName())) throw new IOException("Duplicate APK entry: " + entry.getName());
            if (entry.getName().matches("classes(?:[2-9]|[1-9][0-9]+)?\\.dex")) {
                if (entry.getSize() < 0 || entry.getSize() > 192L * 1024 * 1024) throw new IOException("DEX exceeds size limit.");
                entries.add(entry);
            }
        }
        if (entries.isEmpty() || entries.size() > 100) throw new IOException("Missing or excessive DEX files.");
        return entries;
    }
    static DexBackedDexFile read(ZipFile zip, ZipEntry entry) throws IOException {
        try (InputStream in = zip.getInputStream(entry)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ApkPatcher.copy(in, out, 192L * 1024 * 1024);
            return new DexBackedDexFile(null, out.toByteArray());
        }
    }
    static List<Integer> registers(Instruction ins) {
        List<Integer> result = new ArrayList<>();
        if (ins instanceof RegisterRangeInstruction) {
            RegisterRangeInstruction range = (RegisterRangeInstruction) ins;
            for (int n = 0; n < range.getRegisterCount(); n++) result.add(range.getStartRegister() + n);
        } else if (ins instanceof FiveRegisterInstruction) {
            FiveRegisterInstruction five = (FiveRegisterInstruction) ins;
            int[] values = {five.getRegisterC(), five.getRegisterD(), five.getRegisterE(), five.getRegisterF(), five.getRegisterG()};
            for (int n = 0; n < five.getRegisterCount(); n++) result.add(values[n]);
        }
        return result;
    }
    static String javaName(String descriptor) { return descriptor.substring(1, descriptor.length() - 1).replace('/', '.'); }
    // Generated serializers retain descriptor keys even after class/field renaming.
    static void serializer(ClassDef cls, Plan plan) {
        List<String> keys = new ArrayList<>();
        for (Method method : cls.getMethods()) if (method.getName().equals("<clinit>") && method.getImplementation() != null) {
            Map<Integer, String> strings = new HashMap<>();
            for (Instruction ins : method.getImplementation().getInstructions()) {
                if (ins instanceof ReferenceInstruction) {
                    Reference ref = ((ReferenceInstruction) ins).getReference();
                    if (ref instanceof StringReference && ins instanceof OneRegisterInstruction) strings.put(((OneRegisterInstruction) ins).getRegisterA(), ((StringReference) ref).getString());
                    if (ref instanceof MethodReference) {
                        MethodReference call = (MethodReference) ref;
                        List<Integer> regs = registers(ins);
                        if (call.getParameterTypes().toString().equals("[Ljava/lang/String;, Z]") && regs.size() == 3 && strings.containsKey(regs.get(1))) keys.add(strings.get(regs.get(1)));
                    }
                }
            }
        }
        if (!keys.contains("entryId") || !keys.contains("sortIndex")) return;
        for (Method method : cls.getMethods()) if (method.getName().equals("serialize") && method.getImplementation() != null) {
            Map<Integer, Object> values = new HashMap<>();
            for (Instruction ins : method.getImplementation().getInstructions()) {
                Object moved = ins instanceof TwoRegisterInstruction ? values.get(((TwoRegisterInstruction) ins).getRegisterB()) : null;
                if (ins.getOpcode().setsRegister() && ins instanceof OneRegisterInstruction) values.remove(((OneRegisterInstruction) ins).getRegisterA());
                if (ins instanceof NarrowLiteralInstruction && ins instanceof OneRegisterInstruction) values.put(((OneRegisterInstruction) ins).getRegisterA(), ((NarrowLiteralInstruction) ins).getNarrowLiteral());
                if (ins.getOpcode().name().startsWith("MOVE_OBJECT") && ins instanceof TwoRegisterInstruction && moved != null) values.put(((TwoRegisterInstruction) ins).getRegisterA(), moved);
                if (!(ins instanceof ReferenceInstruction)) continue;
                Reference ref = ((ReferenceInstruction) ins).getReference();
                if (ins.getOpcode() == Opcode.IGET_OBJECT && ref instanceof FieldReference) values.put(((OneRegisterInstruction) ins).getRegisterA(), ref);
                if (!(ref instanceof MethodReference)) continue;
                MethodReference call = (MethodReference) ref;
                List<? extends CharSequence> params = call.getParameterTypes();
                List<Integer> regs = registers(ins);
                if (!call.getReturnType().equals("V") || params.size() < 3 || !params.get(0).toString().equals("Lkotlinx/serialization/descriptors/SerialDescriptor;") || !params.get(1).toString().equals("I") || regs.size() != params.size() + 1) continue;
                Object index = values.get(regs.get(2)), field = values.get(regs.get(regs.size() - 1));
                if (!(index instanceof Integer) || !(field instanceof FieldReference) || (int) index < 0 || (int) index >= keys.size()) continue;
                FieldReference f = (FieldReference) field;
                String key = keys.get((int) index);
                if (key.equals("entryId") && f.getType().equals("Ljava/lang/String;")) plan.entryFields.put(javaName(f.getDefiningClass()), f.getName());
                if (key.equals("promotedMetadata")) plan.promotedFields.put(javaName(f.getDefiningClass()), f.getName());
            }
        }
    }
    static Method collectionDeserializer(ClassDef cls) {
        if (!cls.getType().startsWith("Lkotlinx/serialization/internal/")) return null;
        boolean array = false, iterator = false, descriptor = false;
        Method deserialize = null;
        for (Method method : cls.getMethods()) {
            if (method.getName().equals("deserialize") && method.getParameterTypes().toString().equals("[Lkotlinx/serialization/encoding/Decoder;]") && method.getReturnType().equals("Ljava/lang/Object;") && method.getImplementation() != null) deserialize = method;
            if (method.getImplementation() == null) continue;
            for (Instruction ins : method.getImplementation().getInstructions()) if (ins instanceof ReferenceInstruction) {
                Reference ref = ((ReferenceInstruction) ins).getReference();
                if (ins.getOpcode() == Opcode.NEW_INSTANCE && ref instanceof TypeReference && ((TypeReference) ref).getType().equals("Ljava/util/ArrayList;")) array = true;
                if (ref instanceof MethodReference) {
                    MethodReference call = (MethodReference) ref;
                    iterator |= call.getDefiningClass().equals("Ljava/util/Collection;") && call.getName().equals("iterator");
                    descriptor |= call.getDefiningClass().equals("Lkotlinx/serialization/KSerializer;") && call.getName().equals("getDescriptor");
                }
            }
        }
        return array && iterator && descriptor ? deserialize : null;
    }
    public static Plan analyze(File apk, String application) throws IOException {
        if (application == null || !application.matches("[A-Za-z_$][A-Za-z0-9_$.]*")) throw new IOException("Unsupported application class.");
        Plan plan = new Plan("L" + application.replace('.', '/') + ";");
        Map<String, String> candidates = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(apk)) {
            List<ZipEntry> entries = dexEntries(zip);
            for (ZipEntry entry : entries) {
                for (ClassDef cls : read(zip, entry).getClasses()) {
                    if (cls.getType().startsWith("Ldev/sparklecat/xpatch/runtime/")) throw new IOException("Already patched APK. Select the original.");
                    if (cls.getType().equals(plan.application)) plan.applicationFound = true;
                    serializer(cls, plan);
                    Method collection = collectionDeserializer(cls);
                    if (collection != null) plan.collectionHooks.add(key(collection));
                    Set<String> strings = new HashSet<>();
                    List<Method> parsers = new ArrayList<>();
                    for (Method method : cls.getMethods()) {
                        if (method.getImplementation() == null) continue;
                        if (method.getName().equals("parse") && method.getReturnType().startsWith("L")) parsers.add(method);
                        for (Instruction ins : method.getImplementation().getInstructions()) {
                            if (ins instanceof ReferenceInstruction && ((ReferenceInstruction) ins).getReference() instanceof StringReference) {
                                String value = ((StringReference) ((ReferenceInstruction) ins).getReference()).getString();
                                if (value.equals("entryId") || value.equals("sortIndex") || value.equals("content") || value.equals("item")) strings.add(value);
                            }
                        }
                    }
                    // Serialized keys identify the schema even when the mapper and model are renamed.
                    if (!strings.contains("entryId") || !(strings.contains("item") || strings.contains("sortIndex") && strings.contains("content"))) continue;
                    for (Method method : parsers) {
                        Set<String> models = new HashSet<>();
                        for (Instruction ins : method.getImplementation().getInstructions()) {
                            if (ins.getOpcode() == Opcode.NEW_INSTANCE) models.add(((TypeReference) ((ReferenceInstruction) ins).getReference()).getType());
                        }
                        if (models.size() == 1) candidates.put(key(method), models.iterator().next());
                    }
                }
            }
            if (!plan.applicationFound) throw new IOException("Application class is outside the APK. A complete, standalone APK is required.");
            Set<String> models = new HashSet<>(candidates.values());
            for (ZipEntry entry : entries) {
                for (ClassDef cls : read(zip, entry).getClasses()) {
                    if (!models.contains(cls.getType())) continue;
                    List<Field> fields = new ArrayList<>();
                    for (Field field : cls.getInstanceFields()) if (field.getType().equals("Ljava/lang/String;")) fields.add(field);
                    // Never guess an obfuscated field when more than one is possible.
                    if (fields.size() == 1) plan.entryFields.put(cls.getType().substring(1, cls.getType().length() - 1).replace('/', '.'), fields.get(0).getName());
                }
            }
        }
        if (plan.entryFields.isEmpty() || plan.collectionHooks.size() != 1) plan.collectionHooks.clear();
        for (Map.Entry<String, String> candidate : candidates.entrySet()) {
            String model = candidate.getValue();
            if (plan.entryFields.containsKey(model.substring(1, model.length() - 1).replace('/', '.'))) plan.hooks.add(candidate.getKey());
        }
        return plan;
    }
    static Method hook(Method method, boolean application) throws IOException {
        MutableMethodImplementation body = new MutableMethodImplementation(method.getImplementation());
        int matches = 0;
        for (int i = body.getInstructions().size() - 1; i >= 0; i--) {
            BuilderInstruction instruction = body.getInstructions().get(i);
            if (application && instruction.getOpcode() == Opcode.RETURN_VOID) {
                int receiver = body.getRegisterCount() - 1;
                body.addInstruction(i, new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, receiver, 1, INSTALL));
                matches++;
            } else if (!application && instruction.getOpcode() == Opcode.RETURN_OBJECT) {
                int register = ((OneRegisterInstruction) instruction).getRegisterA();
                if (!method.getReturnType().equals("Ljava/lang/Object;")) body.addInstruction(i, new BuilderInstruction21c(Opcode.CHECK_CAST, register, new ImmutableTypeReference(method.getReturnType())));
                body.addInstruction(i, new BuilderInstruction11x(Opcode.MOVE_RESULT_OBJECT, register));
                body.addInstruction(i, new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, register, 1, FILTER));
                matches++;
            }
        }
        if (matches == 0) throw new IOException("Matched method has no supported return: " + key(method));
        return new ImmutableMethod(method.getDefiningClass(), method.getName(), method.getParameters(), method.getReturnType(), method.getAccessFlags(), method.getAnnotations(), method.getHiddenApiRestrictions(), body);
    }
    static ClassDef rewrite(ClassDef cls, Plan plan) throws IOException {
        boolean application = cls.getType().equals(plan.application);
        boolean changed = application;
        boolean onCreate = false;
        List<Method> methods = new ArrayList<>();
        for (Method method : cls.getMethods()) {
            boolean bootstrap = application && method.getName().equals("onCreate") && method.getParameterTypes().isEmpty() && method.getReturnType().equals("V");
            if (bootstrap) onCreate = true;
            if (bootstrap || plan.hooks.contains(key(method)) || plan.collectionHooks.contains(key(method))) {
                if (method.getImplementation() == null || AccessFlags.STATIC.isSet(method.getAccessFlags())) throw new IOException("Unsupported hook method: " + key(method));
                methods.add(hook(method, bootstrap)); changed = true;
            } else methods.add(method);
        }
        if (application && !onCreate) {
            if (cls.getSuperclass() == null) throw new IOException("Missing application superclass.");
            ImmutableMethodReference parent = new ImmutableMethodReference(cls.getSuperclass(), "onCreate", Collections.emptyList(), "V");
            List<Instruction> instructions = Arrays.asList(new BuilderInstruction3rc(Opcode.INVOKE_SUPER_RANGE, 0, 1, parent), new BuilderInstruction3rc(Opcode.INVOKE_STATIC_RANGE, 0, 1, INSTALL), new BuilderInstruction10x(Opcode.RETURN_VOID));
            methods.add(new ImmutableMethod(cls.getType(), "onCreate", Collections.emptyList(), "V", AccessFlags.PUBLIC.getValue(), Collections.emptySet(), Collections.emptySet(), new ImmutableMethodImplementation(1, instructions, Collections.emptyList(), Collections.emptyList())));
        }
        return changed ? new ImmutableClassDef(cls.getType(), cls.getAccessFlags(), cls.getSuperclass(), cls.getInterfaces(), cls.getSourceFile(), cls.getAnnotations(), cls.getFields(), methods) : cls;
    }
    public static Map<String, File> apply(File apk, File work, Plan plan) throws IOException {
        Map<String, File> replacements = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(apk)) {
            for (ZipEntry entry : dexEntries(zip)) {
                DexBackedDexFile dex = read(zip, entry);
                Set<ClassDef> classes = new LinkedHashSet<>();
                boolean changed = false;
                for (ClassDef cls : dex.getClasses()) {
                    ClassDef patched = rewrite(cls, plan);
                    classes.add(patched); changed |= patched != cls;
                }
                if (changed) {
                    File out = new File(work, entry.getName());
                    // Keep unchanged classes backed by the original DEX. Eager immutable
                    // copies of every instruction exceed phone heap limits on modern X.
                    DexPool.writeTo(out.getPath(), new DexFile() {
                        @Override public Set<? extends ClassDef> getClasses() { return classes; }
                        @Override public Opcodes getOpcodes() { return dex.getOpcodes(); }
                    });
                    replacements.put(entry.getName(), out);
                }
            }
        }
        return replacements;
    }
}
