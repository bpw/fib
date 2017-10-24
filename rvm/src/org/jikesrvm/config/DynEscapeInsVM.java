package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class DynEscapeInsVM extends DynEscapeBaseNoOctet {

  @Override @Pure public boolean escapeInstrumentVM() { return true; }

}
