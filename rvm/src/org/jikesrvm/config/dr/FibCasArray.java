package org.jikesrvm.config.dr;

import org.vmmagic.pragma.Pure;

public class FibCasArray extends FibArray {

  @Override
  @Pure
  public boolean fibAdaptiveCas() {
    return true;
  }
  
  @Override
  @Pure
  public int drExtraWordsInHistory() {
    return 2; // layout currently does not support non-powers of 2 total sizes... 1;
  }
  
  @Override
  @Pure
  public int epochTagBits() {
    return super.epochTagBits() + 1;
  }

}
