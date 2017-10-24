package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class OctetNoRbaStats extends OctetNoRba {

  @Override
  @Pure
  public boolean stats() {
    return true;
  }

  /** Required because stats are being used. */
  @Override
  @Pure
  public boolean forceUseJikesInliner() {
    return true;
  }

}
