package org.jikesrvm.config;

import org.jikesrvm.octet.PessimisticStateTransfers;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;

/** Pessimistic barriers with CAS but no fence. */
@Uninterruptible
public class PessimisticBarriersNoFence extends PessimisticBarriers {

  /** Use the pessimistic read that skips the fence. */
  @Override @Inline
  public void pessimisticRead(Address addr) {
    PessimisticStateTransfers.readWithoutFence(addr);
  }

  /** Use the pessimistic write that skips the fence. */
  @Override @Inline
  public void pessimisticWrite(Address addr) {
    PessimisticStateTransfers.writeWithoutFence(addr);
  }
}
