package org.jikesrvm.dr.instrument;

import org.jikesrvm.compilers.baseline.ia32.EmptyBaselineInstr;
import org.jikesrvm.compilers.baseline.ia32.OctetBaselineInstr;
import org.jikesrvm.compilers.opt.EmptyOptInstr;
import org.jikesrvm.compilers.opt.OctetOptInstr;
import org.jikesrvm.compilers.opt.RedundantBarrierRemover;
import org.jikesrvm.octet.ClientAnalysis;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class EmptyAnalysis extends ClientAnalysis {

  @Override
  public boolean supportsIrBasedBarriers() {
    // TODO Auto-generated method stub
    return false;
  }

  @Override
  public boolean incRequestCounterForImplicitProtocol() {
    // TODO Auto-generated method stub
    return false;
  }

  /** Support overriding/customizing barrier insertion in the baseline compiler */
  @Interruptible
  public OctetBaselineInstr newBaselineInstr() {
    return new EmptyBaselineInstr();
  }
  
  /** Support overriding/customizing barrier insertion in the opt compiler */
  @Interruptible
  public OctetOptInstr newOptInstr(boolean lateInstr, RedundantBarrierRemover redundantBarrierRemover) {
    return new EmptyOptInstr(lateInstr, redundantBarrierRemover);
  }

}
