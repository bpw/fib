package org.jikesrvm.config.dr;

import org.vmmagic.pragma.Pure;

public class FibCasPreemptiveReadShareArray extends FibCasArray {

  @Override
  @Pure
  public boolean fibPreemptiveReadShare() {
    return true;
  }

}
