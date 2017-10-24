package org.jikesrvm.compilers.opt;

import static org.jikesrvm.compilers.opt.ir.Operators.CALL;
import static org.jikesrvm.compilers.opt.ir.Operators.REF_AND;
import static org.jikesrvm.compilers.opt.ir.Operators.INT_AND;
import static org.jikesrvm.compilers.opt.ir.Operators.INT_MOVE;
import static org.jikesrvm.compilers.opt.ir.Operators.INT_IFCMP;

import java.util.Set;

import org.jikesrvm.VM;
import org.jikesrvm.classloader.FieldReference;
import org.jikesrvm.classloader.NormalMethod;
import org.jikesrvm.classloader.RVMField;
import org.jikesrvm.classloader.RVMMethod;
import org.jikesrvm.classloader.TypeReference;
import org.jikesrvm.compilers.opt.driver.CompilerPhase;
import org.jikesrvm.compilers.opt.hir2lir.ConvertToLowLevelIR;
import org.jikesrvm.compilers.opt.inlining.InlineDecision;
import org.jikesrvm.compilers.opt.inlining.Inliner;
import org.jikesrvm.compilers.opt.ir.ALoad;
import org.jikesrvm.compilers.opt.ir.AStore;
import org.jikesrvm.compilers.opt.ir.BasicBlock;
import org.jikesrvm.compilers.opt.ir.Binary;
import org.jikesrvm.compilers.opt.ir.Call;
import org.jikesrvm.compilers.opt.ir.GetField;
import org.jikesrvm.compilers.opt.ir.GetStatic;
import org.jikesrvm.compilers.opt.ir.Goto;
import org.jikesrvm.compilers.opt.ir.IR;
import org.jikesrvm.compilers.opt.ir.IRTools;
import org.jikesrvm.compilers.opt.ir.IfCmp;
import org.jikesrvm.compilers.opt.ir.Instruction;
import org.jikesrvm.compilers.opt.ir.Load;
import org.jikesrvm.compilers.opt.ir.Move;
import org.jikesrvm.compilers.opt.ir.Operators;
import org.jikesrvm.compilers.opt.ir.PutField;
import org.jikesrvm.compilers.opt.ir.PutStatic;
import org.jikesrvm.compilers.opt.ir.Register;
import org.jikesrvm.compilers.opt.ir.Store;
import org.jikesrvm.compilers.opt.ir.operand.BranchOperand;
import org.jikesrvm.compilers.opt.ir.operand.BranchProfileOperand;
import org.jikesrvm.compilers.opt.ir.operand.ConditionOperand;
import org.jikesrvm.compilers.opt.ir.operand.LocationOperand;
import org.jikesrvm.compilers.opt.ir.operand.MethodOperand;
import org.jikesrvm.compilers.opt.ir.operand.Operand;
import org.jikesrvm.compilers.opt.ir.operand.RegisterOperand;
import org.jikesrvm.objectmodel.MiscHeader;
import org.jikesrvm.octet.CFGVisualization;
import org.jikesrvm.octet.InstrDecisions;
import org.jikesrvm.octet.Octet;
import org.jikesrvm.octet.OctetState;
import org.jikesrvm.octet.Site;
import org.jikesrvm.octet.Stats;
import org.jikesrvm.runtime.Entrypoints;
import org.jikesrvm.runtime.Magic;
import org.jikesrvm.util.HashSetRVM;
import org.vmmagic.unboxed.Offset;

/** Octet: add read/write instrumentation */ 
public class OctetOptInstr extends CompilerPhase {

  private static final boolean verbose = false;

  /** Is this happening early or late in the compilation process (i.e., before or after 
   * getfields and putfields are converted to loads and stores)? */
  final boolean lateInstr;

  static enum InliningType { NO_INLINING, JIKES_INLINER, INSERT_IR_INSTS };
  final InliningType inliningType;
  final RedundantBarrierRemover redundantBarrierRemover;

