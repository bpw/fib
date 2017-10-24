package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class HeaderWordAlloc extends HeaderWordOnly {

  @Override @Pure
  public boolean instrumentAllocation() { return true; }

}
