package org.jikesrvm.compilers.opt;

import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import org.jikesrvm.VM;
import org.jikesrvm.classloader.FieldReference;
import org.jikesrvm.classloader.RVMField;
import org.jikesrvm.compilers.opt.driver.CompilerPhase;
import org.jikesrvm.compilers.opt.ir.ALoad;
import org.jikesrvm.compilers.opt.ir.AStore;
import org.jikesrvm.compilers.opt.ir.BasicBlock;
import org.jikesrvm.compilers.opt.ir.BoundsCheck;
import org.jikesrvm.compilers.opt.ir.GetField;
import org.jikesrvm.compilers.opt.ir.GetStatic;
import org.jikesrvm.compilers.opt.ir.IR;
import org.jikesrvm.compilers.opt.ir.Instruction;
import org.jikesrvm.compilers.opt.ir.Move;
import org.jikesrvm.compilers.opt.ir.New;
import org.jikesrvm.compilers.opt.ir.NullCheck;
import org.jikesrvm.compilers.opt.ir.PutField;
import org.jikesrvm.compilers.opt.ir.PutStatic;
import org.jikesrvm.compilers.opt.ir.Register;
import org.jikesrvm.compilers.opt.ir.operand.LocationOperand;
import org.jikesrvm.compilers.opt.ir.operand.Operand;
import org.jikesrvm.compilers.opt.liveness.LiveAnalysis;
import org.jikesrvm.compilers.opt.regalloc.LiveIntervalElement;
import org.jikesrvm.octet.Octet;
import org.jikesrvm.octet.Stats;

/**
 * Octet: This class implements a compiler phase for finding redundant barriers
 * in octet
 * 
 * @author Meisam
 * @modified by Minjia on October 4, 2012.
 * */
public class RedundantBarrierRemover extends CompilerPhase {

  /** Octet: TODO: more than 10 is probably a bug */
  private static final int MAX_ITERATIONS = 10; // 10000;

  /**
   * Octet: later: set this value from config.
   */
  private static final boolean DEBUG = false;

  /**
   * Octet: later: set this value from config.
   */
  private static final boolean VERBOSE = false;

  /** There are different levels of redundant barrier analysis. */
  public enum AnalysisLevel {
    NONE, DEFAULT_SAFE, OPTIMISTIC_SAFE, OPTIMISTIC_UNSAFE, SUPER_OPTIMISTIC_UNSAFE
  }

  final AnalysisLevel analysisLevel;
  
  public RedundantBarrierRemover(AnalysisLevel analysisLevel) {
    this.analysisLevel = analysisLevel;
  }
  
  /*
   * (non-Javadoc)
   * 
   * @see org.jikesrvm.compilers.opt.driver.CompilerPhase#getName()
   */
  @Override
  public String getName() {
    return "Octet Redundant Barrier Remover";
  }

  /*
   * (non-Javadoc)
   * 
   * @see
   * org.jikesrvm.compilers.opt.driver.CompilerPhase#newExecution(org.jikesrvm
   * .compilers.opt.ir.IR)
   */
  @Override
  public CompilerPhase newExecution(IR ir) {
    return this;
  }

  /*
   * (non-Javadoc)
   * 
   * @see
   * org.jikesrvm.compilers.opt.driver.CompilerPhase#shouldPerform(org.jikesrvm
   * .compilers.opt.OptOptions)
   */
  @Override
  public boolean shouldPerform(OptOptions options) {
    return Octet.getConfig().insertBarriers() && Octet.getConfig().instrumentOptimizingCompiler()
        && analysisLevel != AnalysisLevel.NONE;
  }

  /** Should we perform live analysis?  Only actually performed if using SORBA. */
  private static final boolean USE_LIVE_ANALYSIS = true;
  
  /** Live analysis object (only used if USE_LIVE_ANALYSIS==true and using SORBA). */
  LiveAnalysis liveAnalysis = null;

