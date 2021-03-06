package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class DynEscapeOneBitInsVMCheckAssertion extends DynEscapeOneBitInsVM {

  @Override @Pure public boolean escapeAddHeaderWord() { return true; }
  
  @Override @Pure public boolean escapeInstrumentAllocation() { return true; }

}
