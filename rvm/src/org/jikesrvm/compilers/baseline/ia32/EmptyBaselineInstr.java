package org.jikesrvm.compilers.baseline.ia32;

import org.jikesrvm.ArchitectureSpecific.Assembler;
import org.jikesrvm.classloader.FieldReference;
import org.jikesrvm.classloader.NormalMethod;
import org.jikesrvm.classloader.RVMField;
import org.jikesrvm.classloader.TypeReference;
import org.vmmagic.unboxed.Offset;

public class EmptyBaselineInstr extends OctetBaselineInstr {
  
  @Override
  /** Barrier for resolved static fields */
  boolean insertStaticBarrierResolved(NormalMethod method, int biStart, boolean isRead, RVMField field, Assembler asm) {
    
    // FIXME Add volatiles in all field barrier generators.
    return false;
  }

  @Override
  /** Barrier for unresolved static fields */
  boolean insertStaticBarrierUnresolved(NormalMethod method, int biStart, boolean isRead, GPR offsetReg, FieldReference fieldRef, Assembler asm) {
    return false;
  }
  
  /** Barrier for resolved non-static fields */
  boolean insertFieldBarrierResolved(NormalMethod method, int biStart, boolean isRead, Offset numSlots, RVMField field, Assembler asm) {
    return false;
  }

  /** Barrier for unresolved non-static fields */
  boolean insertFieldBarrierUnresolved(NormalMethod method, int biStart, boolean isRead, Offset numSlots, GPR offsetReg, FieldReference fieldRef, Assembler asm) {
    return false;
  }
  
  /** Barrier for arrays */
  boolean insertArrayBarrier(NormalMethod method, int biStart, boolean isRead, Offset numSlots, TypeReference type, Assembler asm) {
    return false;
  }

}
