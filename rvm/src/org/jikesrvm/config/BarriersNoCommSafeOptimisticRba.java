package org.jikesrvm.config;

import org.jikesrvm.compilers.opt.RedundantBarrierRemover;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class BarriersNoCommSafeOptimisticRba extends BarriersNoComm {

  @Override
  @Pure
  @Interruptible
  public RedundantBarrierRemover.AnalysisLevel overrideDefaultRedundantBarrierAnalysisLevel() {
    return RedundantBarrierRemover.AnalysisLevel.OPTIMISTIC_SAFE;
  }

  /** Required because safe optimistic RBA is being used. */
  @Override
  @Pure
  public boolean forceEarlyInstrumentation() {
    return true;
  }
  
}
