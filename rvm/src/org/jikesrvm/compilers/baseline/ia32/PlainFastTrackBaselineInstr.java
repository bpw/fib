package org.jikesrvm.compilers.baseline.ia32;

import org.jikesrvm.ArchitectureSpecific.Assembler;
import org.jikesrvm.classloader.FieldReference;
import org.jikesrvm.classloader.NormalMethod;
import org.jikesrvm.classloader.RVMField;
import org.jikesrvm.classloader.TypeReference;
import org.jikesrvm.dr.Dr;
import org.jikesrvm.dr.DrRuntime;
import org.jikesrvm.dr.DrStats;
import org.jikesrvm.dr.instrument.FieldTreatment;
import org.jikesrvm.octet.Octet;
import org.jikesrvm.runtime.Entrypoints;
import org.jikesrvm.runtime.Magic;
import org.vmmagic.unboxed.Offset;

public class PlainFastTrackBaselineInstr extends OctetBaselineInstr {
  
  /** Barrier for resolved static fields */
  @Override
  boolean insertStaticBarrierResolved(NormalMethod method, int biStart, boolean isRead, RVMField field, Assembler asm) {
        
    if (Octet.shouldInstrumentMethod(method)) {
      if (FieldTreatment.check(field)) {
        NormalMethod barrierMethod = Octet.getClientAnalysis().chooseBarrier(method, isRead, true, true, true, false, isSpecializedMethod(method));
        // push access history offset from zero (not JTOC)
        asm.emitPUSH_Imm(field.getDrOffset().toInt());

        if (barrierMethod == Entrypoints.drGetStaticResolvedObserveInitMethod) {
          asm.emitPUSH_Imm(field.getDeclaringClass().getId());
          BaselineCompilerImpl.genParameterRegisterLoad(asm, 2);
        } else {
          BaselineCompilerImpl.genParameterRegisterLoad(asm, 1);
        }
        asm.emitCALL_Abs(Magic.getTocPointer().plus(barrierMethod.getOffset()));
        if (Dr.STATS) DrStats.baselineStaticBarrierResolvedYes.inc();
        return true;
      } else if (FieldTreatment.vol(field)) {
        NormalMethod barrierMethod = 
            (isRead ? Entrypoints.drVolatileStaticReadResolvedMethod : Entrypoints.drVolatileWriteResolvedMethod);
        if (isRead) {
          // push classID
          asm.emitPUSH_Imm(field.getDeclaringClass().getId());
        } else {
          // push null
          asm.emitPUSH_Imm(0);
        }
        // push access history offset from zero (not JTOC)
        asm.emitPUSH_Imm(field.getDrOffset().toInt());

        BaselineCompilerImpl.genParameterRegisterLoad(asm, 2);
        asm.emitCALL_Abs(Magic.getTocPointer().plus(barrierMethod.getOffset()));
        if (Dr.STATS) DrStats.baselineStaticBarrierResolvedYes.inc();        
        return true;
      }
    }
    if (Dr.STATS) DrStats.baselineStaticBarrierResolvedNo.inc();
    return false;
  }

  /** Barrier for unresolved static fields */
  @Override
  boolean insertStaticBarrierUnresolved(NormalMethod method, int biStart, boolean isRead, GPR offsetReg, FieldReference fieldRef, Assembler asm) {
    if (Octet.shouldInstrumentMethod(method) && FieldTreatment.maybeCheckOrSync(fieldRef)) {
      NormalMethod barrierMethod = Octet.getClientAnalysis().chooseBarrier(method, isRead, true, false, true, false, isSpecializedMethod(method));
      // save offset value on stack
      asm.emitPUSH_Reg(offsetReg);
      // push field ID
      asm.emitPUSH_Imm(fieldRef.getId());
      // start and finish call      
      BaselineCompilerImpl.genParameterRegisterLoad(asm, 1);
      asm.emitCALL_Abs(Magic.getTocPointer().plus(barrierMethod.getOffset()));
      // restore offset value from stack
      asm.emitPOP_Reg(offsetReg);
      if (Dr.STATS) DrStats.baselineStaticBarrierUnresolvedYes.inc();
      return true;
    }
    if (Dr.STATS) DrStats.baselineStaticBarrierUnresolvedNo.inc();
    return false;
  }
  
