package org.jikesrvm.compilers.opt;

import static org.jikesrvm.compilers.opt.ir.Operators.CALL;

import org.jikesrvm.VM;
import org.jikesrvm.classloader.Context;
import org.jikesrvm.classloader.FieldReference;
import org.jikesrvm.classloader.NormalMethod;
import org.jikesrvm.classloader.RVMField;
import org.jikesrvm.classloader.RVMMethod;
import org.jikesrvm.classloader.TypeReference;
import org.jikesrvm.compilers.opt.driver.CompilerPhase;
import org.jikesrvm.compilers.opt.hir2lir.ConvertToLowLevelIR;
import org.jikesrvm.compilers.opt.inlining.InlineDecision;
import org.jikesrvm.compilers.opt.inlining.Inliner;
import org.jikesrvm.compilers.opt.ir.AStore;
import org.jikesrvm.compilers.opt.ir.Binary;
import org.jikesrvm.compilers.opt.ir.Call;
import org.jikesrvm.compilers.opt.ir.IR;
import org.jikesrvm.compilers.opt.ir.IRTools;
import org.jikesrvm.compilers.opt.ir.Instruction;
import org.jikesrvm.compilers.opt.ir.Load;
import org.jikesrvm.compilers.opt.ir.New;
import org.jikesrvm.compilers.opt.ir.NewArray;
import org.jikesrvm.compilers.opt.ir.Operators;
import org.jikesrvm.compilers.opt.ir.PutField;
import org.jikesrvm.compilers.opt.ir.PutStatic;
import org.jikesrvm.compilers.opt.ir.Store;
import org.jikesrvm.compilers.opt.ir.operand.LocationOperand;
import org.jikesrvm.compilers.opt.ir.operand.MethodOperand;
import org.jikesrvm.compilers.opt.ir.operand.Operand;
import org.jikesrvm.compilers.opt.ir.operand.RegisterOperand;
import org.jikesrvm.esc.Esc;
import org.jikesrvm.esc.EscInstrDecisions;
import org.jikesrvm.esc.EscapeState;
import org.jikesrvm.objectmodel.JavaHeader;
import org.jikesrvm.objectmodel.MiscHeader;
import org.jikesrvm.octet.CFGVisualization;
import org.jikesrvm.octet.InstrDecisions;
import org.jikesrvm.octet.Octet;
import org.jikesrvm.octet.Site;
import org.jikesrvm.runtime.Entrypoints;
import org.jikesrvm.runtime.Magic;
import org.jikesrvm.util.HashSetRVM;

/** Escape: add write instrumentation */ 
public class EscapeOptInstr extends CompilerPhase {

  private static final boolean verbose = false;

  /** Is this happening early or late in the compilation process (i.e., before or after 
   * getfields and putfields are converted to loads and stores)? */
  final boolean lateInstr;

  static enum InliningType { NO_INLINING, JIKES_INLINER, INSERT_IR_INSTS };
  final InliningType inliningType;

  @Override
  public String getName() {
    return "Dynamic escape analysis instrumentation";
  }

  // Man: Currently only support early instrumentation
  public EscapeOptInstr(boolean lateInstr) {
    this.lateInstr = lateInstr;
    if (Esc.getConfig().escapeUseJikesInliner() && Esc.getConfig().inlineBarriers()) {
      this.inliningType = InliningType.JIKES_INLINER;
    } else {
      this.inliningType = InliningType.NO_INLINING;
    }
  }

  // we'll reuse the previous instance of this class
  public CompilerPhase newExecution(IR ir) {
    return this;
  }

  @Override
  public boolean shouldPerform(OptOptions options) {
    if (Esc.getConfig().escapeInsertBarriers() && Esc.getConfig().instrumentOptimizingCompiler()) {
      return true;
    }
    return false;
  }

