package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class OctetDefault extends BarriersNoComm {

  @Override @Pure
  public boolean doCommunication() { return true; }

}