  /** Barrier for resolved non-static fields */
  @Override
  boolean insertFieldBarrierResolved(NormalMethod method, int biStart, boolean isRead, Offset numSlots, RVMField field, Assembler asm) {
    if (Octet.shouldInstrumentMethod(method)) {
      if (FieldTreatment.check(field)) {
        NormalMethod barrierMethod = Octet.getClientAnalysis().chooseBarrier(method, isRead, true, true, false, false, isSpecializedMethod(method));
        // param: object reference
        asm.emitPUSH_RegDisp(SP, numSlots);
        // param: access history offset
        asm.emitPUSH_Imm(field.getDrOffset().toInt());
        // start and finish call      
        BaselineCompilerImpl.genParameterRegisterLoad(asm, 2);
        asm.emitCALL_Abs(Magic.getTocPointer().plus(barrierMethod.getOffset()));
        if (Dr.STATS) DrStats.baselineFieldBarrierResolvedYes.inc();
        return true;
      } else if (FieldTreatment.vol(field)) {
        NormalMethod barrierMethod = 
            (isRead ? Entrypoints.drVolatileReadResolvedMethod : Entrypoints.drVolatileWriteResolvedMethod);
        // param: object reference
        asm.emitPUSH_RegDisp(SP, numSlots);
        // param: access history offset from zero (not JTOC)
        asm.emitPUSH_Imm(field.getDrOffset().toInt());

        BaselineCompilerImpl.genParameterRegisterLoad(asm, 2);
        asm.emitCALL_Abs(Magic.getTocPointer().plus(barrierMethod.getOffset()));
        
        // Patch stack so object is around for post barrier after access
        if (isRead) {
          asm.emitPUSH_RegInd(SP);
        } else if (field.getNumberOfStackSlots() == 1) {
          asm.emitPOP_Reg(T0);
          asm.emitPUSH_RegInd(SP);
          asm.emitPUSH_Reg(T0);
        } else { // TWO_SLOTS
          asm.emitPOP_Reg(T0);
          asm.emitPOP_Reg(S0);
          asm.emitPUSH_RegInd(SP);
          asm.emitPUSH_Reg(S0);          
          asm.emitPUSH_Reg(T0);          
        }
        if (Dr.STATS) DrStats.baselineStaticBarrierResolvedYes.inc();        
        return true;
      }
    }
    if (Dr.STATS) DrStats.baselineFieldBarrierResolvedNo.inc();
    return false;
  }

  /** Barrier for unresolved non-static fields */
  @Override
  boolean insertFieldBarrierUnresolved(NormalMethod method, int biStart, boolean isRead, Offset numSlots, GPR offsetReg, FieldReference fieldRef, Assembler asm) {
    if (Octet.shouldInstrumentMethod(method) && FieldTreatment.maybeCheckOrSync(fieldRef)) {
      // we can just send isResolved==true because we can get the offset out of the register "reg"
      NormalMethod barrierMethod = Octet.getClientAnalysis().chooseBarrier(method, isRead, true, false, false, false, isSpecializedMethod(method));
      // save offset value on stack
      asm.emitPUSH_Reg(offsetReg);
      // param: object reference
      asm.emitPUSH_RegDisp(SP, numSlots.plus(WORDSIZE));
      // param: field ID
      asm.emitPUSH_Imm(fieldRef.getId());

      // start and finish call      
      BaselineCompilerImpl.genParameterRegisterLoad(asm, 2);
      asm.emitCALL_Abs(Magic.getTocPointer().plus(barrierMethod.getOffset()));
      // restore offset value from stack
      asm.emitPOP_Reg(offsetReg);
      
      // Patch stack so object is around for post barrier after access
      GPR tempReg = offsetReg == T0 ? T1 : T0;
      if (isRead) {
        asm.emitPUSH_RegInd(SP);
      } else if (fieldRef.getNumberOfStackSlots() == 1) {
        asm.emitPOP_Reg(tempReg);
        asm.emitPUSH_RegInd(SP);
        asm.emitPUSH_Reg(tempReg);
      } else { // TWO_SLOTS
        asm.emitPOP_Reg(tempReg);
        asm.emitPOP_Reg(S0);
        asm.emitPUSH_RegInd(SP);
        asm.emitPUSH_Reg(S0);          
        asm.emitPUSH_Reg(tempReg);          
      }
      if (Dr.STATS) DrStats.baselineFieldBarrierUnresolvedYes.inc();
      return true;
    }
    if (Dr.STATS) DrStats.baselineFieldBarrierUnresolvedNo.inc();
    return false;
  }
  
  /** Barrier for arrays */
  @Override
  boolean insertArrayBarrier(NormalMethod method, int biStart, boolean isRead, Offset numSlots, TypeReference type, Assembler asm) {
    if (Octet.shouldInstrumentMethod(method)) {
      NormalMethod barrierMethod = Octet.getClientAnalysis().chooseBarrier(method, isRead, false, true, false, false, isSpecializedMethod(method));
      // param: object reference
      asm.emitPUSH_RegDisp(SP, numSlots);
      // param: index
      asm.emitPUSH_RegDisp(SP, numSlots); // push index (SP is now one slot lower)

      // start and finish call      
      BaselineCompilerImpl.genParameterRegisterLoad(asm, 2);
      asm.emitCALL_Abs(Magic.getTocPointer().plus(barrierMethod.getOffset()));
      if (Dr.STATS) DrStats.baselineArrayBarrierYes.inc();
      return true;
    }
    if (Dr.STATS) DrStats.baselineArrayBarrierNo.inc();
    return false;
  }

