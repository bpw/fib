package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class DynEscapeSharingOneBitInsVMStats extends DynEscapeSharingOneBitInsVM {

  @Override @Pure public boolean escapeStats() { return true; }

  @Override @Pure public boolean escapePassSite() { return true; }
  
  @Override @Pure public boolean escapeAddHeaderWord() { return false; }
  
  @Override @Pure public boolean escapeInstrumentAllocation() { return false; }
}
