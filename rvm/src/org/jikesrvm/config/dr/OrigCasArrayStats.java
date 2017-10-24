package org.jikesrvm.config.dr;

import org.vmmagic.pragma.Pure;

public class OrigCasArrayStats extends OrigCasArray {

  @Override
  @Pure
  public boolean stats() {
    return true;
  }

}
