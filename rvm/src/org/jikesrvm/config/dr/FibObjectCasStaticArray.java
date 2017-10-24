package org.jikesrvm.config.dr;

import org.jikesrvm.dr.fasttrack.CASFastTrack;
import org.jikesrvm.dr.fasttrack.FastTrack;
import org.vmmagic.pragma.Pure;

public class FibObjectCasStaticArray extends FibArray {

  @Override
  @Pure
  public FastTrack newStaticFastTrack() {
    return new CASFastTrack();
  }

}
