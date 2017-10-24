package org.jikesrvm.config.dr;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class FibArrayHeavy extends FibArray {

  @Override
  @Pure
  public boolean fibHeavyTransfers() {
    return true;
  }
}
