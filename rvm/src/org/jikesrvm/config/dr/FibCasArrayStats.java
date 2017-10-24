package org.jikesrvm.config.dr;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class FibCasArrayStats extends FibCasArray {
  @Override
  @Pure
  public boolean stats() {
    return true;
  }

}
