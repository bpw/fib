package org.jikesrvm.compilers.baseline.ia32;

import org.jikesrvm.ArchitectureSpecific.Assembler;
import org.jikesrvm.ArchitectureSpecific.BaselineConstants;
import org.jikesrvm.classloader.Context;
import org.jikesrvm.classloader.FieldReference;
import org.jikesrvm.classloader.NormalMethod;
import org.jikesrvm.classloader.RVMField;
import org.jikesrvm.classloader.TypeReference;
import org.jikesrvm.dr.instrument.FieldTreatment;
import org.jikesrvm.octet.InstrDecisions;
import org.jikesrvm.octet.Octet;
import org.jikesrvm.octet.Site;
import org.jikesrvm.runtime.Entrypoints;
import org.jikesrvm.runtime.Magic;
import org.vmmagic.unboxed.Offset;

public class OctetBaselineInstr implements BaselineConstants {

  /** Barrier for resolved static fields */
  boolean insertStaticBarrierResolved(NormalMethod method, int biStart, boolean isRead, RVMField field, Assembler asm) {
    if (shouldInstrument(method, biStart) && InstrDecisions.staticFieldHasMetadata(field)) {
      NormalMethod barrierMethod = Octet.getClientAnalysis().chooseBarrier(method, isRead, true, true, true, false, isSpecializedMethod(method));
      // start and finish call      
      int params = startStaticBarrierResolvedCall(field, asm);
      finishCall(method, biStart, true, field.getMemberRef().asFieldReference(), null, null, barrierMethod, params, asm);
      return true;
    }
    return false;
  }

  int startStaticBarrierResolvedCall(RVMField field, Assembler asm) {
    // param: metadata offset
    asm.emitPUSH_Imm(field.getMetadataOffset().toInt()); // push field metadata offset
    // param: field info
    int fieldInfo = InstrDecisions.getFieldInfo(field);
    asm.emitPUSH_Imm(fieldInfo);
    return 2;
  }

  /** Barrier for unresolved static fields */
  boolean insertStaticBarrierUnresolved(NormalMethod method, int biStart, boolean isRead, GPR offsetReg, FieldReference fieldRef, Assembler asm) {
    if (shouldInstrument(method, biStart) && InstrDecisions.staticFieldMightHaveMetadata(fieldRef)) {
      NormalMethod barrierMethod = Octet.getClientAnalysis().chooseBarrier(method, isRead, true, false, true, false, isSpecializedMethod(method));
      // save offset value on stack
      asm.emitPUSH_Reg(offsetReg); // save value on the stack; also update where we'll find the reference
      // start and finish call      
      int params = startStaticBarrierUnresolvedCall(offsetReg, fieldRef, asm);
      finishCall(method, biStart, true, fieldRef, null, offsetReg, barrierMethod, params, asm);
      // restore offset value from stack
      asm.emitPOP_Reg(offsetReg);
      return true;
    }
    return false;
  }

  int startStaticBarrierUnresolvedCall(GPR offsetReg, FieldReference fieldRef, Assembler asm) {
    // param: field ID (needed even if field info disabled, since it's needed to get the metadata offset)
    asm.emitPUSH_Imm(fieldRef.getId());
    return 1;
  }
  
  /** Barrier for resolved non-static fields */
  boolean insertFieldBarrierResolved(NormalMethod method, int biStart, boolean isRead, Offset numSlots, RVMField field, Assembler asm) {
    if (shouldInstrument(method, biStart) &&
        InstrDecisions.objectOrFieldHasMetadata(field) &&
        Octet.shouldInstrumentFieldAccess(field.getMemberRef().asFieldReference())) {
      NormalMethod barrierMethod = Octet.getClientAnalysis().chooseBarrier(method, isRead, true, true, false, false, isSpecializedMethod(method));
      // start and finish call      
      int params = startFieldBarrierResolvedCall(numSlots, field, asm);
      finishCall(method, biStart, true, field.getMemberRef().asFieldReference(), null, null, barrierMethod, params, asm);
      return true;
    }
    return false;
  }

  int startFieldBarrierResolvedCall(Offset numSlots, RVMField field, Assembler asm) {
    // param: object reference
    asm.emitPUSH_RegDisp(SP, numSlots);
    // param: field info
    int fieldInfo = InstrDecisions.getFieldInfo(field);
    asm.emitPUSH_Imm(fieldInfo);
    return 2;
  }
  
  /** Barrier for unresolved non-static fields */
  boolean insertFieldBarrierUnresolved(NormalMethod method, int biStart, boolean isRead, Offset numSlots, GPR offsetReg, FieldReference fieldRef, Assembler asm) {
    if (shouldInstrument(method, biStart) &&
        InstrDecisions.objectOrFieldMightHaveMetadata(fieldRef) &&
        Octet.shouldInstrumentFieldAccess(fieldRef)) {
      // we can just send isResolved==true because we can get the offset out of the register "offsetReg"
      NormalMethod barrierMethod = Octet.getClientAnalysis().chooseBarrier(method, isRead, true, true, false, false, isSpecializedMethod(method));
      // save offset value on stack
      asm.emitPUSH_Reg(offsetReg);
      // start and finish call      
      int params = startFieldBarrierUnresolvedCall(numSlots, offsetReg, fieldRef, asm);
      finishCall(method, biStart, true, fieldRef, null, offsetReg, barrierMethod, params, asm);
      // restore offset value from stack
      asm.emitPOP_Reg(offsetReg);
      return true;
    }
    return false;
  }

