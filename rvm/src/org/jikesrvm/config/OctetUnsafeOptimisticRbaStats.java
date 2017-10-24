package org.jikesrvm.config;

import org.jikesrvm.compilers.opt.RedundantBarrierRemover;
import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

/**
 * A config for collecting stats in Octet when {@link RedundantBarrierRemover}
 * analysis is not enabled.
 * @author Meisam
 *
 */
@Uninterruptible
public class OctetUnsafeOptimisticRbaStats extends OctetUnsafeOptimisticRba {

  @Override @Pure
  public boolean stats() { return true; }

  // Stats must disable IR-based barriers to get correct results
  
  @Override @Pure
  public boolean forceUseJikesInliner() { return true; }

}
