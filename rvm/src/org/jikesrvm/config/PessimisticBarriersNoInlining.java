package org.jikesrvm.config;

import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

/** A configuration that doesn't inline pessimistic barriers,
    to help determine if they're stressing the opt-compiler. */
@Uninterruptible
public class PessimisticBarriersNoInlining extends PessimisticBarriers {

  /** Don't inline barriers. */
  @Override @Pure
  public boolean inlineBarriers() { return false; }
}