  @Override
  public void perform(IR ir) {

    if (!Octet.shouldInstrumentMethod(ir.getMethod())) {
      return;
    }

    if (DEBUG) {
      System.out.println("DEBUG: ");
      System.out.println("DEBUG: ");
      System.out.println("DEBUG: ");
      System.out.println("DEBUG: ============================================");
      System.out.println("DEBUG: " + ir.getMethod());
    }

    /** Perform live analysis if enabled and if using SORBA */
    if (USE_LIVE_ANALYSIS && analysisLevel == AnalysisLevel.OPTIMISTIC_SAFE) {
      liveAnalysis = new LiveAnalysis();
      liveAnalysis.perform(ir);
      //DefUse.computeDU(ir); -- apparently not actually needed
      ir.numberInstructions(); // needed to figure out live ranges
    }
    
    boolean needsUpdate = true;
    int count = 0;

    // Octet: TODO: instead of killing facts at catch blocks, we could unfactor the CFG before this analysis
    //ir.unfactor();
    
    // Initiate the facts as earlier optimization might use the scratch object.
    Enumeration<BasicBlock> blockEnumeration = ir.getBasicBlocks();
    while (blockEnumeration.hasMoreElements()) {
      BasicBlock basicBlock = blockEnumeration.nextElement();
      basicBlock.scratchObject = new ObjectsAccessFacts();
    }
    
    while (needsUpdate) {
      needsUpdate = false;
      count++;

      if (DEBUG) {
        System.out.println("DEBUG:   ---------------------------------");
        System.out.println("DEBUG:   Iteration #" + count);
      }

      if (VM.VerifyAssertions) {
        VM._assert(count < MAX_ITERATIONS);
      }

      // Octet: Meisam: TODO LATER use a better order for traversing basic
      // blocks
      blockEnumeration = ir.getBasicBlocks();
      while (blockEnumeration.hasMoreElements()) {
        BasicBlock basicBlock = blockEnumeration.nextElement();
        Instruction instruction = basicBlock.firstInstruction();

        ObjectsAccessFacts oldFacts = null;
        if (basicBlock.scratchObject instanceof ObjectsAccessFacts) {
          oldFacts = (ObjectsAccessFacts) basicBlock.scratchObject;
        }

        ObjectsAccessFacts newFacts = meetPredecessorBasicBlocks(basicBlock);
        if (DEBUG) {
          System.out.println("DEBUG:     ---- ");
          System.out.println("DEBUG:     " + basicBlock);
        }
        do {

          // Based on existing facts, try to eliminate barriers for a read or write.
          if (instruction.isPossibleSharedMemoryAccess()) {
            boolean storesSharedObject = storesSharedObject(instruction);
            boolean loadsSharedObject = loadsSharedObject(instruction);
            if (storesSharedObject || loadsSharedObject) {
              Object object = storesSharedObject ? getStoredObject(instruction) : getLoadedObject(instruction);
              BarrierType barrierType = newFacts.lookupFact(object);
              boolean isRedundant = barrierType.isHigher(storesSharedObject ? BarrierType.WRITE : BarrierType.READ);
              if (isRedundant) {
                instruction.clearAsPossibleSharedMemoryAccess();
                instruction.markHasRedundantBarrier();
              }
            }
          }

          updateFactsForUsedSharedObjects(instruction, newFacts);

          if (isSafePoint(instruction)) { // handle safe points
            if (analysisLevel != AnalysisLevel.SUPER_OPTIMISTIC_UNSAFE) {
              newFacts.clearAllNonTopFacts();
            }
          }
          
          // Try to update facts for read and write, depending on whether facts were cleared.
          if (storesSharedObject(instruction)) { // example: o.f = ...
            Object obj = getStoredObject(instruction);
            updateFactsForStoredSharedObject(obj, newFacts, instruction);
          } else if (loadsSharedObject(instruction)) { // example: ... = o.f
            Object obj = getLoadedObject(instruction);
            updateFactsForLoadedSharedObject(obj, newFacts, instruction);
          } 
          
          //Handle the lhs of an instruction.
          if (Move.conforms(instruction)) { // example o2 = o1
            Register lhsObject = Move.getResult(instruction).asRegister().getRegister();

            Operand val = Move.getVal(instruction);
            if (val.isRegister()) {
              // Right hand side object
              Register rhsObject = val.asRegister().getRegister();
              updateFactsForMoveInstruction(rhsObject, lhsObject, newFacts);
            } else {
              //Similarly, we 're conservative here, if the right hand side is not a register, than the left hand side could be assigned to anything.
              newFacts.updateToInfimumBarrier(lhsObject, BarrierType.BOTTOM);
            }
          } else if (instruction.isAllocation()) {
            Register register = New.getResult(instruction).asRegister()
                .getRegister();
            // Octet: TODO: Meisam: I think even in optimistic redundant barrier
            // analysis we should clear facts when we see a new instruction.
            // The facts have already been cleared by the above isSafepoint() check. We onlyl need to update the fact for the lhs. 
            newFacts.updateFact(register, BarrierType.TOP);
          } else {
            updateFactsForDefs(instruction, newFacts);
          }

          if (instruction.isBbLast()) {
            basicBlock.scratchObject = newFacts;
            break;
          }
          if (DEBUG) {
            System.out.println("DEBUG:        " + instruction);
            System.out.println("DEBUG:                                     "
                + newFacts);

          }
          instruction = instruction.nextInstructionInCodeOrder();
        } while (instruction != null);

        if (!(newFacts.equals(oldFacts))) {
          needsUpdate = true;
        }
      }
    }

    collectStats(ir);
  }

