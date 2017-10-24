package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class OctetDefaultStaticRaceFiltering extends OctetDefault {

  @Override @Pure
  public boolean enableStaticRaceDetection() { return true; }

}