  @Override
  public void perform(IR ir) {

    if(ir.options.VISUALIZE_CFG) {
      CFGVisualization cfg = new CFGVisualization(ir, "beforeDynEscape");
      cfg.visualizeCFG(ir);
    }

    HashSetRVM<Instruction> callsToInline = null;
    if (inliningType == InliningType.JIKES_INLINER) {
      ir.gc.resync(); // resync generation context; needed since Jikes inlining may occur
      callsToInline = new HashSetRVM<Instruction>();
    }

    if (verbose) {
      System.out.println("Method before: " + ir.getMethod());
      ir.printInstructions();
    }

    for (Instruction inst = ir.firstInstructionInCodeOrder(); inst != null; inst = inst.nextInstructionInCodeOrder()) {
      if (shouldInstrumentInst(inst, ir)) {
        instrumentInst(inst, callsToInline, ir);
      } else {
        // Use this method to instrument any additional instruction types
        // Instructions with possible shared memory accesses probably will not require any further form of 
        // instrumentation, so an else should suffice and would minimize compares
        instrumentOtherInstTypes(inst, callsToInline, ir);
      }
    }

    if (verbose) {
      System.out.println("Method after: " + ir.getMethod());
      ir.printInstructions();
    }

    if (inliningType == InliningType.JIKES_INLINER) {
      for (Instruction call : callsToInline) {
        // Instruction inst = call.nextInstructionInCodeOrder();
        // Setting no callee exceptions might be incorrect for analyses that
        // want to throw exceptions out of the slow path, so allow analyses to override this.
        // Octet: TODO: trying this -- does it help STM?
        inline(call, ir, !Octet.getClientAnalysis().barriersCanThrowExceptions());
      }
    }

    if(ir.options.VISUALIZE_CFG) {
      CFGVisualization cfgAfter = new CFGVisualization(ir, "afterDynEscape");
      cfgAfter.visualizeCFG(ir);
    }
  }  

  private boolean shouldInstrumentInst(Instruction inst, IR ir) {
    boolean shouldInstrument = false;
    if (PutField.conforms(inst)) {
      shouldInstrument = shouldInstrumentInstPosition(inst, ir) && shouldInstrumentScalarAccess(inst);
    } else if (AStore.conforms(inst)) {
      shouldInstrument = shouldInstrumentInstPosition(inst, ir) && AStore.getValue(inst).isRef();
    } else if (PutStatic.conforms(inst)) {
      shouldInstrument = shouldInstrumentInstPosition(inst, ir) && shouldInstrumentStaticAccess(inst);
    }
    return shouldInstrument;
  }
  
  boolean shouldInstrumentScalarAccess(Instruction inst) {
    Operand value = PutField.getValue(inst);
    if (!value.isRef()) {
      return false;
    }
    LocationOperand loc = PutField.getLocation(inst);
    FieldReference fieldRef = loc.getFieldRef();
    return Esc.shouldInstrumentFieldAccess(fieldRef);
  }
  
  boolean shouldInstrumentStaticAccess(Instruction inst) {
    Operand value = PutStatic.getValue(inst);
    if (!value.isRef()) {
      return false;
    }
    LocationOperand loc = PutStatic.getLocation(inst);
    FieldReference fieldRef = loc.getFieldRef();
    return Esc.shouldInstrumentFieldAccess(fieldRef);
  }
  
  public static boolean shouldInstrumentInstPosition(Instruction inst, IR ir) {
    boolean instrumentInst = inst.position == null ? false : Esc.shouldInstrumentMethod(inst.position.getMethod());
    boolean instrumentMethod = Esc.shouldInstrumentMethod(ir.getMethod());
    return instrumentInst && instrumentMethod;
  }
  
