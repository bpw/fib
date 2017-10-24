package org.jikesrvm.config.dr;

import org.vmmagic.pragma.Pure;

public class ThinUnsyncArray extends UnsyncArray {

  @Override
  @Pure
  public InlineLevel drInlineRaceChecks() {
    return InlineLevel.THIN;
  }

}
