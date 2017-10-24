package org.jikesrvm.config.dr;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

public class UnsyncArrayNoInline extends UnsyncArray {

  @Uninterruptible
  @Override
  @Pure
  public Base.InlineLevel drInlineRaceChecks() {
    return InlineLevel.NONE;
  }
}
