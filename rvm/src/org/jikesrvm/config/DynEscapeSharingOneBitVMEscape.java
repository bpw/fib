package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class DynEscapeSharingOneBitVMEscape extends DynEscapeSharingOneBitBase {

  @Override @Pure public boolean escapeVMContextEscape() { return true; }

}
