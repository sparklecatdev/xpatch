package dev.sparklecat.xpatch;

import java.util.*;
import org.jf.dexlib2.*;
import org.jf.dexlib2.iface.*;
import org.jf.dexlib2.iface.instruction.*;
import org.jf.dexlib2.iface.reference.*;

/** Follows StringBuilder append order, including R8's reordered string helpers. */
final class SemanticFields {
    private static final String BUILDER="Ljava/lang/StringBuilder;";
    private static final Object UNKNOWN=new Object();
    private final Map<String,List<Object>> helpers=new HashMap<>();
    private static String key(MethodReference method) { return method.getDefiningClass()+"->"+method.getName()+method.getParameterTypes()+method.getReturnType(); }
    void index(Method method) {
        if(method.getImplementation()==null || !AccessFlags.STATIC.isSet(method.getAccessFlags())) return;
        if(!method.getReturnType().equals(BUILDER) && !method.getParameterTypes().contains(BUILDER)) return;
        Map<Integer,Object> parameters=new HashMap<>(); int word=0;
        int first=ModernHooks.localCount(method);
        for(CharSequence type:method.getParameterTypes()) { parameters.put(first+word,word); word+=type.toString().equals("J")||type.toString().equals("D")?2:1; }
        List<Object> sequence=trace(method,parameters,true);
        if(sequence!=null && !sequence.isEmpty()) helpers.put(key(method),sequence);
    }
    Map<String,String> fields(ClassDef cls,Method method) {
        List<Object> sequence=trace(method,new HashMap<>(),false); Map<String,String> result=new TreeMap<>(); String label=null;
        if(sequence==null)return result;
        for(Object part:sequence) {
            if(part instanceof String) { String text=(String)part; int start=text.startsWith(", ")?1:text.lastIndexOf('('); label=text.endsWith("=")?text.substring(start+1,text.length()-1).trim():null; }
            else { if(label!=null && part instanceof FieldReference) { FieldReference field=(FieldReference)part; if(field.getDefiningClass().equals(cls.getType()))result.put(label,field.getName()); } label=null; }
        }
        return result;
    }
    private List<Object> trace(Method method,Map<Integer,Object> values,boolean helper) {
        List<Object> sequence=new ArrayList<>(); int count=0;
        for(Instruction ins:method.getImplementation().getInstructions()) {
            if(ins instanceof OffsetInstruction || (helper && ++count>40))return null;
            Object moved=ins instanceof TwoRegisterInstruction?values.get(((TwoRegisterInstruction)ins).getRegisterB()):null;
            if(ins.getOpcode().setsRegister() && ins instanceof OneRegisterInstruction)values.remove(((OneRegisterInstruction)ins).getRegisterA());
            if(ins.getOpcode().name().startsWith("MOVE") && ins instanceof TwoRegisterInstruction && moved!=null)values.put(((OneRegisterInstruction)ins).getRegisterA(),moved);
            if(!(ins instanceof ReferenceInstruction))continue;
            Reference ref=((ReferenceInstruction)ins).getReference();
            if(ref instanceof StringReference && ins instanceof OneRegisterInstruction)values.put(((OneRegisterInstruction)ins).getRegisterA(),((StringReference)ref).getString());
            if(ref instanceof FieldReference && ins.getOpcode().name().startsWith("IGET"))values.put(((OneRegisterInstruction)ins).getRegisterA(),ref);
            if(!(ref instanceof MethodReference))continue;
            MethodReference call=(MethodReference)ref; List<Integer> registers=DexEngine.registers(ins);
            if(call.getDefiningClass().equals(BUILDER)) {
                if((call.getName().equals("append") || call.getName().equals("<init>")) && registers.size()>1) sequence.add(values.getOrDefault(registers.get(1),UNKNOWN));
            } else if(!helper && helpers.containsKey(key(call))) {
                for(Object part:helpers.get(key(call)))sequence.add(part instanceof Integer && (Integer)part<registers.size()?values.getOrDefault(registers.get((Integer)part),UNKNOWN):part);
            } else if(helper)return null;
        }
        return sequence;
    }
}
