package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class OctetForceEarlyInstr extends OctetDefault {

  @Override @Pure
  public boolean forceEarlyInstrumentation() { return true; }

}
