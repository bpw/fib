package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class OctetForceUseHoldState extends OctetDefault {

  @Override @Pure
  public boolean forceUseHoldState() { return true; }

}
