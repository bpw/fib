package org.jikesrvm.compilers.opt;

import static org.jikesrvm.compilers.opt.ir.Operators.CALL;

import org.jikesrvm.VM;
import org.jikesrvm.classloader.FieldReference;
import org.jikesrvm.classloader.NormalMethod;
import org.jikesrvm.classloader.RVMField;
import org.jikesrvm.compilers.opt.driver.CompilerPhase;
import org.jikesrvm.compilers.opt.ir.ALoad;
import org.jikesrvm.compilers.opt.ir.AStore;
import org.jikesrvm.compilers.opt.ir.Call;
import org.jikesrvm.compilers.opt.ir.GetField;
import org.jikesrvm.compilers.opt.ir.GetStatic;
import org.jikesrvm.compilers.opt.ir.IR;
import org.jikesrvm.compilers.opt.ir.IRTools;
import org.jikesrvm.compilers.opt.ir.Instruction;
import org.jikesrvm.compilers.opt.ir.Load;
import org.jikesrvm.compilers.opt.ir.PutField;
import org.jikesrvm.compilers.opt.ir.PutStatic;
import org.jikesrvm.compilers.opt.ir.Store;
import org.jikesrvm.compilers.opt.ir.operand.LocationOperand;
import org.jikesrvm.compilers.opt.ir.operand.MethodOperand;
import org.jikesrvm.compilers.opt.ir.operand.NullConstantOperand;
import org.jikesrvm.compilers.opt.ir.operand.Operand;
import org.jikesrvm.dr.Dr;
import org.jikesrvm.dr.DrRuntime;
import org.jikesrvm.dr.DrStats;
import org.jikesrvm.dr.instrument.FieldTreatment;
import org.jikesrvm.octet.InstrDecisions;
import org.jikesrvm.octet.Octet;
import org.jikesrvm.runtime.Entrypoints;
import org.jikesrvm.runtime.Magic;
import org.jikesrvm.util.HashSetRVM;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;


public class PlainFastTrackOptInstr extends OctetOptInstr {

  @Override
  public String getName() {
    return "Plain FastTrack instrumentation";
  }

  public PlainFastTrackOptInstr(boolean lateInstr, RedundantBarrierRemover redundantBarrierRemover) {
    super(lateInstr, redundantBarrierRemover);
  }
  



  /** Pass site and/or other client-specific parameters */
  @Override
  void finishParams(Instruction inst, FieldReference fieldRef, Instruction barrier) {
//    passSite(inst, barrier);
//    passExtra(inst, fieldRef, barrier);
  }

  /** Client analyses can override this to add more parameters */
  @Override
  void passExtra(Instruction inst, FieldReference fieldRef, Instruction barrier) {
  }

  /** Client analyses can override this to provide customized field offset*/
  @Override
  protected int getFieldOffset(RVMField field) {
    // we'll just slide in our own offset here.
    return field.getDrOffset().toInt();
  }


