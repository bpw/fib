package org.jikesrvm.config.dr;

import org.vmmagic.pragma.Pure;

public class FibStaticReadShareArray extends FibArray {

  @Override
  @Pure
  public boolean fibStaticReadShare() {
    return true;
  }

}
