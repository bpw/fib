package org.jikesrvm.compilers.opt;

import static org.jikesrvm.compilers.opt.ir.Operators.CALL;

import org.jikesrvm.VM;
import org.jikesrvm.classloader.FieldReference;
import org.jikesrvm.classloader.NormalMethod;
import org.jikesrvm.classloader.RVMField;
import org.jikesrvm.compilers.opt.driver.CompilerPhase;
import org.jikesrvm.compilers.opt.ir.ALoad;
import org.jikesrvm.compilers.opt.ir.AStore;
import org.jikesrvm.compilers.opt.ir.BasicBlock;
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
import org.jikesrvm.compilers.opt.ir.operand.Operand;
import org.jikesrvm.dr.Dr;
import org.jikesrvm.dr.DrStats;
import org.jikesrvm.octet.InstrDecisions;
import org.jikesrvm.octet.Octet;
import org.jikesrvm.runtime.Magic;
import org.jikesrvm.util.HashSetRVM;
import org.vmmagic.pragma.Uninterruptible;


public class EmptyOptInstr extends OctetOptInstr {

  @Override
  public String getName() {
    return "Empty instrumentation";
  }

  public EmptyOptInstr(boolean lateInstr, RedundantBarrierRemover redundantBarrierRemover) {
    super(lateInstr, redundantBarrierRemover);
  }

  @Override
  public void perform(IR ir) {
  }

  @Override
  public void instrumentOtherInstTypes(Instruction inst,
      HashSetRVM<Instruction> callsToInline, IR ir) {
  }

  @Override
  void instrumentInst(Instruction inst, HashSetRVM<Instruction> callsToInline,
      IR ir) {
  }

  @Override
  void instrumentScalarAccess(Instruction inst,
      HashSetRVM<Instruction> callsToInline, IR ir) {
  }

  @Override
  void instrumentArrayAccess(Instruction inst,
      HashSetRVM<Instruction> callsToInline, IR ir) {
  }

  @Override
  void instrumentStaticAccess(Instruction inst,
      HashSetRVM<Instruction> callsToInline, IR ir) {
  }

  @Override
  void finishParams(Instruction inst, FieldReference fieldRef,
      Instruction barrier) {
  }

  @Override
  void passSite(Instruction inst, Instruction barrier) {
  }

  @Override
  void passExtra(Instruction inst, FieldReference fieldRef, Instruction barrier) {
  }

  @Override
  void addParam(Instruction call, Operand operand) {
  }

  @Override
  void insertBarrier(Instruction barrierCall, Instruction inst, boolean isRead,
      Operand refOperand, RVMField field, boolean isResolved,
      HashSetRVM<Instruction> callsToInline, IR ir) {
  }

  @Override
  void makeRedundantBarriersSafe(Instruction inst, Instruction slowPath, IR ir) {
  }
  


}
