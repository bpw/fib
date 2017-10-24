package org.jikesrvm.config.dr;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class SyncTrackingOnly extends Base {
  @Override
  @Pure
  public boolean syncTracking() {
    return true;
  }
  @Override
  @Pure
  public boolean collapseEpochs() {
    return true;
  }
}
