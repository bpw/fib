package org.jikesrvm.config.dr;

import org.vmmagic.pragma.Pure;

public class CasArrayNoInline extends CasArray {

  @Override
  @Pure
  public Base.InlineLevel drInlineRaceChecks() {
    return InlineLevel.NONE;
  }
}
