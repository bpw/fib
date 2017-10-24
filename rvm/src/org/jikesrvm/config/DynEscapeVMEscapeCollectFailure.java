package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class DynEscapeVMEscapeCollectFailure extends DynEscapeVMContextEscape {

  @Override @Pure public boolean escapeCollectAssertFailure() { return true; }

}
