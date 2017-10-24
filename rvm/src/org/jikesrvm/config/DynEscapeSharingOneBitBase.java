package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class DynEscapeSharingOneBitBase extends DynEscapeSharingAnalysis {

  @Override @Pure public boolean escapeUseOneBitInGCHeader() { return true; }

}
