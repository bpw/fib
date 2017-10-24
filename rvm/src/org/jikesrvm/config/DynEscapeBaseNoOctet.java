package org.jikesrvm.config;

import org.jikesrvm.compilers.opt.RedundantBarrierRemover;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class DynEscapeBaseNoOctet extends BaseConfig {
  @Override
  @Pure
  @Interruptible
  public RedundantBarrierRemover.AnalysisLevel overrideDefaultRedundantBarrierAnalysisLevel() {
    return RedundantBarrierRemover.AnalysisLevel.NONE;
  }

  @Override @Pure
  public boolean escapeUseJikesInliner() { return true; }
  
//  @Override @Pure public boolean addHeaderWord() { return true; }
//  @Override @Pure public boolean instrumentAllocation() { return true; }
//  @Override @Pure public boolean insertBarriers() { return true; }
  
  @Override @Pure public boolean escapeAddHeaderWord() { return true; }
  
  @Override @Pure public boolean escapeInstrumentAllocation() { return true; }
  
  @Override @Pure public boolean escapeInsertBarriers() { return true; }

}