  @Override
  public String getName() {
    return "Octet instrumentation";
  }

  public OctetOptInstr(boolean lateInstr, RedundantBarrierRemover redundantBarrierRemover) {
    this.lateInstr = lateInstr;
    this.redundantBarrierRemover = redundantBarrierRemover;
    if (Octet.getConfig().inlineBarriers()) {
      // Build-time option can force use of the Jikes inliner
      if (Octet.getClientAnalysis().supportsIrBasedBarriers() &&
          !Octet.getConfig().forceUseJikesInliner()) {
        // Consistency check: Stats won't work with IR-based barriers
        if (VM.VerifyAssertions) { VM._assert(!Octet.getConfig().stats()); }
        this.inliningType = InliningType.INSERT_IR_INSTS;
      } else if (!lateInstr) {
        this.inliningType = InliningType.JIKES_INLINER;
      } else {
        this.inliningType = InliningType.NO_INLINING;
      }
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
    if (Octet.getConfig().insertBarriers() && Octet.getConfig().instrumentOptimizingCompiler()) {
      // Build-time options can force early instrumentation and/or use of the Jikes inliner, which requires early instrumentation
      if (Octet.getClientAnalysis().useLateOptInstrumentation() &&
          !Octet.getConfig().forceEarlyInstrumentation() &&
          !Octet.getConfig().forceUseJikesInliner()) {
        return lateInstr;
      } else {
        return !lateInstr;
      }
    }
    return false;
  }

  /** For debugging purposes. See makeRedundantBarriersSafe(). */
  protected HashSetRVM<Instruction> slowPathsInstrumented;

  @Override
  public void perform(IR ir) {

    if(ir.options.VISUALIZE_CFG) {
      CFGVisualization cfg = new CFGVisualization(ir, "beforeOctet");
      cfg.visualizeCFG(ir);
    }

    if (VM.VerifyAssertions) {
      slowPathsInstrumented = new HashSetRVM<Instruction>();
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
      if (inst.isPossibleSharedMemoryAccess() || (Octet.getClientAnalysis().instrInstructionHasRedundantBarrier(inst))) {
        instrumentInst(inst, callsToInline, ir);
        inst.clearAsPossibleSharedMemoryAccess();
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
        Instruction inst = call.nextInstructionInCodeOrder();
        // Setting no callee exceptions might be incorrect for analyses that
        // want to throw exceptions out of the slow path, so allow analyses to override this.
        // Octet: TODO: trying this -- does it help STM?
        inline(call, ir, !Octet.getClientAnalysis().barriersCanThrowExceptions());
        // Make optimistic RBA safe by executing barriers for recently acquired objects.
        if (!inst.hasRedundantBarrier() || !Octet.getConfig().isFieldSensitiveAnalysis()) {
          makeRedundantBarriersSafe(inst, null, ir);
        }
      }
    }

    // Check that all instructions were instrumented.  While it seems like iterating over
    // all instructions above should accomplish this, the facts that instructions get inserted --
    // and particularly because BBs get split -- can cause problems, particularly if
    // the "next" instruction is determined prior to inserting barrier code (which is avoided above).
    if (VM.VerifyAssertions) {
      if (!Octet.getConfig().isFieldSensitiveAnalysis()) {
        for (Instruction inst = ir.firstInstructionInCodeOrder(); inst != null; inst = inst.nextInstructionInCodeOrder()) {
          VM._assert(!inst.isPossibleSharedMemoryAccess());
        }
      }
    }
    if(ir.options.VISUALIZE_CFG) {
      CFGVisualization cfgAfter = new CFGVisualization(ir, "afterOctet");
      cfgAfter.visualizeCFG(ir);
    }
  }  

  /**
   * Classes deriving from OctetOptInstr can override this method and choose whether to instrument additional instructions
   * Does nothing by default
   * @param inst
   * @param callsToInline
   * @param ir
   */
  public void instrumentOtherInstTypes(Instruction inst, HashSetRVM<Instruction> callsToInline, IR ir) {
    // Do nothing.
  }

  protected boolean isSpecializedMethod(IR ir) {
    return Octet.getClientAnalysis().isSpecializedMethod(ir.getMethod());
  }
  
  void instrumentInst(Instruction inst, HashSetRVM<Instruction> callsToInline, IR ir) {
    if (GetField.conforms(inst) || PutField.conforms(inst)) {
      if (VM.VerifyAssertions) { VM._assert(!lateInstr); }
      instrumentScalarAccess(inst, callsToInline, ir);
    } else if (ALoad.conforms(inst) || AStore.conforms(inst)) {
      instrumentArrayAccess(inst, callsToInline, ir);
    } else if (GetStatic.conforms(inst) || PutStatic.conforms(inst)) {
      if (VM.VerifyAssertions) { VM._assert(!lateInstr); }
      instrumentStaticAccess(inst, callsToInline, ir);
    } else if (Load.conforms(inst) || Store.conforms(inst)) {
      if (VM.VerifyAssertions) { VM._assert(lateInstr); }
      Operand tempRef = Load.conforms(inst) ? Load.getAddress(inst) : Store.getAddress(inst);
      boolean isStatic = tempRef.isIntConstant() && tempRef.asIntConstant().value == Magic.getTocPointer().toInt();
      if (isStatic) {
        instrumentStaticAccess(inst, callsToInline, ir);
      } else {
        instrumentScalarAccess(inst, callsToInline, ir);
      }
    } else {
      if (VM.VerifyAssertions) { VM._assert(false); }
    }
  }

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
    if (VM.VerifyAssertions && isResolved) { VM._assert(!field.isStatic()); }

    int fieldInfo = 0;
    if (InstrDecisions.passFieldInfo()) {
      // Octet: TODO: we could still call the resolved barrier if doing late instrumentation,
      // since the offset will be in a virtual register
      if (isResolved && InstrDecisions.useFieldOffset()) {
        fieldInfo = getFieldOffset(field);
      } else {
        fieldInfo = fieldRef.getId();
      }
    }

    NormalMethod barrierMethod = Octet.getClientAnalysis().chooseBarrier(ir.getMethod(), isRead, true, isResolved, false, inst.hasRedundantBarrier(), isSpecializedMethod(ir));
    Instruction barrierCall = Call.create2(CALL,
        null,
        IRTools.AC(barrierMethod.getOffset()),
        MethodOperand.STATIC(barrierMethod),
        ref.copy(),
        IRTools.IC(fieldInfo));
    barrierCall.position = inst.position;
    barrierCall.bcIndex = inst.bcIndex;
    // Octet: LATER: try this
    /*
    if (!Octet.getClientAnalysis().barriersCanThrowExceptions()) {
      barrierCall.markAsNonPEI();
    }
     */
    finishParams(inst, fieldRef, barrierCall);
    insertBarrier(barrierCall, inst, isRead, ref, field, isResolved, callsToInline, ir);
  }

