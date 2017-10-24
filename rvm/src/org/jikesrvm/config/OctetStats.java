package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class OctetStats extends OctetDefault {

  @Override @Pure
  public boolean stats() { return true; }

  // Stats must disable IR-based inlining to get correct results
  
  @Override @Pure
  public boolean forceUseJikesInliner() { return true; }
  
}
