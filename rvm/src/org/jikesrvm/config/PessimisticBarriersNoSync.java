package org.jikesrvm.config;

import org.jikesrvm.octet.PessimisticStateTransfers;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;

/** Pessimistic barriers that don't actually perform any synchronization.
    For comparison purposes (e.g., to see how much the pessimistic barriers are stressing the opt compiler). */
@Uninterruptible
public class PessimisticBarriersNoSync extends PessimisticBarriers {

  /** Use the pessimistic read that skips all synchronization. */
  @Override @Inline
  public void pessimisticRead(Address addr) {
    PessimisticStateTransfers.readWithoutSync(addr);
  }

  /** Use the pessimistic write that skips all synchronization. */
  @Override @Inline
  public void pessimisticWrite(Address addr) {
    PessimisticStateTransfers.writeWithoutSync(addr);
  }
}