  void instrumentArrayAccess(Instruction inst, HashSetRVM<Instruction> callsToInline, IR ir) {
    boolean isRead = ALoad.conforms(inst);
    LocationOperand loc = isRead ? ALoad.getLocation(inst) : AStore.getLocation(inst);
    if (VM.VerifyAssertions) { VM._assert(loc.isArrayAccess()); }
    Operand ref = isRead ? ALoad.getArray(inst) : AStore.getArray(inst);
    Operand index = isRead ? ALoad.getIndex(inst) : AStore.getIndex(inst);

    NormalMethod barrierMethod = Octet.getClientAnalysis().chooseBarrier(ir.getMethod(), isRead, false, true, false, inst.hasRedundantBarrier(), isSpecializedMethod(ir));
    Instruction barrierCall = Call.create2(CALL,
        null,
        IRTools.AC(barrierMethod.getOffset()),
        MethodOperand.STATIC(barrierMethod),
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
    insertBarrier(barrierCall, inst, isRead, ref, null, true, callsToInline, ir);
  }

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

    int fieldInfo = 0;
    if (InstrDecisions.passFieldInfo()) {
      if (isResolved && InstrDecisions.useFieldOffset()) {
        fieldInfo = getFieldOffset(field);
      } else {
        fieldInfo = fieldRef.getId();
      }
    }

    NormalMethod barrierMethod = Octet.getClientAnalysis().chooseBarrier(ir.getMethod(), isRead, true, isResolved, true, inst.hasRedundantBarrier(), isSpecializedMethod(ir));
    Instruction barrierCall;
    if (isResolved) {
      barrierCall = Call.create2(CALL,
          null,
          IRTools.AC(barrierMethod.getOffset()),
          MethodOperand.STATIC(barrierMethod),
          IRTools.AC(field.getMetadataOffset()),
          IRTools.IC(fieldInfo));
    } else {
      barrierCall = Call.create1(CALL,
          null,
          IRTools.AC(barrierMethod.getOffset()),
          MethodOperand.STATIC(barrierMethod),
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
    insertBarrier(barrierCall, inst, isRead, null, field, isResolved, callsToInline, ir);
  }

  /** Pass site and/or other client-specific parameters */
  void finishParams(Instruction inst, FieldReference fieldRef, Instruction barrier) {
    passSite(inst, barrier);
    passExtra(inst, fieldRef, barrier);
  }

  void passSite(Instruction inst, Instruction barrier) {
    int siteID = InstrDecisions.passSite() ? Site.getSite(inst) : 0;
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

  // FIB: added wrapper
  void insertBarrier(Instruction barrierCall, Instruction inst, boolean isRead, Operand refOperand, RVMField field, boolean isResolved, HashSetRVM<Instruction> callsToInline, IR ir) {
    insertBarrier(barrierCall, inst, isRead, refOperand, field, isResolved, callsToInline, ir, true);
  }
  // FIB: added before/after option.
  void insertBarrier(Instruction barrierCall, Instruction inst, boolean isRead, Operand refOperand, RVMField field, boolean isResolved, HashSetRVM<Instruction> callsToInline, IR ir, boolean before) {
    // "Inline" by inserting IR instructions one-by-one, or insert a (possibly inlined) call
    if (inliningType == InliningType.INSERT_IR_INSTS &&
        // can't support IR-based barriers for unresolved statics
        (isResolved || refOperand != null) &&
        // just insert a call for infrequent instructions
        !inst.getBasicBlock().getInfrequent()) {

      // FIB: sanity for paths I don't use.
      if (VM.VerifyAssertions) VM._assert(before);
      
      insertIrBasedBarrier(inst, isRead, refOperand, field, barrierCall, ir, null, false, null, null, false);
    } else {
      // FIB: pass on before
      insertCall(barrierCall, inst, isResolved, ir, before);

      // Inline if using Jikes inliner
      if (inliningType == InliningType.JIKES_INLINER &&
          // only inline non-infrequent instructions
          !inst.getBasicBlock().getInfrequent() &&
          // only inline for resolved fields
          isResolved) {
        callsToInline.add(barrierCall);
      } else {
        // Make optimistic RBA safe by executing barriers for recently acquired objects
        if (!inst.hasRedundantBarrier() || !Octet.getConfig().isFieldSensitiveAnalysis()) {
          makeRedundantBarriersSafe(inst, barrierCall, ir);
        }
      }
    }
  }

  /** Adds special barriers to make redundant barrier analysis safe. */
  void makeRedundantBarriersSafe(Instruction inst, Instruction slowPath, IR ir) {
    if (redundantBarrierRemover != null &&
        redundantBarrierRemover.analysisLevel == RedundantBarrierRemover.AnalysisLevel.OPTIMISTIC_SAFE) {
      // Only early instrumentation is currently supported.
      if (VM.VerifyAssertions) { VM._assert(!lateInstr); }
      if (slowPath == null) {
        // Need to find slow path, since it was added via inlining
        boolean done = false;
        slowPath = inst;
        do {
          slowPath = slowPath.prevInstructionInCodeOrder();
          if (Call.conforms(slowPath)) {
            RVMMethod target = Call.getMethod(slowPath).getTarget();
            done = (target == Entrypoints.octetReadSlowPathMethod ||
                target == Entrypoints.octetWriteSlowPathMethod);
          }
        } while (!done);
        // Octet: LATER: try this
        /*
        if (!Octet.getClientAnalysis().barriersCanThrowExceptions()) {
          barrierCall.markAsNonPEI();
        }
         */
      } else {
        if (VM.VerifyAssertions) {
          VM._assert(Call.conforms(slowPath));
          RVMMethod target = Call.getMethod(slowPath).getTarget();
          VM._assert(target == Entrypoints.octetReadSlowPathMethod ||
              target == Entrypoints.octetWriteSlowPathMethod ||
              // If the barrier isn't inlined, then the call might actually be to the fast path, so allow that, too
              target.getDeclaringClass().equals(Entrypoints.octetFieldReadBarrierResolvedMethod.getDeclaringClass()));
        }
      }

      // Check to make sure we found the right slow path
      if (VM.VerifyAssertions) {
        // Check that we haven't already instrumented this slow path
        VM._assert(!slowPathsInstrumented.contains(slowPath));
        slowPathsInstrumented.add(slowPath);
        // Make sure all slow paths have been instrumented
        for (Instruction temp = ir.firstInstructionInCodeOrder(); temp != null; temp = temp.nextInstructionInCodeOrder()) {
          if (Call.conforms(temp)) {
            RVMMethod target = Call.getMethod(temp).getTarget();
            if (target == Entrypoints.octetReadSlowPathMethod ||
                target == Entrypoints.octetWriteSlowPathMethod) {
              VM._assert(slowPathsInstrumented.contains(temp));
            }
          }
        }
      }

      // First see if there are any redundant objects here.
      int redundantObjects = 0;
      ObjectsAccessFacts facts = (ObjectsAccessFacts)inst.scratchObject;
      Set<Object> objects = null;
      if (facts != null) {
        objects = facts.getObjects();
        for (Object object : objects) {
          BarrierType barrierType = facts.lookupFact(object);
          // Only READ and WRITE facts need to have their states reacquired.  Not BOTTOM and not TOP. 
          if (barrierType.equals(BarrierType.READ) || barrierType.equals(BarrierType.WRITE)) {
            redundantObjects++;
          }
        }
      }
      Stats.optimisticRbaStaticLoopSize.incBin(redundantObjects);

      // We need to have at least two redundant objects here to do any work.  If only the object for this barrier is redundant, then we don't need a retry loop.
      if (redundantObjects > 1) {
        // Initialize a boolean that keeps track of whether any slow paths have been taken
        RegisterOperand needRetryBooleanOperand = ir.regpool.makeTempBoolean();
        Instruction moveInst = Move.create(INT_MOVE, needRetryBooleanOperand, IRTools.IC(1));
        slowPath.insertAfter(moveInst);
        // Split above and below the Move instruction, to put it in its own basic block
        BasicBlock slowPathBB = slowPath.getBasicBlock();
        slowPathBB.splitNodeWithLinksAt(moveInst, ir);
        BasicBlock loopBB = slowPathBB.splitNodeWithLinksAt(slowPath, ir);

        // Now iterate over all facts in the set
        for (Object object : objects) {
          BarrierType barrierType = facts.lookupFact(object);
          // Only READ and WRITE facts need to have their states reacquired.  Not BOTTOM and not TOP. 
          if (barrierType.equals(BarrierType.READ) || barrierType.equals(BarrierType.WRITE)) {
            RVMMethod barrierMethod = null;
            Operand objectOperand = null;
            if (object instanceof Register) {
              objectOperand = new RegisterOperand((Register)object, TypeReference.JavaLangObject);
              barrierMethod = barrierType.equals(BarrierType.READ) ? Entrypoints.octetReadObjectMethod : Entrypoints.octetWriteObjectMethod;
            } else if (object instanceof FieldReference) {
              objectOperand = IRTools.AC(((FieldReference)object).getResolvedField().getMetadataOffset());
              barrierMethod = barrierType.equals(BarrierType.READ) ? Entrypoints.octetReadStaticMethod : Entrypoints.octetWriteStaticMethod;
            } else {
              if (VM.VerifyAssertions) { VM._assert(false, "Unexpected object type: " + object.getClass()); }
            }
            // Compute whether slow path was taken
            RegisterOperand tookSlowPathOperand = ir.regpool.makeTempBoolean();
            Instruction barrierCall = Call.create3(CALL,
                tookSlowPathOperand.copyRO(),
                IRTools.AC(barrierMethod.getOffset()),
                MethodOperand.STATIC(barrierMethod),
                objectOperand,
                IRTools.IC(0),
                IRTools.IC(0));
            barrierCall.bcIndex = slowPath.bcIndex;
            barrierCall.position = slowPath.position;
            // Octet: LATER: try this
            /*
            if (!Octet.getClientAnalysis().barriersCanThrowExceptions()) {
              barrierCall.markAsNonPEI();
            }
             */
            loopBB.appendInstruction(barrierCall);
            // Compute whether any slow path was taken
            Instruction andInst = Binary.create(INT_AND, needRetryBooleanOperand.copyRO(), needRetryBooleanOperand.copyRO(), tookSlowPathOperand.copyRO());
            andInst.bcIndex = slowPath.bcIndex;
            andInst.position = slowPath.position;
            loopBB.appendInstruction(andInst);
          }
        }
        // Now add a back edge up to the top of the basic block (to repeat the slow path)
        Instruction branchInst = IfCmp.create(INT_IFCMP, null, needRetryBooleanOperand.copyRO(), IRTools.IC(0), ConditionOperand.EQUAL(), loopBB.makeJumpTarget(), new BranchProfileOperand(BranchProfileOperand.NEVER));
        loopBB.appendInstruction(branchInst);
        loopBB.insertOut(loopBB);
        ir.verify("After back edge");
      }
    }
  }

  Instruction insertIrBasedBarrier(Instruction inst, boolean isRead, Operand ref, RVMField field, Instruction slowPath, IR ir, BasicBlock targetAfterSlowPath, boolean insertRetry, BasicBlock thisBB, BasicBlock nextBB, boolean mayBeUnresolvedStatic) {
    // split the current block and add a target block at the end of the IR
    BasicBlock readCasesBB = null;
    BasicBlock slowPathBB = null;
    if (thisBB == null && nextBB == null) {
      // Values are null means that these basic blocks have not been set.
      // RsceAnalysis sets thisBB to the basic block which will have the fast path.
      // When this method is called from RSCE analysis inst.getBasicBlock gives a basic block 
      // in the region body (as inst in this case is the instruction for which the barrier was generated
      // but RSCE requires splitting of the current basic block where header generation
      // is at present, that is a basic block in region header. Thus the code below in this if-block is for normal Octet instrumentation
      // and not for RSCE Analysis.
      thisBB = inst.getBasicBlock();
      nextBB = thisBB.splitNodeWithLinksAt(inst.prevInstructionInCodeOrder(), ir);
      readCasesBB = isRead ? thisBB.createSubBlock(inst.bcIndex, ir, 0) : null;
      slowPathBB = thisBB.createSubBlock(inst.bcIndex, ir, 0);
    } else {
      readCasesBB = isRead ? thisBB.createSubBlock(inst.bcIndex, ir, 0) : null;
      slowPathBB = thisBB.createSubBlock(inst.bcIndex, ir, 0);
      CFGVisualization.setColorForBasicBlocks(readCasesBB);
      CFGVisualization.setColorForBasicBlocks(slowPathBB);
    }

    if (isRead) {
      thisBB.insertOut(readCasesBB);
      readCasesBB.insertOut(nextBB);
      readCasesBB.insertOut(slowPathBB);
    } else {
      thisBB.insertOut(slowPathBB);
    }
    slowPathBB.insertOut(nextBB);
    if (isRead) {
      ir.cfg.addLastInCodeOrder(readCasesBB);
    }
    ir.cfg.addLastInCodeOrder(slowPathBB);

    // populate the local basic block:
    // get Octet metadata and compare to current thread

    RegisterOperand metadataOperand = ir.regpool.makeTemp(TypeReference.Word);
    Instruction loadInst;
    if (ref == null) {
      // static field (must be resolved, to get metadata offset) 
      if(!mayBeUnresolvedStatic) {
        // may be called from sce analysis for unresolved statics
        if (VM.VerifyAssertions) { VM._assert(field != null && field.isStatic()); }
      }
      if (field != null && field.hasMetadataOffset()) {
        Offset metadataOffset = field.getMetadataOffset();
        loadInst = Load.create(Operators.PREPARE_INT,
            metadataOperand,
            ir.regpool.makeJTOCOp(ir, inst),
            IRTools.AC(metadataOffset),
            new LocationOperand(metadataOffset));
        loadInst.position = inst.position;
        loadInst.bcIndex = inst.bcIndex;
        thisBB.appendInstruction(loadInst);

      } else {
        // Man: I think this block is for unresolved static, so it should not be reached.
        // Besides, the method "getResolved" returns an address. We should have another load instruction to load from the returned address.
        // Aritra: not reached by Octet but reached by Sce. Added the loadWord.
        if (VM.VerifyAssertions) { VM._assert(VM.NOT_REACHED); }
        NormalMethod getResolved = InstrDecisions.resolveStatic();
        Instruction getResolvedField = Call.create1(CALL, metadataOperand, IRTools.AC(getResolved.getOffset()),
            MethodOperand.STATIC(getResolved), Call.getParam(slowPath, 0));
        getResolvedField.position = inst.position;
        getResolvedField.bcIndex = inst.bcIndex;
        thisBB.appendInstruction(getResolvedField);
        if (lateInstr) {
          ConvertToLowLevelIR.callHelper(getResolvedField, ir);
        }
      }

    } else {
      // Octet: TODO: should we use location operands for Octet metadata loads? That might help the optimzer know what's going on.
      
      // object field or array element
      loadInst = Load.create(Operators.PREPARE_INT,
          metadataOperand,
          ref.copy(),
          IRTools.AC(MiscHeader.OCTET_OFFSET),
          null);
      loadInst.position = inst.position;
      loadInst.bcIndex = inst.bcIndex;
      thisBB.appendInstruction(loadInst);
    }




    if(isRead && Octet.getClientAnalysis().checkBothExclStateIRBasedBarriers()) {
      // insert And instruction followed by Ifcmp to check for both read and write exclusive state for reads
      RegisterOperand andResult = ir.regpool.makeTemp(TypeReference.Word);
      Instruction andIns = Binary.create(REF_AND, andResult, metadataOperand.copy(), IRTools.AC(OctetState.THREAD_MASK_FOR_EXCL_STATE.toAddress()));
      andIns.position = inst.position;
      andIns.bcIndex = inst.bcIndex;
      Instruction cmpInst = IfCmp.create(Operators.REF_IFCMP,
          ir.regpool.makeTempValidation(),
          andResult.copy(),
          ir.regpool.makeTROp(),
          ConditionOperand.NOT_EQUAL(),
          new BranchOperand((isRead ? readCasesBB : slowPathBB).firstInstruction()),
          BranchProfileOperand.never());
      cmpInst.position = inst.position;
      cmpInst.bcIndex = inst.bcIndex;
      thisBB.appendInstruction(andIns);
      thisBB.appendInstruction(cmpInst);

    } else { 

      Instruction cmpInst = IfCmp.create(Operators.REF_IFCMP,
          ir.regpool.makeTempValidation(),
          metadataOperand.copy(),
          ir.regpool.makeTROp(),
          ConditionOperand.NOT_EQUAL(),
          new BranchOperand((isRead ? readCasesBB : slowPathBB).firstInstruction()),
          BranchProfileOperand.never());
      cmpInst.position = inst.position;
      cmpInst.bcIndex = inst.bcIndex;
      thisBB.appendInstruction(cmpInst);

    }

    // rest of fast path (if any), followed by slow path, then a jump back to the handler

    // Octet: LATER: This code doesn't actually check for RdEx.
    // But it's correct since the "slow path" call is actually a call to the fast path barrier method, which checks for RdEx.

    // for reads, check for RdSh
    if (isRead) {
      RegisterOperand threadRdShCounter = ir.regpool.makeTemp(TypeReference.Word);
      Instruction loadThreadRdShCounterInst = Load.create(Operators.INT_LOAD,
          threadRdShCounter,
          ir.regpool.makeTROp(),
          IRTools.AC(Entrypoints.octetThreadReadSharedCounterField.getOffset()),
          new LocationOperand(Entrypoints.octetThreadReadSharedCounterField));
      loadThreadRdShCounterInst.position = inst.position;
      loadThreadRdShCounterInst.bcIndex = inst.bcIndex;
      readCasesBB.appendInstruction(loadThreadRdShCounterInst);

      Instruction rdShCmpInst = IfCmp.create(Operators.REF_IFCMP,
          ir.regpool.makeTempValidation(),
          threadRdShCounter.copy(),
          metadataOperand.copy(),
          ConditionOperand.LOWER_EQUAL(),
          new BranchOperand(nextBB.firstInstruction()),
          BranchProfileOperand.likely());
      rdShCmpInst.position = inst.position;
      rdShCmpInst.bcIndex = inst.bcIndex;
      readCasesBB.appendInstruction(rdShCmpInst);
    }

    Instruction jumpInst;

    if (insertRetry) {
      // Delete the fall through edge and insert the retry edge. 
      // InsertRetry would be true if configuration is RSCEIRBasedHeaderBarriers and if it is not the first barrier of the region.
      jumpInst = Goto.create(Operators.GOTO,
          new BranchOperand(targetAfterSlowPath.firstInstruction()));
      slowPathBB.insertOut(targetAfterSlowPath);
      slowPathBB.deleteOut(nextBB);
    } else {
      jumpInst = Goto.create(Operators.GOTO,
          new BranchOperand(nextBB.firstInstruction()));

    }
    jumpInst.position = inst.position;
    jumpInst.bcIndex = inst.bcIndex;
    slowPathBB.appendInstruction(jumpInst);

    insertCall(slowPath, jumpInst, true, ir);

    // Make optimistic RBA safe by executing barriers for recently acquired objects
    makeRedundantBarriersSafe(inst, slowPath, ir);

    return null;
  }

  /** insert a barrier method */
  private void insertCall(Instruction barrier, Instruction nextInst, boolean isResolved, IR ir) {
    insertCall(barrier, nextInst, isResolved, ir, true);
  }
  // FIB: added before/after option
  private void insertCall(Instruction barrier, Instruction nextInst, boolean isResolved, IR ir, boolean before) {
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
    if (before) {
      nextInst.insertBefore(barrier);
    } else {
      nextInst.insertAfter(barrier);
    }

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
