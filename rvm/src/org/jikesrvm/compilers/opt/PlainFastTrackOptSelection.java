package org.jikesrvm.compilers.opt;

import org.jikesrvm.classloader.FieldReference;
import org.jikesrvm.classloader.RVMField;
import org.jikesrvm.compilers.opt.escape.FI_EscapeSummary;
import org.jikesrvm.compilers.opt.ir.GetField;
import org.jikesrvm.compilers.opt.ir.GetStatic;
import org.jikesrvm.compilers.opt.ir.Instruction;
import org.jikesrvm.compilers.opt.ir.PutField;
import org.jikesrvm.compilers.opt.ir.PutStatic;
import org.jikesrvm.compilers.opt.ir.operand.LocationOperand;
import org.jikesrvm.compilers.opt.ir.operand.Operand;
import org.jikesrvm.dr.DrRuntime;
import org.jikesrvm.dr.instrument.FieldTreatment;
import org.jikesrvm.octet.Octet;

/** FIB: decide which reads/writes should be instrumented */
public class PlainFastTrackOptSelection extends OctetOptSelection {

  @Override
  public String getName() {
    return "FIB simple read and write selection";
  }

  public PlainFastTrackOptSelection() {
  }

  
  @Override
  boolean shouldInstrumentScalarAccess(Instruction inst, FI_EscapeSummary escapeSummary) {
    // are we early or late in the compilation process?  use a different strategy in each case
    boolean isRead = GetField.conforms(inst);
    LocationOperand loc = isRead ? GetField.getLocation(inst)  : PutField.getLocation(inst);
    Operand ref = isRead ? GetField.getRef(inst) : PutField.getRef(inst);
    FieldReference fieldRef = loc.getFieldRef();
    RVMField field = fieldRef.peekResolvedField();
    boolean isResolved = (field != null);
    boolean mightHaveMetadata;
    if (isResolved) {
      mightHaveMetadata = FieldTreatment.check(field) || FieldTreatment.vol(field); // InstrDecisions.objectOrFieldHasMetadata(field);
    } else {
      mightHaveMetadata = FieldTreatment.maybeCheckOrSync(fieldRef); // InstrDecisions.objectOrFieldMightHaveMetadata(fieldRef);
    }
    if (mightHaveMetadata &&
        Octet.shouldInstrumentFieldAccess(fieldRef)) {
      if (mightEscape(ref, escapeSummary)) {
        return true;
      } else {
        // Helps with debugging
        // inst.markThreadLocal();
      }
    }
    return false;
  }
  
  @Override
  boolean shouldInstrumentStaticAccess(Instruction inst) {
    boolean isRead = GetStatic.conforms(inst);
    LocationOperand loc = isRead ? GetStatic.getLocation(inst) : PutStatic.getLocation(inst);
    FieldReference fieldRef = loc.getFieldRef();
    RVMField field = fieldRef.peekResolvedField();
    boolean isResolved = (field != null);
    if (isResolved) {
      return FieldTreatment.check(field) || FieldTreatment.vol(field)
          || (FieldTreatment.staticFinal(field) && !DrRuntime.initHappensBeforeAll(field.getDeclaringClass())); // InstrDecisions.staticFieldHasMetadata(field);
    } else {
      return FieldTreatment.maybeCheckOrSync(fieldRef); // InstrDecisions.staticFieldMightHaveMetadata(fieldRef);
    }
  }
  
//  public static boolean shouldInstrumentInstPosition(Instruction inst, IR ir) {
//    boolean instrumentInst = inst.position == null ? false : Octet.shouldInstrumentMethod(inst.position.getMethod());
//    boolean instrumentMethod = FibTreatment.instrument(ir.getMethod()); //Octet.shouldInstrumentMethod(ir.getMethod());
//
//    // Octet: TODO: Figure this out!  Might need to instrument more or fewer classes.
//    // A class like System or String doesn't seem to need Octet instrumentation, but what about stuff inlined into it?
//    // Actually, System probably needs Octet instrumentation, e.g., for array copy.
//    // If a library method gets inlined into a VM method, then we probably don't need to instrument the library method.
//    
//    /*
//    if (instrumentInst && !instrumentMethod) {
//      System.out.println("Case A: Instruction in method " + ir.getMethod() + " is instrumented but method wouldn't be:");
//      System.out.println(inst);
//      System.out.println("  inst.position: " + inst.position);
//      System.out.println();
//    }
//    if (!instrumentInst && instrumentMethod) {
//      System.out.println("Case B: Instruction in method " + ir.getMethod() + " isn't instrumented but method would be:");
//      System.out.println(inst);
//      System.out.println("  inst.position: " + inst.position);
//      System.out.println();
//    }
//    */
//    
//    // Octet: TODO: Need to make sure that what's getting instrumented is still reasonable.
//    // VM instrumentation in application/library methods isn't necessarily being ignored!
//    
//    // Octet: TODO: not sure about this policy
//    return instrumentInst && instrumentMethod;
//  }
//  
}
