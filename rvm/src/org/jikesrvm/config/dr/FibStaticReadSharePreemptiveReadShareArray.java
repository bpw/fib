package org.jikesrvm.config.dr;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class FibStaticReadSharePreemptiveReadShareArray extends FibPreemptiveReadShareArray {

  @Override
  @Pure
  public boolean fibStaticReadShare() {
    return true;
  }
  

}
