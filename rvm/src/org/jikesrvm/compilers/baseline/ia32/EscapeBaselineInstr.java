package org.jikesrvm.compilers.baseline.ia32;

import org.jikesrvm.ArchitectureSpecific.Assembler;
import org.jikesrvm.ArchitectureSpecific.BaselineConstants;
import org.jikesrvm.classloader.Context;
import org.jikesrvm.classloader.FieldReference;
import org.jikesrvm.classloader.NormalMethod;
import org.jikesrvm.classloader.RVMField;
import org.jikesrvm.classloader.TypeReference;
import org.jikesrvm.esc.Esc;
import org.jikesrvm.esc.EscInstrDecisions;
import org.jikesrvm.esc.EscapeState;
import org.jikesrvm.objectmodel.JavaHeader;
import org.jikesrvm.objectmodel.MiscHeader;
import org.jikesrvm.octet.Site;
import org.jikesrvm.runtime.Entrypoints;
import org.jikesrvm.runtime.Magic;
import org.jikesrvm.VM;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;

/** Should the barrier for DynEscape be before Octet barriers? What about for client analyses based on Octet? */
public class EscapeBaselineInstr implements BaselineConstants {

  /** Barrier for resolved static fields */
  boolean insertWriteBarrierStaticResolved(NormalMethod method, int biStart, RVMField field, Assembler asm) {
    if (shouldInstrument(method) &&
        Esc.shouldInstrumentFieldAccess(field.getMemberRef().asFieldReference()) &&
        field.isReferenceType()) {
      NormalMethod barrierMethod = EscInstrDecisions.chooseBarrier(true, true, true);
      // start and finish call
      int params = startWriteBarrierStaticResolvedCall(field, asm);
      finishCall(method, biStart, true, field.getMemberRef().asFieldReference(), null, null, barrierMethod, params, asm);
      return true;
    }
    return false;
  }

  int startWriteBarrierStaticResolvedCall(RVMField field, Assembler asm) {
    // param: righthand-side value to be stored
    asm.emitPUSH_RegInd(SP);
    // param: field offset
    asm.emitPUSH_Imm(field.getOffset().toInt());
    // param: field info
    int fieldInfo = EscInstrDecisions.getFieldInfo(field);
    asm.emitPUSH_Imm(fieldInfo);
    return 3;
  }

  /** Barrier for unresolved static fields */
  boolean insertWriteBarrierStaticUnresolved(NormalMethod method, int biStart, GPR offsetReg, FieldReference fieldRef, Assembler asm) {
    if (shouldInstrument(method) &&
        Esc.shouldInstrumentFieldAccess(fieldRef) &&
        fieldRef.getFieldContentsType().isReferenceType()) {
      // use the method for resolved static, since the offsetReg is already available 
      NormalMethod barrierMethod = EscInstrDecisions.chooseBarrier(true, true, true);
      // save offset value on stack
      asm.emitPUSH_Reg(offsetReg); // save value on the stack; also update where we'll find the reference
      // start and finish call      
      int params = startWriteBarrierStaticUnresolvedCall(offsetReg, fieldRef, asm);
      finishCall(method, biStart, true, fieldRef, null, offsetReg, barrierMethod, params, asm);
      // restore offset value from stack
      asm.emitPOP_Reg(offsetReg);
      return true;
    }
    return false;
  }

  int startWriteBarrierStaticUnresolvedCall(GPR offsetReg, FieldReference fieldRef, Assembler asm) {
    // param: righthand-side value to be stored -- add a slot because of the push above
    asm.emitPUSH_RegDisp(SP, BaselineCompilerImpl.ONE_SLOT);
    // param: field offset
    asm.emitPUSH_Reg(offsetReg);
    // param: field info (either field ID or field offset)
    if (EscInstrDecisions.passFieldInfo()) {
      if (EscInstrDecisions.useFieldOffset()) {
        asm.emitPUSH_Reg(offsetReg); // the offset is in a register
      } else {
        asm.emitPUSH_Imm(fieldRef.getId());
      }
    } else {
      asm.emitPUSH_Imm(0);
    }
    return 3;
  }
  
  /** Barrier for resolved non-static fields */
  boolean insertWriteBarrierFieldResolved(NormalMethod method, int biStart, Offset numSlots, RVMField field, Assembler asm) {
    if (shouldInstrument(method) &&
        Esc.shouldInstrumentFieldAccess(field.getMemberRef().asFieldReference())) {
      NormalMethod barrierMethod = EscInstrDecisions.chooseBarrier(true, true, false);
      // start and finish call      
      int params = startWriteBarrierFieldResolvedCall(numSlots, field, asm);
      finishCall(method, biStart, true, field.getMemberRef().asFieldReference(), null, null, barrierMethod, params, asm);
      return true;
    }
    return false;
  }

  int startWriteBarrierFieldResolvedCall(Offset numSlots, RVMField field, Assembler asm) {
    if (VM.VerifyAssertions) {
      VM._assert(numSlots.EQ(BaselineCompilerImpl.ONE_SLOT));
    }
    // param: righthand-side value to be stored
    asm.emitPUSH_RegInd(SP);
    // param: object reference
    asm.emitPUSH_RegDisp(SP, numSlots.plus(WORDSIZE));
    // param: field offset
    asm.emitPUSH_Imm(field.getOffset().toInt());
    // param: field info
    int fieldInfo = EscInstrDecisions.getFieldInfo(field);
    asm.emitPUSH_Imm(fieldInfo);
    return 4;
  }
  
