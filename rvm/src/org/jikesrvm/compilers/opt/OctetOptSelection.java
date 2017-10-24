package org.jikesrvm.compilers.opt;

import org.jikesrvm.VM;
import org.jikesrvm.classloader.Context;
import org.jikesrvm.classloader.RVMField;
import org.jikesrvm.classloader.FieldReference;
import org.jikesrvm.compilers.opt.DefUse;
import org.jikesrvm.compilers.opt.OptOptions;
import org.jikesrvm.compilers.opt.driver.CompilerPhase;
import org.jikesrvm.compilers.opt.escape.EscapeTransformations;
import org.jikesrvm.compilers.opt.escape.FI_EscapeSummary;
import org.jikesrvm.compilers.opt.ir.ALoad;
import org.jikesrvm.compilers.opt.ir.AStore;
import org.jikesrvm.compilers.opt.ir.GetField;
import org.jikesrvm.compilers.opt.ir.GetStatic;
import org.jikesrvm.compilers.opt.ir.IR;
import org.jikesrvm.compilers.opt.ir.Instruction;
import org.jikesrvm.compilers.opt.ir.PutField;
import org.jikesrvm.compilers.opt.ir.PutStatic;
import org.jikesrvm.compilers.opt.ir.Register;
import org.jikesrvm.compilers.opt.ir.operand.LocationOperand;
import org.jikesrvm.compilers.opt.ir.operand.Operand;
import org.jikesrvm.octet.InstrDecisions;
import org.jikesrvm.octet.Octet;

/** Octet: decide which reads/writes should be instrumented */
public class OctetOptSelection extends CompilerPhase {

  private static final boolean verbose = true;

  @Override
  public String getName() {
    return "Octet read and write selection";
  }

  public OctetOptSelection() {
  }

  /** Let's just reuse the previous instance of this class, but reset the private variables */
  public CompilerPhase newExecution(IR ir) {
    return this;
  }

  @Override
  public boolean shouldPerform(OptOptions options) {
    return Octet.getConfig().insertBarriers() && Octet.getConfig().instrumentOptimizingCompiler();
  }

  @Override
  public void perform(IR ir) {
    // Octet: TODO: also undo prior changes for getting escape analysis to work in LIR
    FI_EscapeSummary escapeSummary = doEscapeAnalysis(ir);

    // we should be able to do something here with object references that we've already checked,
    // since they can't change unless we change them -- although a thread might relinquish any
    // object's state at *any* read or write, so how to deal with that?
    /*
    if (Octet.eliminateRedundantBarriers()) {
      computeRedundantReadBarriers();
    }
    */
    
    processInsts(escapeSummary, ir);
    
    // Octet: TODO: remove
    /*
    if (Octet.instrumentMethod(ir.getMethod())) {
      dumpIR(ir, "After Octet opt selection");
    }
    */
  }

  FI_EscapeSummary doEscapeAnalysis(IR ir) {
    // use escape analysis to find definitely unshared objects
    if (Octet.getConfig().useEscapeAnalysis()) {
      return EscapeTransformations.getEscapeSummary(ir);
    } else {
      // No need to do these if escape analysis is off, right?
      /*
      DefUse.computeDU(ir);
      DefUse.recomputeSSA(ir);
      */
      return null;
    }
  }
  
  void processInsts(FI_EscapeSummary escapeSummary, IR ir) {
    Instruction next;
    for (Instruction inst = ir.firstInstructionInCodeOrder(); inst != null; inst = next) {
      next = inst.nextInstructionInCodeOrder();
      processInst(inst, escapeSummary, ir);
    }
  }
  
  void processInst(Instruction inst, FI_EscapeSummary escapeSummary, IR ir) {
    boolean shouldInstrument = false;
    if (GetField.conforms(inst) || PutField.conforms(inst)) {
      shouldInstrument = shouldInstrumentInstPosition(inst, ir) && shouldInstrumentScalarAccess(inst, escapeSummary);
    } else if (ALoad.conforms(inst) || AStore.conforms(inst)) {
      shouldInstrument = shouldInstrumentInstPosition(inst, ir) && shouldInstrumentArrayAccess(inst, escapeSummary);
    } else if (GetStatic.conforms(inst) || PutStatic.conforms(inst)) {
      shouldInstrument = shouldInstrumentInstPosition(inst, ir) && shouldInstrumentStaticAccess(inst);
    }
    if (shouldInstrument) {
      inst.markAsPossibleSharedMemoryAccess();
    }
  }
  
