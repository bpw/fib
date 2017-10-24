package org.jikesrvm.config.dr;

import org.jikesrvm.dr.fasttrack.FastTrack;
import org.jikesrvm.dr.fasttrack.NaiveSpinLockFastTrack;
import org.jikesrvm.dr.metadata.maps.ArrayEpochMapper;
import org.jikesrvm.dr.metadata.maps.EpochMapper;
import org.vmmagic.pragma.Pure;

public class NaiveCasArray extends FastTrackBase {

  @Override
  @Pure
  public FastTrack newFastTrack() {
    return new NaiveSpinLockFastTrack();
  }

  @Override
  @Pure
  public EpochMapper newEpochMapper() {
    return new ArrayEpochMapper();
  }
  

}
