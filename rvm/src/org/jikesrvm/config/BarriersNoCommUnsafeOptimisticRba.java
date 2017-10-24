package org.jikesrvm.config;

import org.jikesrvm.compilers.opt.RedundantBarrierRemover;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class BarriersNoCommUnsafeOptimisticRba extends BarriersNoComm {

  @Override
  @Pure
  @Interruptible
  public RedundantBarrierRemover.AnalysisLevel overrideDefaultRedundantBarrierAnalysisLevel() {
    return RedundantBarrierRemover.AnalysisLevel.OPTIMISTIC_UNSAFE;
  }

}
