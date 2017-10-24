package org.jikesrvm.config.dr;

import org.jikesrvm.dr.metadata.maps.ArrayEpochMapper;
import org.jikesrvm.dr.metadata.maps.EpochMapper;
import org.vmmagic.pragma.Pure;

public class FibArray extends FibFastTrackBase {

  @Override
  @Pure
  public EpochMapper newEpochMapper() {
    return new ArrayEpochMapper();
  }

}
