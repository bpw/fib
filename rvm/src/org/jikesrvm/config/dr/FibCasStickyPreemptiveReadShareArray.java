package org.jikesrvm.config.dr;

import org.vmmagic.pragma.Pure;

public class FibCasStickyPreemptiveReadShareArray extends FibCasPreemptiveReadShareArray {

  @Override
  @Pure
  public boolean fibStickyReadShare() {
    return true;
  }

}
