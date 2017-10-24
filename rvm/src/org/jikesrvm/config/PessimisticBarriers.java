package org.jikesrvm.config;

import org.jikesrvm.octet.PessimisticStateTransfers;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;

/** Use pessimistic barriers, which use a CAS or fence instead of using Octet's optimistic barriers.
    On the other hand, pessimistic barriers don't need communication.
    They can't use IR-based inlining since there isn't support for that in OctetOptInstr. */
@Uninterruptible
public class PessimisticBarriers extends BarriersNoComm {

  @Override @Pure
  public boolean usePessimisticBarriers() { return true; }

  @Override @Pure
  public boolean forceUseJikesInliner() { return true; }
  
  /** Use the pessimistic read that performs a fence. */
  @Override @Inline
  public void pessimisticRead(Address addr) {
    PessimisticStateTransfers.readWithFence(addr);
  }

  /** Use the pessimistic write that performs a fence. */
  @Override @Inline
  public void pessimisticWrite(Address addr) {
    PessimisticStateTransfers.writeWithFence(addr);
  }
}
