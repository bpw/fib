package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class BaseCustom1 extends BaseConfig {

  @Override @Pure
  public boolean custom1() { return true; }

}
