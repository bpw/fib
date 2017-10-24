package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class BarriersNoCommForceUseJikesInliner extends BarriersNoComm {

  @Override @Pure
  public boolean forceUseJikesInliner() { return true; }

}
