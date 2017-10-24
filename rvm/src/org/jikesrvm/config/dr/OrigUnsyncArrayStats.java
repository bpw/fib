package org.jikesrvm.config.dr;

import org.vmmagic.pragma.Pure;

public class OrigUnsyncArrayStats extends OrigUnsyncArray {

  @Override
  @Pure
  public boolean stats() {
    return true;
  }

}
