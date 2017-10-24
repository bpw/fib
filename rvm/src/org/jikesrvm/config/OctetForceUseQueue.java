package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class OctetForceUseQueue extends OctetDefault {

  @Override @Pure
  public boolean forceUseCommunicationQueue() { return true; }

}
