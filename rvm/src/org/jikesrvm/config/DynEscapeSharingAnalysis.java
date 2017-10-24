package org.jikesrvm.config;

import org.jikesrvm.octet.ClientAnalysis;
import org.jikesrvm.esc.sharing.DynamicSharingAnalysis;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class DynEscapeSharingAnalysis extends DynEscapeBaseNoOctet {

  @Interruptible
  public ClientAnalysis constructClientAnalysis() { return new DynamicSharingAnalysis(); }
  
  @Override @Pure public boolean addHeaderWord() { return true; }
  
  @Override @Pure public boolean instrumentAllocation() { return true; }
  
  @Override @Pure public boolean insertBarriers() { return true; }

}
