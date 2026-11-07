package dev.sparklecat.xpatch;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.jf.dexlib2.*;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.iface.reference.MethodReference;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.instruction.*;
import org.jf.dexlib2.immutable.reference.*;
import org.jf.dexlib2.writer.pool.DexPool;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

public class DexEngineTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    static final String APP = "Lexample/App;", MODEL = "Lrenamed/b;", MAPPER = "Lrenamed/a;";
    static Method method(String owner, String name, String result, int registers, List<? extends Instruction> code) {
        return new ImmutableMethod(owner, name, Collections.emptyList(), result, 1, Collections.emptySet(), Collections.emptySet(), new ImmutableMethodImplementation(registers, code, Collections.emptyList(), Collections.emptyList()));
    }
    static ClassDef cls(String type, List<? extends Field> fields, List<? extends Method> methods) {
        return new ImmutableClassDef(type, 1, "Ljava/lang/Object;", Collections.emptyList(), null, Collections.emptySet(), fields, methods);
    }
    File fixture(boolean ambiguous) throws Exception {
        List<Instruction> parser = Arrays.asList(
            new ImmutableInstruction21c(Opcode.CONST_STRING, 0, new ImmutableStringReference("entryId")),
            new ImmutableInstruction21c(Opcode.CONST_STRING, 0, new ImmutableStringReference("item")),
            new ImmutableInstruction21c(Opcode.NEW_INSTANCE, 7, new ImmutableTypeReference(MODEL)),
            new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 7));
        List<Field> fields = new ArrayList<>();
        fields.add(new ImmutableField(MODEL, "renamedId", "Ljava/lang/String;", 1, null, Collections.emptySet(), Collections.emptySet()));
        if (ambiguous) fields.add(new ImmutableField(MODEL, "other", "Ljava/lang/String;", 1, null, Collections.emptySet(), Collections.emptySet()));
        File dex = temp.newFile();
        DexPool.writeTo(dex.getPath(), new ImmutableDexFile(Opcodes.forApi(26), Arrays.asList(cls(APP, Collections.emptyList(), Collections.emptyList()), cls(MODEL, fields, Collections.emptyList()), cls(MAPPER, Collections.emptyList(), Collections.singletonList(method(MAPPER, "parse", MODEL, 8, parser))))));
        File apk = temp.newFile();
        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(apk))) { zip.putNextEntry(new ZipEntry("classes.dex")); zip.write(java.nio.file.Files.readAllBytes(dex.toPath())); zip.closeEntry(); }
        return apk;
    }
    @Test public void findsRenamedSchemaAndRewritesActualReturnRegister() throws Exception {
        File apk = fixture(false);
        DexEngine.Plan plan = DexEngine.analyze(apk, "example.App");
        assertTrue(plan.adsAvailable()); assertEquals("renamedId", plan.entryFields.get("renamed.b"));
        File patched = DexEngine.apply(apk, temp.newFolder(), plan).get("classes.dex");
        org.jf.dexlib2.dexbacked.DexBackedDexFile dex = org.jf.dexlib2.dexbacked.DexBackedDexFile.fromInputStream(Opcodes.forApi(26), new BufferedInputStream(new FileInputStream(patched)));
        boolean filter = false, bootstrap = false;
        for (ClassDef cls : dex.getClasses()) for (Method method : cls.getMethods()) {
            if (method.getName().equals("onCreate")) bootstrap = true;
            for (Instruction ins : method.getImplementation().getInstructions()) if (ins instanceof ReferenceInstruction && ((ReferenceInstruction) ins).getReference() instanceof MethodReference) {
                MethodReference ref = (MethodReference) ((ReferenceInstruction) ins).getReference();
                if (ref.getName().equals("filter")) { assertEquals(7, ((RegisterRangeInstruction) ins).getStartRegister()); filter = true; }
            }
        }
        assertTrue(filter); assertTrue(bootstrap);
    }
    @Test public void ambiguousStringFieldDoesNotGuess() throws Exception { assertFalse(DexEngine.analyze(fixture(true), "example.App").adsAvailable()); }
    @Test public void missingApplicationIsRejected() throws Exception {
        try { DexEngine.analyze(fixture(false), "missing.App"); fail(); } catch (IOException expected) { assertTrue(expected.getMessage().contains("Application")); }
    }
    @Test public void hooksEveryReturnAndSupportsHighApplicationRegister() throws Exception {
        Method parser = method(MAPPER, "parse", MODEL, 8, Arrays.asList(new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 7), new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 2)));
        List<Instruction> result = new ArrayList<>(); DexEngine.hook(parser, false).getImplementation().getInstructions().forEach(result::add);
        assertEquals(8, result.size()); assertEquals(7, ((RegisterRangeInstruction) result.get(0)).getStartRegister()); assertEquals(2, ((RegisterRangeInstruction) result.get(4)).getStartRegister());
        Method app = method(APP, "onCreate", "V", 300, Collections.singletonList(new ImmutableInstruction10x(Opcode.RETURN_VOID)));
        Instruction hook = DexEngine.hook(app, true).getImplementation().getInstructions().iterator().next();
        assertEquals(299, ((RegisterRangeInstruction) hook).getStartRegister());
    }

    @Test public void derivesModernFieldsFromDescriptorIndexes() {
        String serializer = "Lrenamed/Serializer;";
        List<Instruction> init = new ArrayList<>();
        for (String key : Arrays.asList("other", "entryId", "sortIndex", "promotedMetadata")) {
            init.add(new ImmutableInstruction21c(Opcode.CONST_STRING, 1, new ImmutableStringReference(key)));
            init.add(new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 3, 0, 1, 2, 0, 0, new ImmutableMethodReference("Ldescriptor;", "renamed", Arrays.asList("Ljava/lang/String;", "Z"), "V")));
        }
        List<Instruction> encode = new ArrayList<>();
        for (int index : new int[]{0, 1, 3}) {
            encode.add(new ImmutableInstruction11n(Opcode.CONST_4, 2, index));
            encode.add(new ImmutableInstruction22c(Opcode.IGET_OBJECT, 3, 4, new ImmutableFieldReference(MODEL, index == 0 ? "decoy" : index == 1 ? "actualId" : "marker", index == 3 ? "Ljava/lang/Object;" : "Ljava/lang/String;")));
            encode.add(new ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 4, 0, 1, 2, 3, 0, new ImmutableMethodReference("Lencoder;", "encode", Arrays.asList("Lkotlinx/serialization/descriptors/SerialDescriptor;", "I", "Ljava/lang/Object;"), "V")));
        }
        DexEngine.Plan plan = new DexEngine.Plan(APP);
        DexEngine.serializer(cls(serializer, Collections.emptyList(), Arrays.asList(method(serializer, "<clinit>", "V", 5, init), method(serializer, "serialize", "V", 5, encode))), plan);
        assertEquals("actualId", plan.entryFields.get("renamed.b"));
        assertEquals("marker", plan.promotedFields.get("renamed.b"));
    }
    @Test public void collectionMatcherRequiresAllStructuralSignals() {
        String type = "Lkotlinx/serialization/internal/renamed;";
        List<Instruction> code = new ArrayList<>(Arrays.asList(
            new ImmutableInstruction21c(Opcode.NEW_INSTANCE, 0, new ImmutableTypeReference("Ljava/util/ArrayList;")),
            new ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 1, 0, 0, 0, 0, 0, new ImmutableMethodReference("Ljava/util/Collection;", "iterator", Collections.emptyList(), "Ljava/util/Iterator;")),
            new ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 1, 0, 0, 0, 0, 0, new ImmutableMethodReference("Lkotlinx/serialization/KSerializer;", "getDescriptor", Collections.emptyList(), "Lkotlinx/serialization/descriptors/SerialDescriptor;")),
            new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0)));
        Method deserialize = new ImmutableMethod(type, "deserialize", Collections.singletonList(new ImmutableMethodParameter("Lkotlinx/serialization/encoding/Decoder;", Collections.emptySet(), null)), "Ljava/lang/Object;", 1, Collections.emptySet(), Collections.emptySet(), new ImmutableMethodImplementation(2, code, Collections.emptyList(), Collections.emptyList()));
        assertNotNull(DexEngine.collectionDeserializer(cls(type, Collections.emptyList(), Collections.singletonList(deserialize))));
        code.remove(2);
        assertNull(DexEngine.collectionDeserializer(cls(type, Collections.emptyList(), Collections.singletonList(method(type, "helper", "Ljava/lang/Object;", 2, code)))));
    }
}
