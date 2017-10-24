package org.jikesrvm.config.dr;

import org.vmmagic.pragma.Pure;

public class FibCasStickyReadShareArray extends FibCasArray {

  @Override
  @Pure
  public boolean fibStickyReadShare() {
    return true;
  }

}
