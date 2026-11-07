package dev.sparklecat.xpatch;

import org.junit.Test;
import org.jf.dexlib2.*;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.iface.reference.*;
import org.jf.dexlib2.immutable.*;
import org.jf.dexlib2.immutable.instruction.*;
import org.jf.dexlib2.immutable.reference.*;
import java.util.*;
import static org.junit.Assert.*;

public class ModernHooksTest {
    static List<Instruction> code(Method method) { List<Instruction> result=new ArrayList<>(); method.getImplementation().getInstructions().forEach(result::add); return result; }
    @Test public void hidingBarBranchesToOriginalBodyWhenDisabled() {
        Method original=new ImmutableMethod("Lexample/Tabs;","render",Collections.emptyList(),"V",AccessFlags.PUBLIC.getValue()|AccessFlags.STATIC.getValue(),Collections.emptySet(),Collections.emptySet(),
            new ImmutableMethodImplementation(1,Arrays.asList(new ImmutableInstruction11n(Opcode.CONST_4,0,7),new ImmutableInstruction10x(Opcode.RETURN_VOID)),Collections.emptyList(),Collections.emptyList()));
        ModernHooks hooks=new ModernHooks(); hooks.hidden.put(DexEngine.key(original),"hideTabs");
        List<Instruction> patched=code(hooks.rewrite(original)); assertEquals(6,patched.size());
        assertEquals(Opcode.IF_EQZ,patched.get(2).getOpcode()); assertEquals(Opcode.RETURN_VOID,patched.get(3).getOpcode()); assertEquals(Opcode.CONST_4,patched.get(4).getOpcode());
        int targetAddress=patched.get(0).getCodeUnits()+patched.get(1).getCodeUnits()+((OffsetInstruction)patched.get(2)).getCodeOffset();
        int originalAddress=0;for(int i=0;i<4;i++)originalAddress+=patched.get(i).getCodeUnits(); assertEquals(originalAddress,targetAddress);
    }
    @Test public void prerollHookOnlyChangesTheMatchedFieldAndPreservesReturn() {
        String owner="Lcom/x/models/Post;"; FieldReference preroll=new ImmutableFieldReference(owner,"r","Lexample/Preroll;"),other=new ImmutableFieldReference(owner,"o","Ljava/lang/Object;");
        Method original=DexEngineTest.method(owner,"<init>","V",3,Arrays.asList(new ImmutableInstruction22c(Opcode.IPUT_OBJECT,0,2,preroll),new ImmutableInstruction22c(Opcode.IPUT_OBJECT,1,2,other),new ImmutableInstruction10x(Opcode.RETURN_VOID)));
        ModernHooks hooks=new ModernHooks(); hooks.preroll.put(DexEngine.key(original),"r"); List<Instruction> patched=code(hooks.rewrite(original));
        assertEquals(6,patched.size()); assertEquals("adMetadata",((MethodReference)((ReferenceInstruction)patched.get(0)).getReference()).getName()); assertEquals(0,((RegisterRangeInstruction)patched.get(0)).getStartRegister());
        assertEquals(preroll,((ReferenceInstruction)patched.get(3)).getReference()); assertEquals(other,((ReferenceInstruction)patched.get(4)).getReference()); assertEquals(Opcode.RETURN_VOID,patched.get(5).getOpcode());
    }
    @Test public void timelineHookUsesParameterWordOffsetsAfterWideArguments() {
        Method original=new ImmutableMethod("Lexample/Timeline;","<init>",Arrays.asList(new ImmutableMethodParameter("J",Collections.emptySet(),null),new ImmutableMethodParameter("Ljava/util/List;",Collections.emptySet(),null)),"V",1,Collections.emptySet(),Collections.emptySet(),new ImmutableMethodImplementation(6,Collections.singletonList(new ImmutableInstruction10x(Opcode.RETURN_VOID)),Collections.emptyList(),Collections.emptyList()));
        ModernHooks hooks=new ModernHooks(); hooks.lists.put(DexEngine.key(original),1); List<Instruction> patched=code(hooks.rewrite(original)); assertEquals(5,((RegisterRangeInstruction)patched.get(0)).getStartRegister());
    }
}
