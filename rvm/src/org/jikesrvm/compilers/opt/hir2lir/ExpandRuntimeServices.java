/*
 *  This file is part of the Jikes RVM project (http://jikesrvm.org).
 *
 *  This file is licensed to You under the Eclipse Public License (EPL);
 *  You may not use this file except in compliance with the License. You
 *  may obtain a copy of the License at
 *
 *      http://www.opensource.org/licenses/eclipse-1.0.php
 *
 *  See the COPYRIGHT.txt file distributed with this work for information
 *  regarding copyright ownership.
 */
package org.jikesrvm.compilers.opt.hir2lir;

import static org.jikesrvm.compilers.opt.driver.OptConstants.RUNTIME_SERVICES_BCI;
import static org.jikesrvm.compilers.opt.ir.Operators.ATHROW_opcode;
import static org.jikesrvm.compilers.opt.ir.Operators.CALL;
import static org.jikesrvm.compilers.opt.ir.Operators.GETFIELD_opcode;
import static org.jikesrvm.compilers.opt.ir.Operators.GETSTATIC_opcode;
import static org.jikesrvm.compilers.opt.ir.Operators.INT_ASTORE;
import static org.jikesrvm.compilers.opt.ir.Operators.MONITORENTER_opcode;
import static org.jikesrvm.compilers.opt.ir.Operators.MONITOREXIT_opcode;
import static org.jikesrvm.compilers.opt.ir.Operators.NEWARRAY_UNRESOLVED_opcode;
import static org.jikesrvm.compilers.opt.ir.Operators.NEWARRAY;
import static org.jikesrvm.compilers.opt.ir.Operators.NEWARRAY_opcode;
import static org.jikesrvm.compilers.opt.ir.Operators.NEWOBJMULTIARRAY_opcode;
import static org.jikesrvm.compilers.opt.ir.Operators.NEW_UNRESOLVED_opcode;
import static org.jikesrvm.compilers.opt.ir.Operators.NEW_opcode;
import static org.jikesrvm.compilers.opt.ir.Operators.PUTFIELD_opcode;
import static org.jikesrvm.compilers.opt.ir.Operators.PUTSTATIC_opcode;
import static org.jikesrvm.compilers.opt.ir.Operators.REF_ALOAD_opcode;
import static org.jikesrvm.compilers.opt.ir.Operators.REF_ASTORE_opcode;
import static org.jikesrvm.compilers.opt.ir.Operators.BYTE_ASTORE_opcode;
import static org.jikesrvm.compilers.opt.ir.Operators.DOUBLE_ASTORE_opcode;
import static org.jikesrvm.compilers.opt.ir.Operators.FLOAT_ASTORE_opcode;
import static org.jikesrvm.compilers.opt.ir.Operators.INT_ASTORE_opcode;
import static org.jikesrvm.compilers.opt.ir.Operators.LONG_ASTORE_opcode;
import static org.jikesrvm.compilers.opt.ir.Operators.SHORT_ASTORE_opcode;
import static org.jikesrvm.compilers.opt.ir.Operators.REF_MOVE;
import static org.jikesrvm.mm.mminterface.Barriers.*;

import java.lang.reflect.Constructor;

import org.jikesrvm.VM;
import org.jikesrvm.classloader.Context;
import org.jikesrvm.classloader.RVMArray;
import org.jikesrvm.classloader.RVMClass;
import org.jikesrvm.classloader.RVMField;
import org.jikesrvm.classloader.FieldReference;
import org.jikesrvm.classloader.RVMMethod;
import org.jikesrvm.classloader.RVMType;
import org.jikesrvm.classloader.TypeReference;
import org.jikesrvm.compilers.opt.OptOptions;
import org.jikesrvm.compilers.opt.Simple;
import org.jikesrvm.compilers.opt.controlflow.BranchOptimizations;
import org.jikesrvm.compilers.opt.driver.CompilerPhase;
import org.jikesrvm.compilers.opt.inlining.InlineDecision;
import org.jikesrvm.compilers.opt.inlining.Inliner;
import org.jikesrvm.compilers.opt.ir.ALoad;
import org.jikesrvm.compilers.opt.ir.AStore;
import org.jikesrvm.compilers.opt.ir.Athrow;
import org.jikesrvm.compilers.opt.ir.Call;
import org.jikesrvm.compilers.opt.ir.GetField;
import org.jikesrvm.compilers.opt.ir.GetStatic;
import org.jikesrvm.compilers.opt.ir.IR;
import org.jikesrvm.compilers.opt.ir.IRTools;
import org.jikesrvm.compilers.opt.ir.Instruction;
import org.jikesrvm.compilers.opt.ir.MonitorOp;
import org.jikesrvm.compilers.opt.ir.Multianewarray;
import org.jikesrvm.compilers.opt.ir.Move;
import org.jikesrvm.compilers.opt.ir.New;
import org.jikesrvm.compilers.opt.ir.NewArray;
import org.jikesrvm.compilers.opt.ir.PutField;
import org.jikesrvm.compilers.opt.ir.PutStatic;
import org.jikesrvm.compilers.opt.ir.operand.IntConstantOperand;
import org.jikesrvm.compilers.opt.ir.operand.LocationOperand;
import org.jikesrvm.compilers.opt.ir.operand.MethodOperand;
import org.jikesrvm.compilers.opt.ir.operand.Operand;
import org.jikesrvm.compilers.opt.ir.operand.RegisterOperand;
import org.jikesrvm.compilers.opt.ir.operand.TypeOperand;
import org.jikesrvm.dr.Dr;
import org.jikesrvm.dr.instrument.FieldTreatment;
import org.jikesrvm.mm.mminterface.MemoryManager;
import org.jikesrvm.objectmodel.ObjectModel;
import org.jikesrvm.octet.Octet;
import org.jikesrvm.runtime.Entrypoints;
import org.jikesrvm.util.HashSetRVM;
import org.vmmagic.pragma.Inline;

