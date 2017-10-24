package org.jikesrvm.dr.fasttrack;

import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Offset;

@Uninterruptible
public final class EmptyFastTrack extends FastTrack {

  @Override
  public void write(Object md, Offset historyOffset) { }
  
  @Override
  public void read(Object md, Offset historyOffset) { }

}
