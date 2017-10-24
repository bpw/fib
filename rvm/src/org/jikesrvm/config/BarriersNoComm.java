package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class BarriersNoComm extends HeaderWordAlloc {

  @Override @Pure
  public boolean insertBarriers() { return true; }

}
