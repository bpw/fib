package org.jikesrvm.config.dr;

import org.jikesrvm.dr.fasttrack.CASFastTrack;
import org.jikesrvm.dr.fasttrack.FastTrack;
import org.jikesrvm.dr.metadata.maps.ArrayEpochMapper;
import org.jikesrvm.dr.metadata.maps.EpochMapper;
import org.vmmagic.pragma.Pure;

public class CasArray extends FastTrackBase {

  @Override
  @Pure
  public FastTrack newFastTrack() {
    return new CASFastTrack();
  }

  @Override
  @Pure
  public EpochMapper newEpochMapper() {
    return new ArrayEpochMapper();
  }
  

}
