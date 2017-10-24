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
public class OctetStatsNoRba extends OctetDefault {

  @Override
  @Pure
  @Interruptible
  public RedundantBarrierRemover.AnalysisLevel overrideDefaultRedundantBarrierAnalysisLevel() {
    return RedundantBarrierRemover.AnalysisLevel.NONE;
  }

  @Override @Pure
  public boolean stats() { return true; }

  // Stats must disable inlining to get correct results
  
  @Override @Pure
  public boolean inlineBarriers() { return false; }

}