/**
 * As part of the expansion of HIR into LIR, this compile phase
 * replaces all HIR operators that are implemented as calls to
 * VM service routines with CALLs to those routines.
 * For some (common and performance critical) operators, we
 * may optionally inline expand the call (depending on the
 * the values of the relevant compiler options and/or Controls).
 * This pass is also responsible for inserting write barriers
 * if we are using an allocator that requires them. Write barriers
 * are always inline expanded.
 */
public final class ExpandRuntimeServices extends CompilerPhase {
  /** Cache of simple optimizations if used to tidy up */
  private Simple _os;
  /** Cache of branch optimizations if used to tidy up */
  private BranchOptimizations branchOpts;
  /** Did we expand something? */
  private boolean didSomething = false;

  /**
   * Constructor for this compiler phase
   */
  private static final Constructor<CompilerPhase> constructor =
      getCompilerPhaseConstructor(ExpandRuntimeServices.class);

  /**
   * Get a constructor object for this compiler phase
   * @return compiler phase constructor
   */
  @Override
  public Constructor<CompilerPhase> getClassConstructor() {
    return constructor;
  }

  @Override
  public boolean shouldPerform(OptOptions options) {
    return true;
  }

  @Override
  public String getName() {
    return "Expand Runtime Services";
  }

  @Override
  public void reportAdditionalStats() {
    VM.sysWrite("  ");
    VM.sysWrite(container.counter1 / container.counter2 * 100, 2);
    VM.sysWrite("% Infrequent RS calls");
  }