  // FIB: optional post-barriers.
  @Override
  boolean insertStaticPostBarrierResolved(NormalMethod method, int biStart, boolean isRead, RVMField field, Assembler asm) {
    if (Octet.shouldInstrumentMethod(method)) {
      if (FieldTreatment.vol(field)) {
        NormalMethod barrierMethod =
            (isRead ? Entrypoints.drPostVolatileReadResolvedMethod : Entrypoints.drPostVolatileWriteResolvedMethod);
        // push null
        asm.emitPUSH_Imm(0);
        // push access history offset from zero (not JTOC)
        asm.emitPUSH_Imm(field.getDrOffset().toInt());

        BaselineCompilerImpl.genParameterRegisterLoad(asm, 2);
        asm.emitCALL_Abs(Magic.getTocPointer().plus(barrierMethod.getOffset()));
        return true;
      } else if (isRead && FieldTreatment.staticFinal(field) && !DrRuntime.initHappensBeforeAll(field.getDeclaringClass())) {
//        if (Dr.STATS) DrStats.staticFinalReadInstrs.inc();
        // TODO: if all live fib threads have already observed the relevant class init, elide this.
        // push class
        asm.emitPUSH_Imm(field.getDeclaringClass().getId());
        // call.
        BaselineCompilerImpl.genParameterRegisterLoad(asm, 1);
        asm.emitCALL_Abs(Magic.getTocPointer().plus(Entrypoints.drPostGetStaticFinalResolvedMethod.getOffset()));
      }
    }
    return false;
  }
  @Override
  boolean insertStaticPostBarrierUnresolved(NormalMethod method, int biStart, boolean isRead, FieldReference fieldRef, Assembler asm) {
    if (Octet.shouldInstrumentMethod(method) && FieldTreatment.maybeCheckOrSync(fieldRef)) {
      NormalMethod barrierMethod =
          (isRead ? Entrypoints.drPostReadUnresolvedMethod : Entrypoints.drPostWriteUnresolvedMethod);
      // push null
      asm.emitPUSH_Imm(0);
      // push the field reference id
      asm.emitPUSH_Imm(fieldRef.getId());
      
      BaselineCompilerImpl.genParameterRegisterLoad(asm, 2);
      asm.emitCALL_Abs(Magic.getTocPointer().plus(barrierMethod.getOffset()));
      return true;
    }
    return false;
  }
  @Override
  boolean insertFieldPostBarrierResolved(NormalMethod method, int biStart, boolean isRead, RVMField field, Assembler asm) {
    if (Octet.shouldInstrumentMethod(method) && FieldTreatment.vol(field)) {
      NormalMethod barrierMethod =
          (isRead ? Entrypoints.drPostVolatileReadResolvedMethod : Entrypoints.drPostVolatileWriteResolvedMethod);
      // param: object reference, out from under read result
      if (isRead) {
        if (field.getNumberOfStackSlots() == 1) {
          asm.emitPOP_Reg(T0);
          asm.emitPOP_Reg(S0);
          asm.emitPUSH_Reg(T0);
          asm.emitPUSH_Reg(S0);
        } else {
          asm.emitPOP_Reg(T0);
          asm.emitPOP_Reg(S0);
          asm.emitPOP_Reg(T1);
          asm.emitPUSH_Reg(S0);        
          asm.emitPUSH_Reg(T0);
          asm.emitPUSH_Reg(T1);
        }
      }
      // param: access history offset
      asm.emitPUSH_Imm(field.getDrOffset().toInt());
      
      BaselineCompilerImpl.genParameterRegisterLoad(asm, 2);
      asm.emitCALL_Abs(Magic.getTocPointer().plus(barrierMethod.getOffset()));
      return true;
    }
    return false;
  }
  @Override
  boolean insertFieldPostBarrierUnresolved(NormalMethod method, int biStart, boolean isRead, FieldReference fieldRef, Assembler asm) {
    if (Octet.shouldInstrumentMethod(method) && FieldTreatment.maybeCheckOrSync(fieldRef)) {
      NormalMethod barrierMethod =
          (isRead ? Entrypoints.drPostReadUnresolvedMethod : Entrypoints.drPostWriteUnresolvedMethod);
      // param: object reference
      if (isRead) {
        if (fieldRef.getNumberOfStackSlots() == 1) {
          asm.emitPOP_Reg(T0);
          asm.emitPOP_Reg(S0);
          asm.emitPUSH_Reg(T0);
          asm.emitPUSH_Reg(S0);
        } else {
          asm.emitPOP_Reg(T0);
          asm.emitPOP_Reg(S0);
          asm.emitPOP_Reg(T1);
          asm.emitPUSH_Reg(S0);        
          asm.emitPUSH_Reg(T0);
          asm.emitPUSH_Reg(T1);
        }
      }
      // param: field reference id
      asm.emitPUSH_Imm(fieldRef.getId());
      
      BaselineCompilerImpl.genParameterRegisterLoad(asm, 2);
      asm.emitCALL_Abs(Magic.getTocPointer().plus(barrierMethod.getOffset()));
      return true;
    }
    return false;
  }

}