  /**
   * Copied from OctetOptInstr: I want to pass an offset not an int...
   */
  @Override
  void instrumentScalarAccess(Instruction inst, HashSetRVM<Instruction> callsToInline, IR ir) {
    boolean isRead;
    LocationOperand loc;
    Operand ref = null;
    if (!lateInstr) {
      isRead = GetField.conforms(inst);
      loc = isRead ? GetField.getLocation(inst) : PutField.getLocation(inst);
      ref = isRead ? GetField.getRef(inst) : PutField.getRef(inst);
    } else {
      isRead = Load.conforms(inst);
      loc = isRead ? Load.getLocation(inst) : Store.getLocation(inst);
      if (VM.VerifyAssertions) { VM._assert(loc != null && loc.isFieldAccess()); }
      Operand tempRef = isRead ? Load.getAddress(inst) : Store.getAddress(inst);
      if (VM.VerifyAssertions) { VM._assert(!(tempRef.isIntConstant() && tempRef.asIntConstant().value == Magic.getTocPointer().toInt())); }
      ref = tempRef;
    }
    FieldReference fieldRef = loc.getFieldRef();
    RVMField field = fieldRef.peekResolvedField();
    boolean isResolved = (field != null);
    if (VM.VerifyAssertions && isResolved) { VM._assert(!field.isStatic()); } // FIB needs that too.
        
    { // Scope to make sure I get the write post-barriers later...
      NormalMethod barrierMethod = isResolved && FieldTreatment.vol(field)
          ? (isRead ? Entrypoints.drVolatileReadResolvedMethod : Entrypoints.drVolatileWriteResolvedMethod)
              : Octet.getClientAnalysis().chooseBarrier(ir.getMethod(), isRead, true, isResolved, false, inst.hasRedundantBarrier(), isSpecializedMethod(ir));

          Instruction barrierCall = Call.create2(CALL,
              null,
              IRTools.AC(barrierMethod.getOffset()),
              MethodOperand.STATIC(barrierMethod),
              ref.copy(),
              isResolved ? IRTools.AC(field.getDrOffset()) : IRTools.IC(fieldRef.getId()));

          barrierCall.position = inst.position;
          barrierCall.bcIndex = inst.bcIndex;
          // Octet: LATER: try this
          /*
          if (!Octet.getClientAnalysis().barriersCanThrowExceptions()) {
            barrierCall.markAsNonPEI();
          }
           */
          finishParams(inst, fieldRef, barrierCall);
          insertBarrier(barrierCall, inst, isRead, ref, field, isResolved, callsToInline, ir, true);
    }
    
    // FIB: volatile and unresolved post-barriers.
    Instruction postBarrierCall;
    if (!isResolved) {
      NormalMethod postBarrierMethod =
          isRead ? Entrypoints.drPostReadUnresolvedMethod : Entrypoints.drPostWriteUnresolvedMethod;

      postBarrierCall = Call.create2(CALL,
          null,
          IRTools.AC(postBarrierMethod.getOffset()),
          MethodOperand.STATIC(postBarrierMethod),
          ref.copy(),
          IRTools.IC(fieldRef.getId()));

    } else if (FieldTreatment.vol(field)) {
      NormalMethod postBarrierMethod =
          isRead ? Entrypoints.drPostVolatileReadResolvedMethod : Entrypoints.drPostVolatileWriteResolvedMethod;
      
      postBarrierCall = Call.create2(CALL,
          null,
          IRTools.AC(postBarrierMethod.getOffset()),
          MethodOperand.STATIC(postBarrierMethod),
          ref.copy(),
          IRTools.AC(field.getDrOffset()));

    } else {
      return;
    }

    postBarrierCall.position = inst.position;
    postBarrierCall.bcIndex = inst.bcIndex;
    // TODO: do I need to mark anything to prevent reordering of the access and the post-barrier?
    insertBarrier(postBarrierCall, inst.nextInstructionInCodeOrder(), isRead, ref, field, isResolved, callsToInline, ir, false);
  }

  
  /**
   * Copied from OctetOptInstr, but I don't want FieldInfo on a static access.
   */
  @Override
  void instrumentStaticAccess(Instruction inst, HashSetRVM<Instruction> callsToInline, IR ir) {
    boolean isRead;
    LocationOperand loc;
    if (!lateInstr) {
      isRead = GetStatic.conforms(inst);
      loc = isRead ? GetStatic.getLocation(inst) : PutStatic.getLocation(inst);
    } else {
      isRead = Load.conforms(inst);
      loc = isRead ? Load.getLocation(inst) : Store.getLocation(inst);
      if (VM.VerifyAssertions) { VM._assert(loc.isFieldAccess()); }
      Operand tempRef = isRead ? Load.getAddress(inst) : Store.getAddress(inst);
      if (VM.VerifyAssertions) { VM._assert(tempRef.isIntConstant() && tempRef.asIntConstant().value == Magic.getTocPointer().toInt()); }
    }
    FieldReference fieldRef = loc.getFieldRef();
    RVMField field = fieldRef.peekResolvedField();
    boolean isResolved = (field != null);
    if (VM.VerifyAssertions && isResolved) { VM._assert(field.isStatic()); }

    if (!isResolved || !FieldTreatment.staticFinal(field)) { // Scope to make sure I get the right barrier below.
      Instruction barrierCall;
      // FIB: resolved volatiles get different barriers.
      if (isResolved && FieldTreatment.vol(field)) {
        NormalMethod barrierMethod =
            (isRead ? Entrypoints.drVolatileStaticReadResolvedMethod : Entrypoints.drVolatileWriteResolvedMethod);

        barrierCall = Call.create2(CALL,
            null,
            IRTools.AC(barrierMethod.getOffset()),
            MethodOperand.STATIC(barrierMethod),
            isRead ? IRTools.IC(field.getDeclaringClass().getId()) : new NullConstantOperand(),
            IRTools.AC(field.getDrOffset()));
      } else { // original
        NormalMethod barrierMethod = Octet.getClientAnalysis().chooseBarrier(ir.getMethod(), isRead, true, isResolved, true, inst.hasRedundantBarrier(), isSpecializedMethod(ir));

        if (barrierMethod == Entrypoints.drGetStaticResolvedObserveInitMethod) {
          barrierCall = Call.create2(CALL,
              null,
              IRTools.AC(barrierMethod.getOffset()),
              MethodOperand.STATIC(barrierMethod),
              IRTools.AC(field.getDrOffset()),
              IRTools.IC(field.getDeclaringClass().getId()));
        } else {
          barrierCall = Call.create1(CALL,
              null,
              IRTools.AC(barrierMethod.getOffset()),
              MethodOperand.STATIC(barrierMethod),
              isResolved ? IRTools.AC(field.getDrOffset()) : IRTools.IC(fieldRef.getId()));
        }
      }

      barrierCall.position = inst.position;
      barrierCall.bcIndex = inst.bcIndex;
      // Octet: LATER: try this
      /*
      if (!Octet.getClientAnalysis().barriersCanThrowExceptions()) {
        barrierCall.markAsNonPEI();
      }
       */
      finishParams(inst, fieldRef, barrierCall);
      insertBarrier(barrierCall, inst, isRead, null, field, isResolved, callsToInline, ir);
    }
    
    // FIB: volatile and unresolved post-barriers.
    Instruction postBarrierCall;
    if (!isResolved) {
      NormalMethod postBarrierMethod =
          isRead ? Entrypoints.drPostReadUnresolvedMethod : Entrypoints.drPostWriteUnresolvedMethod;

      postBarrierCall = Call.create2(CALL,
          null,
          IRTools.AC(postBarrierMethod.getOffset()),
          MethodOperand.STATIC(postBarrierMethod),
          new NullConstantOperand(),
          IRTools.IC(fieldRef.getId()));

    } else if (FieldTreatment.vol(field)) {
      NormalMethod postBarrierMethod =
          isRead ? Entrypoints.drPostVolatileReadResolvedMethod : Entrypoints.drPostVolatileWriteResolvedMethod;
      
      postBarrierCall = Call.create2(CALL,
          null,
          IRTools.AC(postBarrierMethod.getOffset()),
          MethodOperand.STATIC(postBarrierMethod),
          new NullConstantOperand(),
          IRTools.AC(field.getDrOffset()));

    } else if (isRead && FieldTreatment.staticFinal(field) && !DrRuntime.initHappensBeforeAll(field.getDeclaringClass())) {
//      if (Dr.STATS) DrStats.staticFinalReadInstrs.inc();
      // TODO: if all live fib threads have already observed the relevant class init, elide this.
      postBarrierCall = Call.create1(CALL,
          null,
          IRTools.AC(Entrypoints.drPostGetStaticFinalResolvedMethod.getOffset()),
          MethodOperand.STATIC(Entrypoints.drPostGetStaticFinalResolvedMethod),
          IRTools.IC(field.getDeclaringClass().getId()));
    } else {
      return;
    }

    postBarrierCall.position = inst.position;
    postBarrierCall.bcIndex = inst.bcIndex;
    // TODO: do I need to mark anything to prevent reordering of the access and the post-barrier?
    // TODO: internally, I use the last arg ("before") to decide whether barrier call is inserted
    // before or after 2nd arg.
    // inst.nextInstructionInCodeOrder()
    insertBarrier(postBarrierCall, inst, isRead, null, field, isResolved, callsToInline, ir, false);
  }

  
  
  
  
  
  // Sanity-checking instrumentation choices.
  
