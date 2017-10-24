package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class DynEscapeOneBitInsVM extends DynEscapeOneBitBase {

  @Override @Pure public boolean escapeInstrumentVM() { return true; }

}