  /**
   * Counts the number of shared accesses and the number of accesses for which a
   * redundant barrier was detect for the given IR.
   * 
   * @param ir
   */
  private void collectStats(IR ir) {
    if (Octet.getConfig().stats()) {
      Enumeration<BasicBlock> blockEnumeration = ir.getBasicBlocks();
      while (blockEnumeration.hasMoreElements()) {
        BasicBlock basicBlock = blockEnumeration.nextElement();
        Instruction instruction = basicBlock.firstInstruction();
        do {
          if (instruction.isPossibleSharedMemoryAccess()) {
            Stats.sharedAccesses.inc();
          }
          if (instruction.hasRedundantBarrier()) {
            Stats.redundantBarriers.inc();
          }
          instruction = instruction.nextInstructionInCodeOrder();
        } while (instruction != basicBlock.lastInstruction());
      }
    }
  }

  /**
   * This methods finds all the objects that are used in the given instruction
   * and if uses are in TOP state, changes them to WRITE. Because objects in TOP
   * state may escape when they are used.
   * 
   * @param instruction
   *          The given instruction
   * @param facts
   *          The facts for the given instruction
   */
  private void updateFactsForUsedSharedObjects(Instruction instruction,
      ObjectsAccessFacts facts) {
    
    Enumeration<Operand> uses = instruction.getUses();

    while (uses.hasMoreElements()) {

      //Any op uses object as an operand like Move, Call, Return.
      //Conservatively change the fact for a object that might escape due to a Move, Call or Return op.
      Operand operand = uses.nextElement();
  
      if (operand.isRegister()) {
        // Handle the following case:
        // r1 = new A();
        // r2 = new A();
        // r2 = r1; or call (r1) or or any use of r1. 
        // r2 = r1.f //In order to use r1.f, it has to be loaded first into some register (A def).
        // o.f = o // the order of handling defs and uses might matter in this special case.
        Register register = operand.asRegister().getRegister();
        if (facts.lookupFact(register) == BarrierType.TOP) {
          facts.updateFact(register, BarrierType.WRITE);
        }
      }
    }
  }

