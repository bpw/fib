package org.jikesrvm.config.dr;

import org.vmmagic.pragma.Pure;


public class FibBufferArray extends FibArray {

  @Override
  @Pure
  public boolean fibBuffer() {
    return true;
  }

}
