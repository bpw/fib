package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class OctetForceUseJikesInliner extends OctetDefault {

  @Override @Pure
  public boolean forceUseJikesInliner() { return true; }

}