  @Override
  public void instrumentInst(Instruction inst, HashSetRVM<Instruction> callsToInline, IR ir) {
    if (Dr.STATS) {
      countBarrier(inst, true);
    }
    super.instrumentInst(inst, callsToInline, ir);
  }

  private void countBarrier(Instruction inst, boolean yes) {
    if (GetField.conforms(inst) || PutField.conforms(inst)) {
      countFieldBarrier(inst, yes);
    } else if (ALoad.conforms(inst) || AStore.conforms(inst)) {
      (yes ? DrStats.optArrayBarrierYes : DrStats.optArrayBarrierNo).inc();
    } else if (GetStatic.conforms(inst) || PutStatic.conforms(inst)) {
      countStaticBarrier(inst, yes);
    } else if (Load.conforms(inst) || Store.conforms(inst)) {
      Operand tempRef = Load.conforms(inst) ? Load.getAddress(inst) : Store.getAddress(inst);
      boolean isStatic = tempRef.isIntConstant() && tempRef.asIntConstant().value == Magic.getTocPointer().toInt();
      if (isStatic) {
        countStaticBarrier(inst, yes);
      } else {
        countFieldBarrier(inst, yes);
      }
    }
  }

  private void countStaticBarrier(Instruction inst, boolean yes) {
    boolean isRead;
    LocationOperand loc;
    if (!lateInstr) {
      isRead = GetStatic.conforms(inst);
      loc = isRead ? GetStatic.getLocation(inst) : PutStatic.getLocation(inst);
    } else {
      isRead = Load.conforms(inst);
      loc = isRead ? Load.getLocation(inst) : Store.getLocation(inst);
      if (VM.VerifyAssertions) { VM._assert(loc.isFieldAccess()); }
      Operand tempRef = isRead ? Load.getAddress(inst) : Store.getAddress(inst);
      if (VM.VerifyAssertions) { VM._assert(tempRef.isIntConstant() && tempRef.asIntConstant().value == Magic.getTocPointer().toInt()); }
    }
    FieldReference fieldRef = loc.getFieldRef();
    RVMField field = fieldRef.peekResolvedField();
    boolean isResolved = (field != null);
    if (isResolved) {
      (yes ? DrStats.optStaticBarrierResolvedYes : DrStats.optStaticBarrierResolvedNo).inc();
    } else {
      (yes ? DrStats.optStaticBarrierUnresolvedYes : DrStats.optStaticBarrierUnresolvedNo).inc();
    }
  }

