package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class DynEscapeVMEscapeStats extends DynEscapeVMContextEscape {

  @Override @Pure public boolean escapeStats() { return true; }

  @Override @Pure public boolean escapePassSite() { return true; }
}
