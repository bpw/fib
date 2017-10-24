package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class OctetCheckRdEx extends OctetDefault {

  @Override @Pure
  public boolean forceCheckBothExclStateIRBasedBarriers() { return true; }

}