  /** Barrier for unresolved non-static fields */
  boolean insertWriteBarrierFieldUnresolved(NormalMethod method, int biStart, Offset numSlots, GPR offsetReg, FieldReference fieldRef, Assembler asm) {
    if (shouldInstrument(method) &&
        Esc.shouldInstrumentFieldAccess(fieldRef)) {
      // use the method for resolved field, since the offsetReg is already available 
      NormalMethod barrierMethod = EscInstrDecisions.chooseBarrier(true, true, false);
      // save offset value on stack
      asm.emitPUSH_Reg(offsetReg);
      // start and finish call      
      int params = startWriteBarrierFieldUnresolvedCall(numSlots, offsetReg, fieldRef, asm);
      finishCall(method, biStart, true, fieldRef, null, offsetReg, barrierMethod, params, asm);
      // restore offset value from stack
      asm.emitPOP_Reg(offsetReg);
      return true;
    }
    return false;
  }

  int startWriteBarrierFieldUnresolvedCall(Offset numSlots, GPR offsetReg, FieldReference fieldRef, Assembler asm) {
    if (VM.VerifyAssertions) {
      VM._assert(numSlots.EQ(BaselineCompilerImpl.ONE_SLOT));
    }
    // param: righthand-side value to be stored -- add a slot because of the push above
    asm.emitPUSH_RegDisp(SP, BaselineCompilerImpl.ONE_SLOT);
    // param: object reference -- add a slot because of the push above
    asm.emitPUSH_RegDisp(SP, numSlots.plus(WORDSIZE*2));
    // param: field offset
    asm.emitPUSH_Reg(offsetReg);
    // param: field info (either field ID or field offset)
    if (EscInstrDecisions.passFieldInfo()) {
      if (EscInstrDecisions.useFieldOffset()) {
        asm.emitPUSH_Reg(offsetReg); // the offset is in a register
      } else {
        asm.emitPUSH_Imm(fieldRef.getId());
      }
    } else {
      asm.emitPUSH_Imm(0);
    }
    return 4;
  }
  
  /** Barrier for arrays */
  boolean insertArrayWriteBarrier(NormalMethod method, int biStart, Offset numSlots, TypeReference type, Assembler asm) {
    if (shouldInstrument(method)) {
      NormalMethod barrierMethod = EscInstrDecisions.chooseBarrier(false, true, false);
      // start and finish call
      int params = startArrayWriteBarrierCall(numSlots, asm);
      finishCall(method, biStart, false, null, type, null, barrierMethod, params, asm);
      return true;
    }
    return false;
  }

  int startArrayWriteBarrierCall(Offset numSlots, Assembler asm) {
    if (VM.VerifyAssertions) {
      VM._assert(numSlots.EQ(BaselineCompilerImpl.TWO_SLOTS));
    }
    // param: righthand-side value to be stored
    asm.emitPUSH_RegInd(SP);
    // param: object reference
    asm.emitPUSH_RegDisp(SP, numSlots.plus(WORDSIZE));
    // param: index
    if (EscInstrDecisions.passFieldInfo()) {
      asm.emitPUSH_RegDisp(SP, numSlots.plus(WORDSIZE)); // push index (which is now one lower)
    } else {
      asm.emitPUSH_Imm(0);
    }
    return 3;
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
  
  int passSite(NormalMethod method, int biStart, Assembler asm) {
    int siteID = EscInstrDecisions.passSite() ? Site.getSite(method, biStart, null, true) : 0;
    asm.emitPUSH_Imm(siteID);
    return 1;
  }
  
  int passExtra(NormalMethod method, int biStart, boolean isField, FieldReference fieldRef, TypeReference type, GPR offsetReg, Assembler asm) {
    return 0;
  }
  
  /** Helper to check if the method is a Library method */
  boolean isLibraries(NormalMethod method) {    
    return Context.isLibraryPrefix(method.getDeclaringClass().getTypeRef());
  }
  
  /** Helper method */
  boolean shouldInstrument(NormalMethod method) {
    return Esc.getConfig().escapeInsertBarriers() && Esc.getConfig().instrumentBaselineCompiler() && Esc.shouldInstrumentMethod(method);
  }
  
  /** Mark a newly-allocated object escaped */
  boolean markEscaped(NormalMethod method, int biStart, TypeReference type, Assembler asm) {
    if (//Esc.getConfig().escapeInsertBarriers() && Esc.getConfig().instrumentBaselineCompiler() &&
        Esc.getConfig().escapeVMContextEscape() && method.getStaticContext() == Context.VM_CONTEXT
        && EscInstrDecisions.needsEscape(type)) {
      asm.emitPUSH_Reg(T0);
      BaselineCompilerImpl.genParameterRegisterLoad(asm, 1);
      asm.emitCALL_Abs(Magic.getTocPointer().plus(Entrypoints.escapeMarkEscapedMethod.getOffset()));

      /*
      if (Esc.getConfig().escapeUseOneBitInGCHeader()) {
        asm.emitOR_RegDisp_Imm(T0, JavaHeader.ESCAPE_BIT_OFFSET, EscapeState.ESCAPE_BIT_MASK.toInt());
      } else {
        if (VM.VerifyAssertions) VM._assert(Esc.getConfig().escapeAddHeaderWord());
        // T0 must contain the object/array reference at this point
        asm.emitMOV_RegDisp_Imm(T0, MiscHeader.ESCAPE_OFFSET, EscapeState.getEscapeMetadata().toInt());
      }
      if (Esc.getConfig().escapeStats()) {
        asm.emitPUSH_Reg(T0);
        BaselineCompilerImpl.genParameterRegisterLoad(asm, 1);
        asm.emitCALL_Abs(Magic.getTocPointer().plus(Entrypoints.escapeIncStatsCounterMethod.getOffset()));
      }
      */
      return true;
    }
    return false;
  }
}