  /**
   * Classes deriving from OctetOptInstr can override this method and choose whether to instrument additional instructions
   * Does nothing by default
   * @param inst
   * @param callsToInline
   * @param ir
   */
  public void instrumentOtherInstTypes(Instruction inst, HashSetRVM<Instruction> callsToInline, IR ir) {
    if (Esc.getConfig().escapeVMContextEscape() && ir.getMethod().getStaticContext() == Context.VM_CONTEXT) {
      // Mark objects allocated in VM context as escaped.
      Operand ref = null;
      if (New.conforms(inst)) {
        // I have problems with runtime tables and escape client hooks, so filter... --bpw
        if (EscInstrDecisions.needsEscape(New.getType(inst).getTypeRef())) {
          ref = New.getResult(inst);
        }
      } else if (NewArray.conforms(inst)) {
        if (EscInstrDecisions.needsEscape(NewArray.getType(inst).getTypeRef())) {
          ref = NewArray.getResult(inst);
        }
      }
      if (ref != null) {
        Instruction markEscape;
        // Inline the markEscaped method instead.
        NormalMethod mm = Entrypoints.escapeMarkEscapedMethod;
        markEscape = Call.create1(CALL, null, IRTools.AC(mm.getOffset()), MethodOperand.STATIC(mm), ref.copy());
        markEscape.bcIndex = inst.bcIndex;
        markEscape.position = inst.position;
        inst.insertAfter(markEscape);
        // Save some code space in FullAdaptive...
        if (!VM.VerifyAssertions && callsToInline != null) {
          callsToInline.add(markEscape);
        }
        /*
        if (Esc.getConfig().escapeUseOneBitInGCHeader()) {
          RegisterOperand state = ir.regpool.makeTempInt();
          Instruction loadStateInst = Load.create(Operators.INT_LOAD,
              state, ref.copy(), IRTools.AC(JavaHeader.ESCAPE_BIT_OFFSET), null);
          loadStateInst.bcIndex = inst.bcIndex;
          loadStateInst.position = inst.position;
          
          Instruction orInst = Binary.create(Operators.INT_OR, state.copyRO(), state.copy(), IRTools.IC(EscapeState.ESCAPE_BIT_MASK.toInt()));
          orInst.bcIndex = inst.bcIndex;
          orInst.position = inst.position;
          
          markEscape = Store.create(Operators.INT_STORE, state.copy(), ref.copy(), IRTools.AC(JavaHeader.ESCAPE_BIT_OFFSET), null);
          markEscape.bcIndex = inst.bcIndex;
          markEscape.position = inst.position;
          
          inst.insertAfter(markEscape);
          inst.insertAfter(orInst);
          inst.insertAfter(loadStateInst);
        } else {
          if (VM.VerifyAssertions) VM._assert(Esc.getConfig().escapeAddHeaderWord());
          Operand value = IRTools.IC(EscapeState.getEscapeMetadata().toInt());
          markEscape = Store.create(Operators.INT_STORE,
              value,
              ref.copy(),
              IRTools.AC(MiscHeader.ESCAPE_OFFSET),
              null);
          markEscape.bcIndex = inst.bcIndex;
          markEscape.position = inst.position;
          inst.insertAfter(markEscape);
        }
        if (Esc.getConfig().escapeStats()) {
          NormalMethod m = Entrypoints.escapeIncStatsCounterMethod;
          Instruction callInst = Call.create1(CALL, null, IRTools.AC(m.getOffset()), MethodOperand.STATIC(m), ref.copy());
          callInst.bcIndex = inst.bcIndex;
          callInst.position = inst.position;
          markEscape.insertAfter(callInst);
          callsToInline.add(callInst);
        }
      */
      }
    }
  }
  
  void instrumentInst(Instruction inst, HashSetRVM<Instruction> callsToInline, IR ir) {
    if (PutField.conforms(inst)) {
      if (VM.VerifyAssertions) { VM._assert(!lateInstr); }
      instrumentScalarAccess(inst, callsToInline, ir);
    } else if (AStore.conforms(inst)) {
      instrumentArrayAccess(inst, callsToInline, ir);
    } else if (PutStatic.conforms(inst)) {
      if (VM.VerifyAssertions) { VM._assert(!lateInstr); }
      instrumentStaticAccess(inst, callsToInline, ir);
//    } else if (Store.conforms(inst)) {
//      if (VM.VerifyAssertions) { VM._assert(lateInstr); }
//      Operand tempRef = Store.getAddress(inst);
//      boolean isStatic = tempRef.isIntConstant() && tempRef.asIntConstant().value == Magic.getTocPointer().toInt();
//      if (isStatic) {
//        instrumentStaticAccess(inst, callsToInline, ir);
//      } else {
//        instrumentScalarAccess(inst, callsToInline, ir);
//      }
    } else {
      if (VM.VerifyAssertions) { VM._assert(false); }
    }
  }

