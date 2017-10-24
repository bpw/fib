package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class DynEscapeInsVMCollectFailure extends DynEscapeInsVM {

  @Override @Pure public boolean escapeCollectAssertFailure() { return true; }

}