  boolean shouldInstrumentScalarAccess(Instruction inst, FI_EscapeSummary escapeSummary) {
    // are we early or late in the compilation process?  use a different strategy in each case
    boolean isRead = GetField.conforms(inst);
    LocationOperand loc = isRead ? GetField.getLocation(inst) : PutField.getLocation(inst);
    Operand ref = isRead ? GetField.getRef(inst) : PutField.getRef(inst);
    FieldReference fieldRef = loc.getFieldRef();
    RVMField field = fieldRef.peekResolvedField();
    boolean isResolved = (field != null);
    boolean mightHaveMetadata;
    if (isResolved) {
      mightHaveMetadata = InstrDecisions.objectOrFieldHasMetadata(field);
    } else {
      mightHaveMetadata = InstrDecisions.objectOrFieldMightHaveMetadata(fieldRef);
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
  
  boolean shouldInstrumentArrayAccess(Instruction inst, FI_EscapeSummary escapeSummary) {
    boolean isRead = ALoad.conforms(inst);
    LocationOperand loc = isRead ? ALoad.getLocation(inst) : AStore.getLocation(inst);
    if (VM.VerifyAssertions) { VM._assert(loc.isArrayAccess()); }
    Operand ref = isRead ? ALoad.getArray(inst) : AStore.getArray(inst);
    if (mightEscape(ref, escapeSummary)) {
      return true;
    } else {
      // Helps with debugging
      //inst.markThreadLocal();
    }
    return false;
  }
  
  boolean shouldInstrumentStaticAccess(Instruction inst) {
    boolean isRead = GetStatic.conforms(inst);
    LocationOperand loc = isRead ? GetStatic.getLocation(inst) : PutStatic.getLocation(inst);
    FieldReference fieldRef = loc.getFieldRef();
    RVMField field = fieldRef.peekResolvedField();
    boolean isResolved = (field != null);
    boolean mightHaveMetadata;
    if (isResolved) {
      mightHaveMetadata = InstrDecisions.staticFieldHasMetadata(field);
    } else {
      mightHaveMetadata = InstrDecisions.staticFieldMightHaveMetadata(fieldRef);
    }
    if (mightHaveMetadata) {
      return true;
    }
    return false;
  }
  
  public static boolean shouldInstrumentInstPosition(Instruction inst, IR ir) {
    boolean instrumentInst = inst.position == null ? false : Octet.shouldInstrumentMethod(inst.position.getMethod());
    boolean instrumentMethod = Octet.shouldInstrumentMethod(ir.getMethod());
    boolean racyInst = inst.position == null ? false : (!Octet.getConfig().enableStaticRaceDetection() || (Context.isLibraryPrefix((inst.position.getMethod().getDeclaringClass().getTypeRef()))) || Octet.shouldInstrumentCheckRace(inst.position.getMethod(), inst.position.bcIndex));
     
    // Octet: TODO: Instrument all library accesses for now
    
    // Octet: TODO: Figure this out!  Might need to instrument more or fewer classes.
    // A class like System or String doesn't seem to need Octet instrumentation, but what about stuff inlined into it?
    // Actually, System probably needs Octet instrumentation, e.g., for array copy.
    // If a library method gets inlined into a VM method, then we probably don't need to instrument the library method.
    
    /*
    if (instrumentInst && !instrumentMethod) {
      System.out.println("Case A: Instruction in method " + ir.getMethod() + " is instrumented but method wouldn't be:");
      System.out.println(inst);
      System.out.println("  inst.position: " + inst.position);
      System.out.println();
    }
    if (!instrumentInst && instrumentMethod) {
      System.out.println("Case B: Instruction in method " + ir.getMethod() + " isn't instrumented but method would be:");
      System.out.println(inst);
      System.out.println("  inst.position: " + inst.position);
      System.out.println();
    }
    */
    
    // Octet: TODO: Need to make sure that what's getting instrumented is still reasonable.
    // VM instrumentation in application/library methods isn't necessarily being ignored!
    
    // Octet: TODO: not sure about this policy
    return instrumentInst && instrumentMethod && racyInst;
  }
  
  boolean mightEscape(Operand refOperand, FI_EscapeSummary escapeSummary) {
    if (Octet.getConfig().useEscapeAnalysis()) {
      if (refOperand.isRegister()) {
        Register reg = refOperand.asRegister().getRegister();
        if (escapeSummary.isThreadLocal(reg)) {
          if (VM.VerifyAssertions) { VM._assert(reg.isSSA()); }
          return false;
        }
      }
    }
    return true;
  }
  
  // Octet: LATER: synchronization operations should clear data-flow -- actually we might have more
  // serious problems with using this approach because the result of a barrier
  // can change at any read or write to any object
  
  /** Compute redundant field reads and writes (borrowed from Pacer) -- see note above for why this isn't right for Octet */
  /*
  @SuppressWarnings("unchecked")
  final void computeRedundantReadBarriers(HashSetRVM<Instruction> fullyRedInsts, IR ir) {
    // first set all the scratch objects to empty sets
    for (BasicBlock bb = ir.lastBasicBlockInCodeOrder(); bb != null; bb = bb.prevBasicBlockInCodeOrder()) {
      bb.scratchObject = new HashMap<Register,HashSet<FieldAccess>>();
    }
    ir.cfg.exit().scratchObject = new HashMap<Register,HashSet<FieldAccess>>();

    // do data-flow
    HashMap<Register,HashSet<FieldAccess>> thisFullyRedSet = new HashMap<Register,HashSet<FieldAccess>>();
    boolean changed;
    int iteration = 0;
    do {
      if (verbose) { System.out.println("Beginning iteration " + iteration + " fullyRedInsts.size() = " + fullyRedInsts.size()); }
      iteration++;
      changed = false;
      for (BasicBlock bb = ir.firstBasicBlockInCodeOrder(); bb != null; bb = bb.nextBasicBlockInCodeOrder()) {
        // compute redundant variables for the bottom of the block
        // and merge with redundant variables
        thisFullyRedSet.clear();
        boolean first = true;
        for (BasicBlockEnumeration e = bb.getIn(); e.hasMoreElements(); ) {
          BasicBlock predBB = e.next();
          HashMap<Register,HashSet<FieldAccess>> predFullyRedSet = (HashMap<Register,HashSet<FieldAccess>>)predBB.scratchObject;
          if (first) {
            for (Register reg : predFullyRedSet.keySet()) {
              // gotta copy the set, not reused the same object
              HashSet<FieldAccess> fieldAccesses = new HashSet<FieldAccess>(predFullyRedSet.get(reg));
              thisFullyRedSet.put(reg, fieldAccesses);
            }
            first = false;
          } else {
            // intersection of field access sets
            for (Iterator<Register> iter = thisFullyRedSet.keySet().iterator(); iter.hasNext(); ) {
              Register reg = iter.next();
              if (predFullyRedSet.containsKey(reg)) {
                HashSet<FieldAccess> fieldAccesses = thisFullyRedSet.get(reg);
                fieldAccesses.retainAll(predFullyRedSet.get(reg));
              } else {
                iter.remove();
              }
            }
          }
        }

        // propagate info from top to bottom of block
        for (Instruction i = bb.firstInstruction(); !i.isBbLast(); i = i.nextInstructionInCodeOrder()) {
          // first look at RHS (since we're going forward)
          Operand useOperand = null;
          boolean isRead = false;
          FieldReference fieldRef = null;
          //} else if (NewArray.conforms(i)) {
          //  useOperand = NewArray.getResult(i);
          if (GetField.conforms(i)) {
            useOperand = GetField.getRef(i);
            fieldRef = GetField.getLocation(i).asFieldAccess().getFieldRef();
            isRead = true;
          //} else if (ALoad.conforms(i)) {
          //  useOperand = ALoad.getArray(i);
          } else if (PutField.conforms(i)) {
            useOperand = PutField.getRef(i);
            fieldRef = PutField.getLocation(i).asFieldAccess().getFieldRef();
            isRead = false;
          //} else if (AStore.conforms(i)) {
          //  useOperand = AStore.getArray(i);
          }
          if (useOperand != null) {
            //if (VM.VerifyAssertions) { VM._assert(useOperand.isRegister() || useOperand.isConstant()); }
            if (useOperand.isRegister()) {
              Register useReg = useOperand.asRegister().register;
              FieldAccess fieldAccess = new FieldAccess(fieldRef, isRead);
              HashSet<FieldAccess> fieldAccesses = thisFullyRedSet.get(useReg);
              if (fieldAccesses == null) {
                fieldAccesses = new HashSet<FieldAccess>();
                thisFullyRedSet.put(useReg, fieldAccesses);
              }
              if (fieldAccesses.contains(fieldAccess)) {
                fullyRedInsts.add(i);
              } else {
                fieldAccesses.add(fieldAccess);
              }
            } else if (useOperand.isConstant()) {
              fullyRedInsts.add(i);
            } else {
              VM.sysFail("Weird operand: " + useOperand);
            }
          }
          // now look at LHS
          if (Move.conforms(i)) {
            Operand srcOperand = Move.getVal(i);
            if (srcOperand.isRegister()) {
              Register useReg = srcOperand.asRegister().register;
              Register defReg = Move.getResult(i).register;
              if (thisFullyRedSet.containsKey(useReg)) {
                HashSet<FieldAccess> useFieldAccesses = thisFullyRedSet.get(useReg);
                HashSet<FieldAccess> defFieldAccesses = thisFullyRedSet.get(defReg);
                if (defFieldAccesses == null) {
                  defFieldAccesses = new HashSet<FieldAccess>();
                  thisFullyRedSet.put(defReg, defFieldAccesses);
                }
                defFieldAccesses.addAll(useFieldAccesses);
              }
            }
          } else {
            // look at other defs
            for (OperandEnumeration e = i.getDefs(); e.hasMoreElements(); ) {
              Operand defOperand = e.next();
              if (defOperand.isRegister()) {
                Register defReg = defOperand.asRegister().register;
                thisFullyRedSet.remove(defReg);
              }
            }
          }
        }

        // compare what we've computed with what was already there
        HashMap<Register,HashSet<FieldAccess>> oldFullyRedSet = (HashMap<Register,HashSet<FieldAccess>>)bb.scratchObject;
        if (!oldFullyRedSet.equals(thisFullyRedSet)) {
          if (verbose) {
            System.out.println(bb);
            System.out.println("  Old: ");
            printRegAccessMap(oldFullyRedSet);
            System.out.println("  New: ");
            printRegAccessMap(thisFullyRedSet);
          }
          //if (VM.VerifyAssertions) { VM._assert(thisFullyRedSet.containsAll(oldFullyRedSet)); }
          oldFullyRedSet.clear();
          oldFullyRedSet.putAll(thisFullyRedSet);
          changed = true;
        }
      }
    } while (changed);

    // print graph
    //genGraph(ir, "redComp", partRedInsts, fullyRedInsts, needsBarrierMap, true);

    // clear the scratch objects
    for (BasicBlock bb = ir.lastBasicBlockInCodeOrder(); bb != null; bb = bb.prevBasicBlockInCodeOrder()) {
      bb.scratchObject = null;
    }
    ir.cfg.exit().scratchObject = null;
  }
  
  // used for redundancy
  static final class FieldAccess {
    final FieldReference field;
    final boolean isRead;
    
    public FieldAccess(FieldReference field, boolean isRead) {
      this.field = field;
      this.isRead = isRead;
    }
    
    @Override
    public boolean equals(Object o) {
      FieldAccess other = (FieldAccess)o;
      return field == other.field && isRead == other.isRead;
    }
    
    @Override
    public int hashCode() {
      return field.hashCode() + (isRead ? 1 : 0);
    }
    
    @Override
    public String toString() {
      return field + (isRead ? " (read)" : " (write)");
    }
  }

  private void printRegAccessMap(HashMap<Register,HashSet<FieldAccess>> map) {
    for (Register reg : map.keySet()) {
      System.out.println("    Reg: " + reg);
      HashSet<FieldAccess> fieldAccesses = map.get(reg);
      for (FieldAccess fieldAccess : fieldAccesses) {
        System.out.println("      Field access: " + fieldAccess);
      }
    }
  }
  */
}