  void updateFactsForDefs(Instruction instruction, ObjectsAccessFacts facts) {
    Enumeration<Operand> defs = instruction.getDefs();
    while (defs.hasMoreElements()) {
      Operand operand = defs.nextElement();
      if (operand.isRegister()) {
        Register register = operand.asRegister().getRegister();
        // Normally, the defs should be a new register, however, in unrolling, it could be used again in the unrolled loops.
        // Kind of having no other choice since it could be assigned to anything. We have to be conservative here.
        facts.updateFact(register, BarrierType.BOTTOM);
      }
    }
  }

  /**
   * @param rhsObject
   *          Right hand side of a move instruction
   * @param lhsObject
   *          Left hand side of a move instructions
   * @param facts
   *          Facts about redundant barriers after the instruction
   */
  private void updateFactsForMoveInstruction(Register rhsObject,
      Register lhsObject, ObjectsAccessFacts facts) {

    BarrierType rhsBarrierType = facts.lookupFact(rhsObject);
    // update information for lhs object with barrier for rhs object
    facts.updateFact(lhsObject, rhsBarrierType);
  }

  /**
   * Updates the facts for a given object when we see a {@link Move}
   * instruction.
   * 
   * @param object
   *          the accessed object
   * @param facts
   *          The facts after the instruction
   */
  private void updateFactsForNewInstruction(Object object,
      ObjectsAccessFacts facts) {

    // Octet: TODO: Meisam: I think even in optimistic redundant barrier
    // analysis we should clear facts when we see a new instruction.
    facts.clearAllNonTopFacts();
    facts.updateFact(object, BarrierType.TOP);
  }

  /**
   * Updates the facts for a given object when we see a load from that object.
   * 
   * @param object
   *          the accessed object
   * @param facts
   *          The facts
   */
  private void updateFactsForLoadedSharedObject(Object object, ObjectsAccessFacts facts, Instruction inst) {
    updateFactsForSharedObject(object, facts, BarrierType.READ, inst);
  }

  /**
   * Updates the facts for a given object when we see a store to that object.
   * 
   * @param object
   *          the accessed object
   * @param facts
   *          The facts
   */
  private void updateFactsForStoredSharedObject(Object object, ObjectsAccessFacts facts, Instruction inst) {
    updateFactsForSharedObject(object, facts, BarrierType.WRITE, inst);
  }

  /** Helper method called by updateFactsForLoadedSharedObject and updateFactsForStoredSharedObject */
  private void updateFactsForSharedObject(Object object, ObjectsAccessFacts facts, BarrierType barrierType, Instruction inst) {
    if (analysisLevel != AnalysisLevel.OPTIMISTIC_SAFE &&
        analysisLevel != AnalysisLevel.OPTIMISTIC_UNSAFE &&
        analysisLevel != AnalysisLevel.SUPER_OPTIMISTIC_UNSAFE) {
      facts.clearAllNonTopFacts(); // Because of Safe Points
    }
    facts.updateToSupremumBarrier(object, barrierType);
    
    // For safe optimistic analysis, store the facts for each instruction, to help with adding instrumentation later
    if (analysisLevel == AnalysisLevel.OPTIMISTIC_SAFE) {
      // We can't support retry instrumentation for unresolved statics, so let's just kill all non-top facts at unresolved static accesses
      if (GetStatic.conforms(inst) || PutStatic.conforms(inst)) {
        LocationOperand locationOperand = GetStatic.conforms(inst) ? GetStatic.getLocation(inst) : PutStatic.getLocation(inst); 
        FieldReference fieldRef = locationOperand.getFieldRef();
        RVMField field = fieldRef.getResolvedField();
        if (field == null || !field.hasMetadataOffset()) {
          facts.clearAllNonTopFacts();
        }
      }
      // Clone the facts and store them in the instruction's scratch object
      ObjectsAccessFacts clonedFacts = facts.clone();
      // If enabled, use live analysis information to remove facts for dead registers.
      if (USE_LIVE_ANALYSIS) {
        HashSet<Register> deadRegisters = getDeadRegisters(clonedFacts.getObjects(), inst, object);
        for (Register reg : deadRegisters) {
          clonedFacts.clearFact(reg);
        }
      }
      inst.scratchObject = clonedFacts;
    }
  }
  
