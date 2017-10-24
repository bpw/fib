package org.jikesrvm.config.dr;

import org.jikesrvm.dr.fasttrack.FastTrack;
import org.jikesrvm.dr.fasttrack.OrigCasFastTrack;
import org.jikesrvm.dr.metadata.maps.ArrayEpochMapper;
import org.jikesrvm.dr.metadata.maps.EpochMapper;
import org.vmmagic.pragma.Pure;

public class OrigCasArray extends FastTrackBase {

  @Override
  @Pure
  public FastTrack newFastTrack() {
    return new OrigCasFastTrack();
  }

  @Override
  @Pure
  public EpochMapper newEpochMapper() {
    return new ArrayEpochMapper();
  }

}
