package org.jikesrvm.config;

import org.jikesrvm.compilers.opt.RedundantBarrierRemover;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

/**
 * A config for collecting stats in Octet when {@link RedundantBarrierRemover}
 * analysis is not enabled.
 * @author Meisam
 *
 */
@Uninterruptible
public class OctetUnsafeOptimisticRba extends OctetDefault {

  @Override
  @Pure
  @Interruptible
  public RedundantBarrierRemover.AnalysisLevel overrideDefaultRedundantBarrierAnalysisLevel() {
    return RedundantBarrierRemover.AnalysisLevel.OPTIMISTIC_UNSAFE;
  }

}