  void instrumentScalarAccess(Instruction inst, HashSetRVM<Instruction> callsToInline, IR ir) {
    LocationOperand loc = null;
    Operand ref = null;
    Operand value = null;
//    if (!lateInstr) {
    loc = PutField.getLocation(inst);
    ref = PutField.getRef(inst);
    value = PutField.getValue(inst);
//    } else {
//      isRead = Load.conforms(inst);
//      loc = isRead ? Load.getLocation(inst) : Store.getLocation(inst);
//      if (VM.VerifyAssertions) { VM._assert(loc != null && loc.isFieldAccess()); }
//      Operand tempRef = isRead ? Load.getAddress(inst) : Store.getAddress(inst);
//      if (VM.VerifyAssertions) { VM._assert(!(tempRef.isIntConstant() && tempRef.asIntConstant().value == Magic.getTocPointer().toInt())); }
//      ref = tempRef;
//    }
    FieldReference fieldRef = loc.getFieldRef();
    RVMField field = fieldRef.peekResolvedField();
    boolean isResolved = (field != null);
    if (VM.VerifyAssertions && isResolved) { VM._assert(!field.isStatic()); }

    int fieldInfo = 0;
    if (EscInstrDecisions.passFieldInfo()) {
      if (isResolved && EscInstrDecisions.useFieldOffset()) {
        fieldInfo = getFieldOffset(field);
      } else {
        fieldInfo = fieldRef.getId();
      }
    }

    NormalMethod barrierMethod = EscInstrDecisions.chooseBarrier(true, isResolved, false);
    Instruction barrierCall;
    if (isResolved) {
      barrierCall = Call.create4(CALL,
        null,
        IRTools.AC(barrierMethod.getOffset()),
        MethodOperand.STATIC(barrierMethod),
        value.copy(),
        ref.copy(),
        IRTools.AC(field.getOffset()),
        IRTools.IC(fieldInfo));
    } else {
      barrierCall = Call.create3(CALL,
          null,
          IRTools.AC(barrierMethod.getOffset()),
          MethodOperand.STATIC(barrierMethod),
          value.copy(),
          ref.copy(),
          IRTools.IC(fieldRef.getId()));
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
    insertBarrier(barrierCall, inst, ref, field, isResolved, callsToInline, ir);
  }

  void instrumentArrayAccess(Instruction inst, HashSetRVM<Instruction> callsToInline, IR ir) {
    LocationOperand loc = AStore.getLocation(inst);
    if (VM.VerifyAssertions) { VM._assert(loc.isArrayAccess()); }
    Operand ref = AStore.getArray(inst);
    Operand index = AStore.getIndex(inst);
    Operand value = AStore.getValue(inst);

    NormalMethod barrierMethod = EscInstrDecisions.chooseBarrier(false, true, false);
    Instruction barrierCall = Call.create3(CALL,
        null,
        IRTools.AC(barrierMethod.getOffset()),
        MethodOperand.STATIC(barrierMethod),
        value.copy(),
        ref.copy(),
        index.copy());
    barrierCall.position = inst.position;
    barrierCall.bcIndex = inst.bcIndex;
    // Octet: LATER: try this
    /*
    if (!Octet.getClientAnalysis().barriersCanThrowExceptions()) {
      barrierCall.markAsNonPEI();
    }
     */
    finishParams(inst, null, barrierCall);
    insertBarrier(barrierCall, inst, ref, null, true, callsToInline, ir);
  }

  void instrumentStaticAccess(Instruction inst, HashSetRVM<Instruction> callsToInline, IR ir) {
    LocationOperand loc = null;
    Operand value = null;
//    if (!lateInstr) {
    loc = PutStatic.getLocation(inst);
    value = PutStatic.getValue(inst);
//    } else {
//      loc = Store.getLocation(inst);
//      if (VM.VerifyAssertions) { VM._assert(loc.isFieldAccess()); }
//      Operand tempRef = Store.getAddress(inst);
//      if (VM.VerifyAssertions) { VM._assert(tempRef.isIntConstant() && tempRef.asIntConstant().value == Magic.getTocPointer().toInt()); }
//    }
    FieldReference fieldRef = loc.getFieldRef();
    RVMField field = fieldRef.peekResolvedField();
    boolean isResolved = (field != null);
    if (VM.VerifyAssertions && isResolved) { VM._assert(field.isStatic()); }

    int fieldInfo = 0;
    if (InstrDecisions.passFieldInfo()) {
      if (isResolved && InstrDecisions.useFieldOffset()) {
        fieldInfo = getFieldOffset(field);
      } else {
        fieldInfo = fieldRef.getId();
      }
    }

    NormalMethod barrierMethod = EscInstrDecisions.chooseBarrier(true, isResolved, true);
    Instruction barrierCall;
    if (isResolved) {
      barrierCall = Call.create3(CALL,
          null,
          IRTools.AC(barrierMethod.getOffset()),
          MethodOperand.STATIC(barrierMethod),
          value.copy(),
          IRTools.AC(field.getOffset()),
          IRTools.IC(fieldInfo));
    } else {
      barrierCall = Call.create2(CALL,
          null,
          IRTools.AC(barrierMethod.getOffset()),
          MethodOperand.STATIC(barrierMethod),
          value.copy(),
          // need to pass the field ID even if field info isn't needed, since it's needed to get the metadata offset
          IRTools.IC(fieldRef.getId()));
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
    insertBarrier(barrierCall, inst, null, field, isResolved, callsToInline, ir);
  }

  /** Pass site and/or other client-specific parameters */
  void finishParams(Instruction inst, FieldReference fieldRef, Instruction barrier) {
    passSite(inst, barrier);
    passExtra(inst, fieldRef, barrier);
  }

  void passSite(Instruction inst, Instruction barrier) {
    int siteID = EscInstrDecisions.passSite() ? Site.getSite(inst.position, inst.bcIndex, true) : 0;
    addParam(barrier, IRTools.IC(siteID));
  }

  /** Client analyses can override this to add more parameters */
  void passExtra(Instruction inst, FieldReference fieldRef, Instruction barrier) {
  }

  /** Helper method */
  void addParam(Instruction call, Operand operand) {
    int params = Call.getNumberOfParams(call);
    Call.resizeNumberOfParams(call, params + 1);
    Call.setParam(call, params, operand);
  }

  /** Client analyses can override this to provide customized field offset*/
  protected int getFieldOffset(RVMField field) {
    return field.getOffset().toInt();
  }

  void insertBarrier(Instruction barrierCall, Instruction inst, Operand refOperand, RVMField field, boolean isResolved, HashSetRVM<Instruction> callsToInline, IR ir) {
    insertCall(barrierCall, inst, isResolved, ir);
    // Inline if using Jikes inliner
    if (inliningType == InliningType.JIKES_INLINER &&
        // only inline non-infrequent instructions
        !inst.getBasicBlock().getInfrequent() &&
        // only inline for resolved fields
        isResolved) {
      callsToInline.add(barrierCall);
    }
  }

  /** insert a barrier method */
  private void insertCall(Instruction barrier, Instruction nextInst, boolean isResolved, IR ir) {
    if (VM.VerifyAssertions) {
      RVMMethod target = Call.getMethod(barrier).getTarget();
      int numParams = target.getParameterTypes().length;
      if (Call.getNumberOfParams(barrier) != numParams) {
        System.out.println(barrier);
        System.out.println(nextInst);
        VM.sysFail("Bad match");
      }
    }

    // Insert the barrier call
    nextInst.insertBefore(barrier);

    // If in LIR, the call needs to be lowered to LIR
    if (lateInstr) {
      ConvertToLowLevelIR.callHelper(barrier, ir);
    }
  }

  /**
   * Inline a call instruction -- copied from ExpandRuntimeServices
   */
  private void inline(Instruction inst, IR ir, boolean noCalleeExceptions) {
    // Save and restore inlining control state.
    // Some options have told us to inline this runtime service,
    // so we have to be sure to inline it "all the way" not
    // just 1 level.
    boolean savedInliningOption = ir.options.INLINE;
    boolean savedExceptionOption = ir.options.H2L_NO_CALLEE_EXCEPTIONS;
    ir.options.INLINE = true;
    ir.options.H2L_NO_CALLEE_EXCEPTIONS = noCalleeExceptions;
    boolean savedOsrGI = ir.options.OSR_GUARDED_INLINING;
    ir.options.OSR_GUARDED_INLINING = false;
    try {
      InlineDecision inlDec =
          InlineDecision.YES(Call.getMethod(inst).getTarget(), "Expansion of runtime service");
      Inliner.execute(inlDec, ir, inst);
    } finally {
      ir.options.INLINE = savedInliningOption;
      ir.options.H2L_NO_CALLEE_EXCEPTIONS = savedExceptionOption;
      ir.options.OSR_GUARDED_INLINING = savedOsrGI;
    }
  }

}
