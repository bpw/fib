package org.jikesrvm.dr.fib;

import org.jikesrvm.dr.metadata.AccessHistory;
import org.jikesrvm.dr.metadata.Epoch;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;

@Uninterruptible
public class FibCas extends Threshold {
  protected static final boolean PRINT = false;
  
  @Inline
  protected static void begin(Object md, Offset historyOffset, Word epoch) {
    AccessHistory.storeReadWord(md, historyOffset, Epoch.asAlt(epoch));
  }
}
