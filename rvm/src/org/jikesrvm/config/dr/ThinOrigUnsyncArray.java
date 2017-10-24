package org.jikesrvm.config.dr;

import org.vmmagic.pragma.Pure;

public class ThinOrigUnsyncArray extends OrigUnsyncArray {

  @Override
  @Pure
  public InlineLevel drInlineRaceChecks() {
    return InlineLevel.THIN;
  }

}
