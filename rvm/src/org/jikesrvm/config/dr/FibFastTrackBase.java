package org.jikesrvm.config.dr;

import org.jikesrvm.dr.fasttrack.FastTrack;
import org.jikesrvm.dr.fib.FibFastTrack;
import org.jikesrvm.dr.instrument.FastTrackAnalysis;
import org.jikesrvm.octet.ClientAnalysis;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public abstract class FibFastTrackBase extends SyncTrackingOnly {

  @Override
  @Pure
  public boolean insertBarriers() {
    return true;
  }
  
  /** Construct the client analysis to use. By default, "null" analysis. */
  @Interruptible
  @Pure
  public final ClientAnalysis constructClientAnalysis() { return new FastTrackAnalysis(); }
  
  @Interruptible
  @Override
  @Pure
  public final FastTrack newFastTrack() {
    return new FibFastTrack();
  }

  @Override
  @Pure
  public final boolean fibCommunication() {
    return true;
  }
}