  /**
   * Given an HIR, expand operators that are implemented as calls to
   * runtime service methods. This method should be called as one of the
   * first steps in lowering HIR into LIR.
   *
   * @param ir  The HIR to expand
   */
  @Override
  public void perform(IR ir) {
    ir.gc.resync(); // resync generation context -- yuck...

    // Octet: Static cloning: Check that the static and dynamic contexts match.
    if (Context.DEBUG && Context.isLibraryPrefix(ir.getMethod().getDeclaringClass().getTypeRef())) {
      RVMMethod target = Entrypoints.checkLibraryContextMethod;
      Instruction call = Call.create0(CALL, null, IRTools.AC(target.getOffset()), MethodOperand.STATIC(target));
      ir.firstBasicBlockInCodeOrder().prependInstructionRespectingPrologue(call);
    }
    
    // Octet: needed in order to ensure that Jikes write pre-barriers aren't added multiple times to the same instruction
    HashSetRVM<Instruction> instsWithWritePreBarrier = new HashSetRVM<Instruction>();
    
    // FIB:
    final boolean fib = Dr.ON && Octet.shouldInstrumentMethod(ir.method);
    final boolean fibLocks = Dr.LOCKS && fib;
    // end FIB
    
    Instruction next;
    for (Instruction inst = ir.firstInstructionInCodeOrder(); inst != null; inst = next) {
      next = inst.nextInstructionInCodeOrder();
      int opcode = inst.getOpcode();

      switch (opcode) {

        case NEW_opcode: {
          TypeOperand Type = New.getClearType(inst);
          RVMClass cls = (RVMClass) Type.getVMType();
          IntConstantOperand hasFinalizer = IRTools.IC(cls.hasFinalizer() ? 1 : 0);
          RVMMethod callSite = inst.position.getMethod();
          IntConstantOperand allocator = IRTools.IC(MemoryManager.pickAllocator(cls, callSite));
          IntConstantOperand align = IRTools.IC(ObjectModel.getAlignment(cls));
          IntConstantOperand offset = IRTools.IC(ObjectModel.getOffsetForAlignment(cls, false));
          Operand tib = ConvertToLowLevelIR.getTIB(inst, ir, Type);
          if (VM.BuildForIA32 && VM.runningVM) {
            // shield BC2IR from address constants
            RegisterOperand tmp = ir.regpool.makeTemp(TypeReference.TIB);
            inst.insertBefore(Move.create(REF_MOVE, tmp, tib));
            tib = tmp.copyRO();
          }
          IntConstantOperand site = IRTools.IC(MemoryManager.getAllocationSite(true));
          RVMMethod target = Entrypoints.resolvedNewScalarMethod;
          Call.mutate7(inst,
                       CALL,
                       New.getClearResult(inst),
                       IRTools.AC(target.getOffset()),
                       MethodOperand.STATIC(target),
                       IRTools.IC(cls.getInstanceSize()),
                       tib,
                       hasFinalizer,
                       allocator,
                       align,
                       offset,
                       site);
          next = inst.prevInstructionInCodeOrder();
          if (ir.options.H2L_INLINE_NEW) {
            if (inst.getBasicBlock().getInfrequent()) container.counter1++;
            container.counter2++;
            if (!ir.options.FREQ_FOCUS_EFFORT || !inst.getBasicBlock().getInfrequent()) {
              inline(inst, ir);
            }
          }
        }
        break;

        case NEW_UNRESOLVED_opcode: {
          int typeRefId = New.getType(inst).getTypeRef().getId();
          RVMMethod target = Entrypoints.unresolvedNewScalarMethod;
          IntConstantOperand site = IRTools.IC(MemoryManager.getAllocationSite(true));
          Call.mutate2(inst,
                       CALL,
                       New.getClearResult(inst),
                       IRTools.AC(target.getOffset()),
                       MethodOperand.STATIC(target),
                       IRTools.IC(typeRefId),
                       site);
        }
        break;

        case NEWARRAY_opcode: {
          TypeOperand Array = NewArray.getClearType(inst);
          RVMArray array = (RVMArray) Array.getVMType();
          Operand numberElements = NewArray.getClearSize(inst);
          boolean inline = numberElements instanceof IntConstantOperand;
          Operand width = IRTools.IC(array.getLogElementSize());
          Operand headerSize = IRTools.IC(ObjectModel.computeArrayHeaderSize(array));
          RVMMethod callSite = inst.position.getMethod();
          IntConstantOperand allocator = IRTools.IC(MemoryManager.pickAllocator(array, callSite));
          IntConstantOperand align = IRTools.IC(ObjectModel.getAlignment(array));
          IntConstantOperand offset = IRTools.IC(ObjectModel.getOffsetForAlignment(array, false));
          Operand tib = ConvertToLowLevelIR.getTIB(inst, ir, Array);
          if (VM.BuildForIA32 && VM.runningVM) {
            // shield BC2IR from address constants
            RegisterOperand tmp = ir.regpool.makeTemp(TypeReference.TIB);
            inst.insertBefore(Move.create(REF_MOVE, tmp, tib));
            tib = tmp.copyRO();
          }
          IntConstantOperand site = IRTools.IC(MemoryManager.getAllocationSite(true));
          RVMMethod target = Entrypoints.resolvedNewArrayMethod;
          Call.mutate8(inst,
                       CALL,
                       NewArray.getClearResult(inst),
                       IRTools.AC(target.getOffset()),
                       MethodOperand.STATIC(target),
                       numberElements,
                       width,
                       headerSize,
                       tib,
                       allocator,
                       align,
                       offset,
                       site);
          next = inst.prevInstructionInCodeOrder();
          if (inline && ir.options.H2L_INLINE_NEW) {
            if (inst.getBasicBlock().getInfrequent()) container.counter1++;
            container.counter2++;
            if (!ir.options.FREQ_FOCUS_EFFORT || !inst.getBasicBlock().getInfrequent()) {
              inline(inst, ir);
            }
          }
        }
        break;

        case NEWARRAY_UNRESOLVED_opcode: {
          int typeRefId = NewArray.getType(inst).getTypeRef().getId();
          Operand numberElements = NewArray.getClearSize(inst);
          RVMMethod target = Entrypoints.unresolvedNewArrayMethod;
          IntConstantOperand site = IRTools.IC(MemoryManager.getAllocationSite(true));
          Call.mutate3(inst,
                       CALL,
                       NewArray.getClearResult(inst),
                       IRTools.AC(target.getOffset()),
                       MethodOperand.STATIC(target),
                       numberElements,
                       IRTools.IC(typeRefId),
                       site);
        }
        break;

        case NEWOBJMULTIARRAY_opcode: {
          int dimensions = Multianewarray.getNumberOfDimensions(inst);
          RVMMethod callSite = inst.position.getMethod();
          int typeRefId = Multianewarray.getType(inst).getTypeRef().getId();
          if (dimensions == 2) {
            RVMMethod target = Entrypoints.optNew2DArrayMethod;
            Call.mutate4(inst,
                         CALL,
                         Multianewarray.getClearResult(inst),
                         IRTools.AC(target.getOffset()),
                         MethodOperand.STATIC(target),
                         IRTools.IC(callSite.getId()),
                         Multianewarray.getClearDimension(inst, 0),
                         Multianewarray.getClearDimension(inst, 1),
                         IRTools.IC(typeRefId));
          } else {
            // Step 1: Create an int array to hold the dimensions.
            TypeOperand dimArrayType = new TypeOperand(RVMArray.IntArray);
            RegisterOperand dimArray = ir.regpool.makeTemp(TypeReference.IntArray);
            dimArray.setPreciseType();
            next =  NewArray.create(NEWARRAY, dimArray, dimArrayType, new IntConstantOperand(dimensions));
            inst.insertBefore(next);
            // Step 2: Assign the dimension values to dimArray
            for (int i = 0; i < dimensions; i++) {
              LocationOperand loc = new LocationOperand(TypeReference.Int);
              inst.insertBefore(AStore.create(INT_ASTORE,
                                Multianewarray.getClearDimension(inst, i),
                                dimArray.copyD2U(),
                                IRTools.IC(i),
                                loc,
                                IRTools.TG()));
            }
            // Step 3. Plant call to OptLinker.newArrayArray
            RVMMethod target = Entrypoints.optNewArrayArrayMethod;
            Call.mutate3(inst,
                         CALL,
                         Multianewarray.getClearResult(inst),
                         IRTools.AC(target.getOffset()),
                         MethodOperand.STATIC(target),
                         IRTools.IC(callSite.getId()),
                         dimArray.copyD2U(),
                         IRTools.IC(typeRefId));
          }
        }
        break;

        case ATHROW_opcode: {
          RVMMethod target = Entrypoints.athrowMethod;
          MethodOperand methodOp = MethodOperand.STATIC(target);
          methodOp.setIsNonReturningCall(true);   // Record the fact that this is a non-returning call.
          Call.mutate1(inst, CALL, null, IRTools.AC(target.getOffset()), methodOp, Athrow.getClearValue(inst));
        }
        break;

        case MONITORENTER_opcode: {
          Operand ref = MonitorOp.getClearRef(inst);
          RVMType refType = ref.getType().peekType();
          if (refType != null && !refType.getThinLockOffset().isMax()) {
            // FIB: choice of method
            RVMMethod target = fibLocks ? Entrypoints.drInlineLockMethod : Entrypoints.inlineLockMethod;
            Call.mutate2(inst,
                         CALL,
                         null,
                         IRTools.AC(target.getOffset()),
                         MethodOperand.STATIC(target),
                         MonitorOp.getClearGuard(inst),
                         ref,
                         IRTools.AC(refType.getThinLockOffset()));
            next = inst.prevInstructionInCodeOrder();
            if (inst.getBasicBlock().getInfrequent()) container.counter1++;
            container.counter2++;
            if (!ir.options.FREQ_FOCUS_EFFORT || !inst.getBasicBlock().getInfrequent()) {
              inline(inst, ir);
            }
          } else {
            // FIB: choice of method
            RVMMethod target = (fibLocks ? Entrypoints.drLockMethod : Entrypoints.lockMethod);
            Call.mutate1(inst,
                         CALL,
                         null,
                         IRTools.AC(target.getOffset()),
                         MethodOperand.STATIC(target),
                         MonitorOp.getClearGuard(inst),
                         ref);
          }
        }
        break;

        case MONITOREXIT_opcode: {
          Operand ref = MonitorOp.getClearRef(inst);
          RVMType refType = ref.getType().peekType();
          if (refType != null && !refType.getThinLockOffset().isMax()) {
            // FIB: choice of method
            RVMMethod target = fibLocks ? Entrypoints.drInlineUnlockMethod : Entrypoints.inlineUnlockMethod;
            Call.mutate2(inst,
                         CALL,
                         null,
                         IRTools.AC(target.getOffset()),
                         MethodOperand.STATIC(target),
                         MonitorOp.getClearGuard(inst),
                         ref,
                         IRTools.AC(refType.getThinLockOffset()));
            next = inst.prevInstructionInCodeOrder();
            if (inst.getBasicBlock().getInfrequent()) container.counter1++;
            container.counter2++;
            if (!ir.options.FREQ_FOCUS_EFFORT || !inst.getBasicBlock().getInfrequent()) {
              inline(inst, ir);
            }
          } else {
            // FIB: choice of method
            RVMMethod target = (fibLocks ? Entrypoints.drUnlockMethod : Entrypoints.unlockMethod);
            Call.mutate1(inst,
                         CALL,
                         null,
                         IRTools.AC(target.getOffset()),
                         MethodOperand.STATIC(target),
                         MonitorOp.getClearGuard(inst),
                         ref);
          }
        }
        break;

        case REF_ASTORE_opcode: {
          if (NEEDS_OBJECT_ASTORE_BARRIER) {
            RVMMethod target = Entrypoints.objectArrayWriteBarrierMethod;
            
            // Octet: If the instruction might need an Octet barrier, let's leave the instruction alone and instead insert a "pre-barrier"
            boolean usePreBarrier = mightNeedOctetBarrier(inst);
            if (usePreBarrier) {
              // Don't add Jikes write barriers twice
              if (instsWithWritePreBarrier.contains(inst)) {
                break;
              }
              target = Entrypoints.objectArrayWritePreBarrierMethod;
            }
            
            Instruction wb =
                Call.create3(CALL,
                             null,
                             IRTools.AC(target.getOffset()),
                             MethodOperand.STATIC(target),
                             AStore.getClearGuard(inst),
                             AStore.getArray(inst).copy(),
                             AStore.getIndex(inst).copy(),
                             AStore.getValue(inst).copy());
            wb.bcIndex = RUNTIME_SERVICES_BCI;
            wb.position = inst.position;
            
            // Octet: If inserting a pre-barrier, then don't replace the instruction
            if (usePreBarrier) {
              inst.insertBefore(wb);
              instsWithWritePreBarrier.add(inst);
            } else {
              inst.replace(wb);
            }

            next = wb.prevInstructionInCodeOrder();
            if (ir.options.H2L_INLINE_WRITE_BARRIER) {
              inline(wb, ir, true);
            }
          }
        }
        break;

        case BYTE_ASTORE_opcode: {
          if (NEEDS_BYTE_ASTORE_BARRIER) {
            primitiveArrayStoreHelper(Entrypoints.byteArrayWriteBarrierMethod, inst, next, ir);
          }
        }
        break;

        case DOUBLE_ASTORE_opcode: {
          if (NEEDS_DOUBLE_ASTORE_BARRIER) {
            primitiveArrayStoreHelper(Entrypoints.doubleArrayWriteBarrierMethod, inst, next, ir);
          }
        }
        break;

        case FLOAT_ASTORE_opcode: {
          if (NEEDS_FLOAT_ASTORE_BARRIER) {
            primitiveArrayStoreHelper(Entrypoints.floatArrayWriteBarrierMethod, inst, next, ir);
          }
        }
        break;

        case INT_ASTORE_opcode: {
          if (NEEDS_INT_ASTORE_BARRIER) {
            primitiveArrayStoreHelper(Entrypoints.intArrayWriteBarrierMethod, inst, next, ir);
          }
        }
        break;

        case LONG_ASTORE_opcode: {
          if (NEEDS_LONG_ASTORE_BARRIER) {
            primitiveArrayStoreHelper(Entrypoints.longArrayWriteBarrierMethod, inst, next, ir);
          }
        }
        break;

        case SHORT_ASTORE_opcode: {
          TypeReference type = AStore.getLocation(inst).getElementType();
          if (NEEDS_SHORT_ASTORE_BARRIER && type.isShortType()) {
            primitiveArrayStoreHelper(Entrypoints.shortArrayWriteBarrierMethod, inst, next, ir);
          } else if (NEEDS_CHAR_ASTORE_BARRIER) {
            if (VM.VerifyAssertions) VM._assert(type.isCharType());
            primitiveArrayStoreHelper(Entrypoints.charArrayWriteBarrierMethod, inst, next, ir);
          }
        }
        break;

        case REF_ALOAD_opcode: {
          if (NEEDS_OBJECT_ALOAD_BARRIER) {
            // Octet: we can't support barriers that aren't heap reference write barriers
            if (VM.VerifyAssertions) { VM._assert(false); }
            
            RVMMethod target = Entrypoints.objectArrayReadBarrierMethod;
            Instruction rb =
              Call.create2(CALL,
                           ALoad.getClearResult(inst),
                           IRTools.AC(target.getOffset()),
                           MethodOperand.STATIC(target),
                           ALoad.getClearGuard(inst),
                           ALoad.getArray(inst).copy(),
                           ALoad.getIndex(inst).copy());
            rb.bcIndex = RUNTIME_SERVICES_BCI;
            rb.position = inst.position;
            inst.replace(rb);
            next = rb.prevInstructionInCodeOrder();
            inline(rb, ir, true);
          }
        }
        break;

        case PUTFIELD_opcode: {
          if (NEEDS_OBJECT_PUTFIELD_BARRIER) {
            LocationOperand loc = PutField.getLocation(inst);
            FieldReference fieldRef = loc.getFieldRef();
            if (!fieldRef.getFieldContentsType().isPrimitiveType()) {
              // reference PUTFIELD
              RVMField field = fieldRef.peekResolvedField();
              if (field == null || !field.isUntraced()) {
                RVMMethod target = Entrypoints.objectFieldWriteBarrierMethod;
                
                // Octet: If the instruction might need an Octet barrier, let's leave the instruction alone and instead insert a "pre-barrier"
                boolean usePreBarrier = mightNeedOctetBarrier(inst);
                if (usePreBarrier) {
                  // Don't add Jikes write barriers twice
                  if (instsWithWritePreBarrier.contains(inst)) {
                    break;
                  }
                  target = Entrypoints.objectFieldWritePreBarrierMethod;
                }
                
                Instruction wb =
                    Call.create4(CALL,
                                 null,
                                 IRTools.AC(target.getOffset()),
                                 MethodOperand.STATIC(target),
                                 PutField.getClearGuard(inst),
                                 PutField.getRef(inst).copy(),
                                 PutField.getValue(inst).copy(),
                                 PutField.getOffset(inst).copy(),
                                 IRTools.IC(fieldRef.getId()));
                wb.bcIndex = RUNTIME_SERVICES_BCI;
                wb.position = inst.position;
                
                // Octet: If inserting a pre-barrier, then don't replace the instruction
                if (usePreBarrier) {
                  inst.insertBefore(wb);
                  instsWithWritePreBarrier.add(inst);
                } else {
                  inst.replace(wb);
                }
                
                next = wb.prevInstructionInCodeOrder();
                if (ir.options.H2L_INLINE_WRITE_BARRIER) {
                  inline(wb, ir, true);
                }
              }
            } else {
              // primitive PUTFIELD
              if (NEEDS_BOOLEAN_PUTFIELD_BARRIER && fieldRef.getFieldContentsType().isBooleanType()) {
                primitiveObjectFieldStoreHelper(Entrypoints.booleanFieldWriteBarrierMethod, inst, next, ir, fieldRef);
              } else if (NEEDS_BYTE_PUTFIELD_BARRIER && fieldRef.getFieldContentsType().isByteType()) {
                primitiveObjectFieldStoreHelper(Entrypoints.byteFieldWriteBarrierMethod, inst, next, ir, fieldRef);
              } else if (NEEDS_CHAR_PUTFIELD_BARRIER && fieldRef.getFieldContentsType().isCharType()) {
                primitiveObjectFieldStoreHelper(Entrypoints.charFieldWriteBarrierMethod, inst, next, ir, fieldRef);
              } else if (NEEDS_DOUBLE_PUTFIELD_BARRIER && fieldRef.getFieldContentsType().isDoubleType()) {
                primitiveObjectFieldStoreHelper(Entrypoints.doubleFieldWriteBarrierMethod, inst, next, ir, fieldRef);
              } else if (NEEDS_FLOAT_PUTFIELD_BARRIER && fieldRef.getFieldContentsType().isFloatType()) {
                primitiveObjectFieldStoreHelper(Entrypoints.floatFieldWriteBarrierMethod, inst, next, ir, fieldRef);
              } else if (NEEDS_INT_PUTFIELD_BARRIER && fieldRef.getFieldContentsType().isIntType()) {
                primitiveObjectFieldStoreHelper(Entrypoints.intFieldWriteBarrierMethod, inst, next, ir, fieldRef);
              } else if (NEEDS_LONG_PUTFIELD_BARRIER && fieldRef.getFieldContentsType().isLongType()) {
                primitiveObjectFieldStoreHelper(Entrypoints.longFieldWriteBarrierMethod, inst, next, ir, fieldRef);
              } else if (NEEDS_SHORT_PUTFIELD_BARRIER && fieldRef.getFieldContentsType().isShortType()) {
                primitiveObjectFieldStoreHelper(Entrypoints.shortFieldWriteBarrierMethod, inst, next, ir, fieldRef);
              } else if (NEEDS_WORD_PUTFIELD_BARRIER && fieldRef.getFieldContentsType().isWordType()) {
                primitiveObjectFieldStoreHelper(Entrypoints.wordFieldWriteBarrierMethod, inst, next, ir, fieldRef);
              } else if (NEEDS_ADDRESS_PUTFIELD_BARRIER && fieldRef.getFieldContentsType().isAddressType()) {
                primitiveObjectFieldStoreHelper(Entrypoints.addressFieldWriteBarrierMethod, inst, next, ir, fieldRef);
              } else if (NEEDS_EXTENT_PUTFIELD_BARRIER && fieldRef.getFieldContentsType().isExtentType()) {
                primitiveObjectFieldStoreHelper(Entrypoints.extentFieldWriteBarrierMethod, inst, next, ir, fieldRef);
              } else if (NEEDS_OFFSET_PUTFIELD_BARRIER && fieldRef.getFieldContentsType().isOffsetType()) {
                primitiveObjectFieldStoreHelper(Entrypoints.offsetFieldWriteBarrierMethod, inst, next, ir, fieldRef);
              }
            }
          }
        }
        break;

        case GETFIELD_opcode: {
          if (NEEDS_OBJECT_GETFIELD_BARRIER) {
            // Octet: we can't support barriers that aren't heap reference write barriers
            if (VM.VerifyAssertions) { VM._assert(false); }
            
            LocationOperand loc = GetField.getLocation(inst);
            FieldReference fieldRef = loc.getFieldRef();
            if (GetField.getResult(inst).getType().isReferenceType()) {
              RVMField field = fieldRef.peekResolvedField();
              if (field == null || !field.isUntraced()) {
                RVMMethod target = Entrypoints.objectFieldReadBarrierMethod;
                Instruction rb =
                  Call.create3(CALL,
                               GetField.getClearResult(inst),
                               IRTools.AC(target.getOffset()),
                               MethodOperand.STATIC(target),
                               GetField.getClearGuard(inst),
                               GetField.getRef(inst).copy(),
                               GetField.getOffset(inst).copy(),
                               IRTools.IC(fieldRef.getId()));
                rb.bcIndex = RUNTIME_SERVICES_BCI;
                rb.position = inst.position;
                inst.replace(rb);
                next = rb.prevInstructionInCodeOrder();
                inline(rb, ir, true);
              }
            }
          }
        }
        break;

        case PUTSTATIC_opcode: {
          if (NEEDS_OBJECT_PUTSTATIC_BARRIER) {
            // Octet: we can't support barriers that aren't heap reference write barriers
            if (VM.VerifyAssertions) { VM._assert(false); }
            
            LocationOperand loc = PutStatic.getLocation(inst);
            FieldReference field = loc.getFieldRef();
            if (!field.getFieldContentsType().isPrimitiveType()) {
              RVMMethod target = Entrypoints.objectStaticWriteBarrierMethod;
              Instruction wb =
                  Call.create3(CALL,
                               null,
                               IRTools.AC(target.getOffset()),
                               MethodOperand.STATIC(target),
                               PutStatic.getValue(inst).copy(),
                               PutStatic.getOffset(inst).copy(),
                               IRTools.IC(field.getId()));
              wb.bcIndex = RUNTIME_SERVICES_BCI;
              wb.position = inst.position;
              inst.replace(wb);
              next = wb.prevInstructionInCodeOrder();
              if (ir.options.H2L_INLINE_WRITE_BARRIER) {
                inline(wb, ir, true);
              }
            }
          }
        }
        break;

        case GETSTATIC_opcode: {
          // Octet: Change System.out/err/in to VMSystem.out/err/in if in the VM context.
          if (inst.position.getMethod().getStaticContext() == Context.VM_CONTEXT) {
            FieldReference oldFieldRef = GetStatic.getLocation(inst).getFieldRef();
            FieldReference newFieldRef = oldFieldRef; 
            if (oldFieldRef == Entrypoints.systemOut) {
              newFieldRef = Entrypoints.vmSystemOut;
            } else if (oldFieldRef == Entrypoints.systemErr) {
              newFieldRef = Entrypoints.vmSystemErr;
            } else if (oldFieldRef == Entrypoints.systemIn) { 
              newFieldRef = Entrypoints.vmSystemIn;
            }
            if (newFieldRef != oldFieldRef) {
              GetStatic.setLocation(inst, new LocationOperand(newFieldRef));
              GetStatic.setOffset(inst, IRTools.AC(newFieldRef.peekResolvedField().getOffset()));
            }
          }
          
          if (NEEDS_OBJECT_GETSTATIC_BARRIER) {
            // Octet: we can't support barriers that aren't heap reference write barriers
            if (VM.VerifyAssertions) { VM._assert(false); }
            
            LocationOperand loc = GetStatic.getLocation(inst);
            FieldReference field = loc.getFieldRef();
            if (!field.getFieldContentsType().isPrimitiveType()) {
              RVMMethod target = Entrypoints.objectStaticReadBarrierMethod;
              Instruction rb =
                  Call.create2(CALL,
                               GetStatic.getClearResult(inst),
                               IRTools.AC(target.getOffset()),
                               MethodOperand.STATIC(target),
                               GetStatic.getOffset(inst).copy(),
                               IRTools.IC(field.getId()));
              rb.bcIndex = RUNTIME_SERVICES_BCI;
              rb.position = inst.position;
              inst.replace(rb);
              next = rb.prevInstructionInCodeOrder();
              inline(rb, ir, true);
            }
          }
        }
        break;

        default:
          break;
      }
    }

    // If we actually inlined anything, clean up the mess
    if (didSomething) {
      if (branchOpts == null) {
        branchOpts = new BranchOptimizations(-1, true, true);
      }
      branchOpts.perform(ir, true);
      if (_os == null) {
        _os = new Simple(1, false, false, false, false);
      }
      _os.perform(ir);
    }
    // signal that we do not intend to use the gc in other phases anymore.
    ir.gc.close();
  }

