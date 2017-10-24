package org.jikesrvm.config.dr;

import org.vmmagic.pragma.Pure;

public class FibArrayNoInline extends FibArray {

  @Override
  @Pure
  public Base.InlineLevel drInlineRaceChecks() {
    return InlineLevel.NONE;
  }
}
