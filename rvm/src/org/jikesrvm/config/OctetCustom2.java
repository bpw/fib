package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class OctetCustom2 extends OctetDefault {

  @Override @Pure
  public boolean custom2() { return true; }

}
