package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class OctetForceUseQueueAndHoldState extends OctetForceUseQueue {

  @Override @Pure
  public boolean forceUseHoldState() { return true; }

}
