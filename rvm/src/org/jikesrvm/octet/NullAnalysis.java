/** Octet: a client analysis that doesn't really do anything */
package org.jikesrvm.octet;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public final class NullAnalysis extends ClientAnalysis {

  @Override
  public final boolean supportsIrBasedBarriers() {
    // OctetOptInstr will insert IR instructions that are correct for the basic Octet barriers,
    // but not for any client analyses that modify the default Octet barrier behavior.
    return true;
  }
  
  @Override
  @Pure
  public boolean incRequestCounterForImplicitProtocol() {
    return false;
  }
}