  /** Octet: Check whether an instruction needs to be instrumented by Octet. */
  @Inline
  boolean mightNeedOctetBarrier(Instruction inst) {
    if (Octet.getConfig().insertBarriers() &&
        Octet.getConfig().instrumentOptimizingCompiler()) {
      if (inst.isPossibleSharedMemoryAccess()) {
        if (VM.VerifyAssertions) { VM._assert(Octet.getClientAnalysis().useLateOptInstrumentation()); } 
        return true;
      }
    }
    return false;
  }

  /**
   * Inline a call instruction
   */
  private void inline(Instruction inst, IR ir) {
    inline(inst, ir, false);
  }

  /**
   * Inline a call instruction
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
    didSomething = true;
  }

  /**
   * Helper method to generate call to primitive arrayStore write barrier
   * @param target entry point for write barrier method
   * @param inst the current instruction
   * @param next the next instruction
   * @param ir the IR
   */
  private void primitiveArrayStoreHelper(RVMMethod target, Instruction inst, Instruction next, IR ir) {
    // Octet: we can't support barriers that aren't heap reference write barriers
    if (VM.VerifyAssertions) { VM._assert(false); }
    
    Instruction wb =
      Call.create3(CALL,
                   null,
                   IRTools.AC(target.getOffset()),
                   MethodOperand.STATIC(target),
                   AStore.getClearGuard(inst),
                   AStore.getArray(inst).copy(),
                   AStore.getIndex(inst).copy(),
                   AStore.getValue(inst).copy());
    wb.bcIndex = RUNTIME_SERVICES_BCI;
    wb.position = inst.position;
    inst.replace(wb);
    next = wb.prevInstructionInCodeOrder();
    if (ir.options.H2L_INLINE_WRITE_BARRIER) {
      inline(wb, ir, true);
    }
  }

  /**
   * Helper method to generate call to primitive putfield write barrier
   * @param target entry point for write barrier method
   * @param inst the current instruction
   * @param next the next instruction
   * @param ir the IR
   */
  private void primitiveObjectFieldStoreHelper(RVMMethod target, Instruction inst, Instruction next, IR ir, FieldReference fieldRef) {
    // Octet: we can't support barriers that aren't heap reference write barriers
    if (VM.VerifyAssertions) { VM._assert(false); }
    
    Instruction wb =
      Call.create4(CALL,
                   null,
                   IRTools.AC(target.getOffset()),
                   MethodOperand.STATIC(target),
                   PutField.getClearGuard(inst),
                   PutField.getRef(inst).copy(),
                   PutField.getValue(inst).copy(),
                   PutField.getOffset(inst).copy(),
                   IRTools.IC(fieldRef.getId()));
    wb.bcIndex = RUNTIME_SERVICES_BCI;
    wb.position = inst.position;
    inst.replace(wb);
    next = wb.prevInstructionInCodeOrder();
    if (ir.options.H2L_INLINE_PRIMITIVE_WRITE_BARRIER) {
      inline(wb, ir, true);
    }
  }
}
