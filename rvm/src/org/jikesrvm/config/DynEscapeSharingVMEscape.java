package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class DynEscapeSharingVMEscape extends DynEscapeSharingAnalysis {

  @Override @Pure public boolean escapeVMContextEscape() { return true; }

}
