package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class HeaderWordOnly extends BaseConfig {

  @Override @Pure
  public boolean addHeaderWord() { return true; }

}
