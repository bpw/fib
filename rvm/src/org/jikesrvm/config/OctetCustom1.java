package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class OctetCustom1 extends OctetDefault {

  @Override @Pure
  public boolean custom1() { return true; }

}
