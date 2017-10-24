package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class BaseCustom2 extends BaseConfig {

  @Override @Pure
  public boolean custom2() { return true; }

}
