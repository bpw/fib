package org.jikesrvm.config.dr;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class FibStickyPreemptiveReadShareArray extends FibPreemptiveReadShareArray {

  @Override
  @Pure
  public boolean fibStickyReadShare() {
    return true;
  }

}
