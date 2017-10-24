package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class BarriersNoCommForceNoInlining extends BarriersNoComm {

  @Override @Pure
  public boolean inlineBarriers() { return false; }

}
