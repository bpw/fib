package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class DynEscapeOneBitVMEscape extends DynEscapeOneBitBase {

  @Override @Pure public boolean escapeVMContextEscape() { return true; }

}
