package org.jikesrvm.config;

import org.jikesrvm.compilers.opt.RedundantBarrierRemover;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class DynEscapeOneBitBase extends BaseConfig {
  @Override
  @Pure
  @Interruptible
  public RedundantBarrierRemover.AnalysisLevel overrideDefaultRedundantBarrierAnalysisLevel() {
    return RedundantBarrierRemover.AnalysisLevel.NONE;
  }

  @Override @Pure
  public boolean escapeUseJikesInliner() { return true; }
  
  @Override @Pure public boolean escapeTraceOutEdgesGCStyle() { return true; }
  
  @Override @Pure public boolean escapeUseOneBitInGCHeader() { return true; }
  
  @Override @Pure public boolean escapeInsertBarriers() { return true; }
  
  @Override @Pure public boolean escapePassSite() { return false; }

}
