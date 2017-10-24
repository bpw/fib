package org.jikesrvm.config.dr;

import org.jikesrvm.dr.fasttrack.FastTrack;
import org.jikesrvm.dr.fasttrack.OrigUnsyncFastTrack;
import org.jikesrvm.dr.metadata.maps.ArrayEpochMapper;
import org.jikesrvm.dr.metadata.maps.EpochMapper;
import org.vmmagic.pragma.Pure;

public class OrigUnsyncArray extends FastTrackBase {

  @Override
  @Pure
  public FastTrack newFastTrack() {
    return new OrigUnsyncFastTrack();
  }

  @Override
  @Pure
  public EpochMapper newEpochMapper() {
    return new ArrayEpochMapper();
  }

}
