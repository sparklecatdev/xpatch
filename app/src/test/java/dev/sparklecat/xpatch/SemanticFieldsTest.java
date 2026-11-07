package dev.sparklecat.xpatch;

import java.util.*;
import org.junit.Test;
import org.jf.dexlib2.*;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.instruction.*;
import org.jf.dexlib2.immutable.reference.*;
import static org.junit.Assert.*;

public class SemanticFieldsTest {
    private static final String BUILDER="Ljava/lang/StringBuilder;", MODEL="Lcom/x/models/Module;";
    private static Method helper(List<? extends Instruction> instructions) {
        List<ImmutableMethodParameter> parameters=new ArrayList<>();
        for(String type:Arrays.asList("J","Ljava/lang/String;","Ljava/lang/String;",BUILDER)) parameters.add(new ImmutableMethodParameter(type,Collections.emptySet(),null));
        return new ImmutableMethod("Lr8/Helper;","append",parameters,BUILDER,AccessFlags.PUBLIC.getValue()|AccessFlags.STATIC.getValue(),Collections.emptySet(),Collections.emptySet(),new ImmutableMethodImplementation(5,instructions,Collections.emptyList(),Collections.emptyList()));
    }
    private static List<Instruction> helperBody() {
        List<Instruction> code=new ArrayList<>();
        for(int register:new int[]{2,0,3}) code.add(new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL,register==0?3:2,4,register,register+1,0,0,new ImmutableMethodReference(BUILDER,"append",Collections.singletonList(register==0?"J":"Ljava/lang/String;"),BUILDER)));
        code.add(new ImmutableInstruction11x(Opcode.RETURN_OBJECT,4)); return code;
    }
    private static Method description(boolean branched) {
        List<Instruction> code=new ArrayList<>(Arrays.asList(
            new ImmutableInstruction21c(Opcode.CONST_STRING,0,new ImmutableStringReference("Module(sortIndex=")),
            new ImmutableInstruction21c(Opcode.CONST_STRING,1,new ImmutableStringReference(", entryId=")),
            new ImmutableInstruction22c(Opcode.IGET_WIDE,2,6,new ImmutableFieldReference(MODEL,"sort","J")),
            new ImmutableInstruction22c(Opcode.IGET_OBJECT,5,6,new ImmutableFieldReference(MODEL,"id","Ljava/lang/String;")),
            new ImmutableInstruction35c(Opcode.INVOKE_STATIC,5,2,3,0,1,4,new ImmutableMethodReference("Lr8/Helper;","append",Arrays.asList("J","Ljava/lang/String;","Ljava/lang/String;",BUILDER),BUILDER)),
            new ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL,2,4,5,0,0,0,new ImmutableMethodReference(BUILDER,"append",Collections.singletonList("Ljava/lang/String;"),BUILDER)),
            new ImmutableInstruction11x(Opcode.RETURN_OBJECT,4)));
        if(branched)code.add(0,new ImmutableInstruction21t(Opcode.IF_EQZ,0,2));
        return DexEngineTest.method(MODEL,"toString","Ljava/lang/String;",7,code);
    }
    @Test public void usesAppendOrderAcrossReorderedHelpersAndWideParameters() {
        SemanticFields fields=new SemanticFields();fields.index(helper(helperBody()));Method method=description(false);
        Map<String,String> result=fields.fields(DexEngineTest.cls(MODEL,Collections.emptyList(),Collections.singletonList(method)),method);
        assertEquals(new TreeMap<String,String>(){{put("sortIndex","sort");put("entryId","id");}},result);
    }
    @Test public void rejectsConditionalHelpersAndDescriptions() {
        SemanticFields fields=new SemanticFields();List<Instruction> body=helperBody();body.add(0,new ImmutableInstruction21t(Opcode.IF_EQZ,2,2));fields.index(helper(body));
        Method straight=description(false);assertFalse(fields.fields(DexEngineTest.cls(MODEL,Collections.emptyList(),Collections.singletonList(straight)),straight).containsKey("sortIndex"));
        fields.index(helper(helperBody()));Method branched=description(true);assertTrue(fields.fields(DexEngineTest.cls(MODEL,Collections.emptyList(),Collections.singletonList(branched)),branched).isEmpty());
    }
}