  private void countFieldBarrier(Instruction inst, boolean yes) {
    boolean isRead;
    LocationOperand loc;
    Operand ref = null;
    if (!lateInstr) {
      isRead = GetField.conforms(inst);
      loc = isRead ? GetField.getLocation(inst) : PutField.getLocation(inst);
      ref = isRead ? GetField.getRef(inst) : PutField.getRef(inst);
    } else {
      isRead = Load.conforms(inst);
      loc = isRead ? Load.getLocation(inst) : Store.getLocation(inst);
      if (VM.VerifyAssertions) { VM._assert(loc != null && loc.isFieldAccess()); }
      Operand tempRef = isRead ? Load.getAddress(inst) : Store.getAddress(inst);
      if (VM.VerifyAssertions) { VM._assert(!(tempRef.isIntConstant() && tempRef.asIntConstant().value == Magic.getTocPointer().toInt())); }
      ref = tempRef;
    }
    if (loc == null) return;
    FieldReference fieldRef = loc.getFieldRef();
    if (fieldRef == null) return;
    RVMField field = fieldRef.peekResolvedField();
    boolean isResolved = (field != null);
    if (isResolved) {
      (yes ? DrStats.optFieldBarrierResolvedYes : DrStats.optFieldBarrierResolvedNo).inc();
    } else {
      (yes ? DrStats.optFieldBarrierUnresolvedYes : DrStats.optFieldBarrierUnresolvedNo).inc();
    }
  }

  /**
   * Classes deriving from OctetOptInstr can override this method and choose whether to instrument additional instructions
   * Does nothing by default
   * @param inst
   * @param callsToInline
   * @param ir
   */
  @Override
  public void instrumentOtherInstTypes(Instruction inst, HashSetRVM<Instruction> callsToInline, IR ir) {
    // Do nothing.
    if (Dr.STATS) {
      countBarrier(inst, false);
    }
  }

}
