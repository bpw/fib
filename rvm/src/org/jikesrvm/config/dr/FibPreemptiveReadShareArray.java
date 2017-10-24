package org.jikesrvm.config.dr;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class FibPreemptiveReadShareArray extends FibArray {

  @Override
  @Pure
  public boolean fibPreemptiveReadShare() {
    return true;
  }

}
