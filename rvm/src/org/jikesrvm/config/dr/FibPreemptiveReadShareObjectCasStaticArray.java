package org.jikesrvm.config.dr;

import org.jikesrvm.dr.fasttrack.CASFastTrack;
import org.jikesrvm.dr.fasttrack.FastTrack;
import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class FibPreemptiveReadShareObjectCasStaticArray extends FibPreemptiveReadShareArray {

  @Override
  @Pure
  public FastTrack newStaticFastTrack() {
    return new CASFastTrack();
  }

}