  // Octet: LATER: liveness analysis could simply be used for any kind of RBA analysis, not just SORBA, to simplify the facts
  
  /** Return objects that are dead at inst and thus can be removed from the facts. */
  HashSet<Register> getDeadRegisters(Set<Object> objects, Instruction inst, Object instObject) {
    HashSet<Register> deadRegisters = new HashSet<Register>();
    for (Object temp : objects) {
      if (temp instanceof Register) {
        Register register = (Register)temp;
        Iterator<LiveIntervalElement> e = null;
        try {
          e = liveAnalysis.iterateLiveIntervals(register);
        } catch (ArrayIndexOutOfBoundsException ex) {
          //System.out.println("Register: " + register);
          //ex.printStackTrace();
        }
        if (e != null) {
          boolean hasLaterUse = false;
          // Iterate over live intervals to see if  
          while (e.hasNext()) {
            LiveIntervalElement elem = e.next();
            BasicBlock bb = elem.getBasicBlock();
            // Need to look at live ranges only for this basic block
            if (bb == inst.getBasicBlock()) {
              Instruction begin = (elem.getBegin() == null) ? bb.firstInstruction() : elem.getBegin();
              Instruction end = (elem.getEnd() == null) ? bb.lastInstruction() : elem.getEnd();
              int low = begin.scratch;
              int high = end.scratch;
              //System.out.println("inst.scratch = " + inst.scratch + " ; high = " + high);
              if (inst.scratch >= low && inst.scratch <= high) {
                hasLaterUse = true;
                break;
              } else {
                /*
                    System.out.println("inst.scratch = " + inst.scratch);
                    System.out.println("low = " + low);
                    System.out.println("high = " + high);
                    bb.printExtended();
                    System.out.println("inst: " + inst);
                 */
              }
            }
          }
          if (!hasLaterUse) {
            // Make sure we aren't accidentally clearing fact for the register accessed at this instruction
            if (VM.VerifyAssertions) { VM._assert(register != instObject); }
            // Will clear fact for dead register
            deadRegisters.add(register);
            //System.out.println("Clearing fact: " + register);
          } else {
            //System.out.println("Can't clear fact for " + register + " because it has a later use");
          }
        } else {
          //System.out.println("Can't clear fact for " + register + " because live analysis failed.");
        }
      } else {
        //System.out.println("Can't clear fact for " + temp + " because not a register");
      }
    }
    return deadRegisters;
  }

  /**
   * Does the given instruction stores shared object
   * 
   * @param instruction
   *          The given instruction
   * @return true if the given instruction stores a shared Object
   */
  private boolean storesSharedObject(Instruction instruction) {
    return PutField.conforms(instruction)
        || PutStatic.conforms(instruction)
        || (AStore.conforms(instruction) && AStore.getArray(instruction).isRegister());
  }

  /**
   * Does the given instruction loads a shared object
   * 
   * @param instruction
   *          The given instruction
   * @return true if the given instruction loads a shared Object
   */
  private boolean loadsSharedObject(Instruction instruction) {
    return GetField.conforms(instruction)
        || GetStatic.conforms(instruction)
        || (ALoad.conforms(instruction) && ALoad.getArray(instruction).isRegister());
  }

