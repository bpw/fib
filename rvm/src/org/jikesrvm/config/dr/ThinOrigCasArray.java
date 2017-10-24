package org.jikesrvm.config.dr;

import org.vmmagic.pragma.Pure;

public class ThinOrigCasArray extends OrigCasArray {

  @Override
  @Pure
  public InlineLevel drInlineRaceChecks() {
    return InlineLevel.THIN;
  }

}