  int startFieldBarrierUnresolvedCall(Offset numSlots, GPR offsetReg, FieldReference fieldRef, Assembler asm) {
    // param: object reference -- add a slot because of the push above
    asm.emitPUSH_RegDisp(SP, numSlots.plus(WORDSIZE));
    // param: field info (either field ID or field offset)
    if (InstrDecisions.passFieldInfo()) {
      if (InstrDecisions.useFieldOffset()) {
        asm.emitPUSH_Reg(offsetReg); // the offset is in a register
      } else {
        asm.emitPUSH_Imm(fieldRef.getId());
      }
    } else {
      asm.emitPUSH_Imm(0);
    }
    return 2;
  }
  
  /** Barrier for arrays */
  boolean insertArrayBarrier(NormalMethod method, int biStart, boolean isRead, Offset numSlots, TypeReference type, Assembler asm) {
    if (shouldInstrument(method, biStart)) {
      NormalMethod barrierMethod = Octet.getClientAnalysis().chooseBarrier(method, isRead, false, true, false, false, isSpecializedMethod(method));
      // start and finish call
      int params = startArrayBarrierCall(numSlots, asm);
      finishCall(method, biStart, false, null, type, null, barrierMethod, params, asm);
      return true;
    }
    return false;
  }

  int startArrayBarrierCall(Offset numSlots, Assembler asm) {
    // param: object reference
    asm.emitPUSH_RegDisp(SP, numSlots);
    // param: index
    if (InstrDecisions.passFieldInfo()) {
      asm.emitPUSH_RegDisp(SP, numSlots); // push index (which is now one lower)
    } else {
      asm.emitPUSH_Imm(0);
    }
    return 2;
  }
  
  /** Helper method for passing final parameters and actually making the call */
  void finishCall(NormalMethod method, int biStart, boolean isField, FieldReference fieldRef, TypeReference type, GPR offsetReg, NormalMethod barrierMethod, int params, Assembler asm) {
    // pass site info
    params += passSite(method, biStart, asm);
    // pass extra info specified by a client analysis
    params += passExtra(method, biStart, isField, fieldRef, type, offsetReg, asm);
    // make the call
    BaselineCompilerImpl.genParameterRegisterLoad(asm, params);
    asm.emitCALL_Abs(Magic.getTocPointer().plus(barrierMethod.getOffset()));
  }
  
  /** A client analysis can override this to do nothing. */
  int passSite(NormalMethod method, int biStart, Assembler asm) {
    int siteID = InstrDecisions.passSite() ? Site.getSite(method, biStart, null) : 0;
    asm.emitPUSH_Imm(siteID);
    return 1;
  }
  
  /** A client analysis can override this to pass extra parameters. */
  int passExtra(NormalMethod method, int biStart, boolean isField, FieldReference fieldRef, TypeReference type, GPR offsetReg, Assembler asm) {
    return 0;
  }

  /** Helper method */
  boolean shouldInstrument(NormalMethod method, int bci) {
    return shouldInstrument(method) && (!Octet.getConfig().enableStaticRaceDetection() || isLibraries(method) || Octet.shouldInstrumentCheckRace(method, bci));
  }
  
  /** Helper to check if the method is a Library method */
  boolean isLibraries(NormalMethod method) {    
    return Context.isLibraryPrefix(method.getDeclaringClass().getTypeRef());
  }
  
  /** Helper method */
  boolean shouldInstrument(NormalMethod method) {
    return Octet.getConfig().insertBarriers() && Octet.getConfig().instrumentBaselineCompiler() && Octet.shouldInstrumentMethod(method);
  }
  
  boolean isSpecializedMethod(NormalMethod method) {
    return Octet.getClientAnalysis().isSpecializedMethod(method);
  }
  
  // FIB: optional post-barriers.
  boolean insertStaticPostBarrierResolved(NormalMethod method, int biStart, boolean isRead, RVMField field, Assembler asm) {
    return false;
  }
  boolean insertStaticPostBarrierUnresolved(NormalMethod method, int biStart, boolean isRead, FieldReference fieldRef, Assembler asm) {
    return false;
  }
  boolean insertFieldPostBarrierResolved(NormalMethod method, int biStart, boolean isRead, RVMField field, Assembler asm) {
    return false;
  }
  boolean insertFieldPostBarrierUnresolved(NormalMethod method, int biStart, boolean isRead, FieldReference fieldRef, Assembler asm) {
    return false;
  }


}