  /**
   * Returns the object that is stored in the given instruction
   * 
   * @param instruction
   *          The given instruction
   * @return The stored object
   */
  private Object getStoredObject(Instruction instruction) {
    if (PutField.conforms(instruction)) {
      if (PutField.getRef(instruction).isRegister()) {
        return PutField.getRef(instruction).asRegister().getRegister();
      }
      return null;
    } else if (PutStatic.conforms(instruction)) {
      return PutStatic.getLocation(instruction).getFieldRef();
    } else if (AStore.conforms(instruction)) {
      if (AStore.getArray(instruction).isRegister()) {
        return AStore.getArray(instruction).asRegister().getRegister();
      }
      return null;
    }
    if (VM.VerifyAssertions) {
      VM._assert(false, "Instruction " + instruction
          + " is not valid as an instruction that stores to a shared object");
    }
    return null;
  }

  /**
   * Returns the object that is loaded in the given instruction
   * 
   * @param instruction
   *          The given instruction
   * @return The loaded object
   */
  private Object getLoadedObject(Instruction instruction) {
    if (GetField.conforms(instruction)) {
      if (GetField.getRef(instruction).isRegister()) {
        return GetField.getRef(instruction).asRegister().getRegister();
      }
      return null;
    } else if (GetStatic.conforms(instruction)) {
      return GetStatic.getLocation(instruction).getFieldRef();
    } else if (ALoad.conforms(instruction)) {
      if (ALoad.getArray(instruction).isRegister()) { 
        return ALoad.getArray(instruction).asRegister().getRegister();
      }
      return null;
    }
    if (VM.VerifyAssertions) {
      VM._assert(false, "Instruction " + instruction
          + " is not valid as an instruction that loads from a shared object");
    }
    return null;
  }

  /**
   * Takes a basic block and merges all the facts from its predecessors into a
   * new fact. Returns the result fact
   * 
   * @param bb
   *          the given basic block
   * @return the new fact
   */
  private ObjectsAccessFacts meetPredecessorBasicBlocks(BasicBlock bb) {
    // In the FCFG, exception edges can exit out of the middle of a block to a catch block
    // Thus, all catch blocks should kill all facts.
    // Another option would be to use the unfactored CFG.
    if (bb.isExceptionHandlerBasicBlock()) {
      return new ObjectsAccessFacts();
    }
    
    Enumeration<BasicBlock> predecessorsEnumeration = bb.getInNodes();
    // The initial barrier type is BOTTOM.
    ObjectsAccessFacts newFacts;
    if (!predecessorsEnumeration.hasMoreElements()) { // It is the start node.
      return new ObjectsAccessFacts();
    } else {
      BasicBlock predecessorBasicBlock = predecessorsEnumeration.nextElement();
      // Need a clone here because we want to build facts for the current BB
      // based on its predecessors, however, we do not want to change the
      // predecessor's facts.
      // Get the first predecessor's facts.
      newFacts = ((ObjectsAccessFacts)predecessorBasicBlock.scratchObject).clone();
    }
    
    while (predecessorsEnumeration.hasMoreElements()) {
      BasicBlock predecessorBasicBlock = predecessorsEnumeration.nextElement();
      ObjectsAccessFacts otherFacts = (ObjectsAccessFacts) (predecessorBasicBlock.scratchObject);
      newFacts.meet(otherFacts);
    }

    return newFacts;
  }

  /**
   * 
   * Gets an instruction and returns true if it is a safe point
   * 
   * @param instruction
   *          the given instruction
   * @return true if the given instruction is a safe point, else false
   */

  private boolean isSafePoint(Instruction instruction) {
    return (instruction.isTSPoint() || instruction.isGCPoint())
        && !benignSafePoint(instruction);
  }

  /**
   * Returns true if the given instruction is a benign safe point, which means
   * it doesn't cause the current thread to lose exclusive access to the objects
   * that it already has.
   * 
   * @param instruction
   *          the given instruction
   * @return true if the given instruction is benign safe point
   */
  private boolean benignSafePoint(Instruction instruction) {
    return NullCheck.conforms(instruction) || BoundsCheck.conforms(instruction);
  }

}
