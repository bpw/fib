package org.jikesrvm.config.dr;

import org.jikesrvm.dr.fasttrack.FastTrack;
import org.jikesrvm.dr.fasttrack.UnsyncFastTrack;
import org.jikesrvm.dr.metadata.maps.ArrayEpochMapper;
import org.jikesrvm.dr.metadata.maps.EpochMapper;
import org.vmmagic.pragma.Pure;

public class UnsyncArray extends FastTrackBase {

  @Override
  @Pure
  public FastTrack newFastTrack() {
    return new UnsyncFastTrack();
  }

  @Override
  @Pure
  public EpochMapper newEpochMapper() {
    return new ArrayEpochMapper();
  }

}
