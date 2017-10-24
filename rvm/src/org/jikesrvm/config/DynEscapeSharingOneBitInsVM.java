package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class DynEscapeSharingOneBitInsVM extends DynEscapeSharingOneBitBase {

  @Override @Pure public boolean escapeInstrumentVM() { return true; }

}
