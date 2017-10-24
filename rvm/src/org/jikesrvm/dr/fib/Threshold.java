package org.jikesrvm.dr.fib;

import org.jikesrvm.VM;
import org.jikesrvm.dr.Dr;
import org.jikesrvm.dr.DrDebug;
import org.jikesrvm.dr.DrStats;
import org.jikesrvm.dr.metadata.AccessHistory;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;

/**
 * Counter management for adaptive transitions between FIB and CAS mode.
 * @author bpw
 *
 */
@Uninterruptible
public class Threshold {
  // Counter word:
  private static final int COUNTER_BITS = Dr.config().fibThresholdBits();
  private static final Word COUNTER_MASK = Word.fromIntSignExtend((1<<COUNTER_BITS)-1);

  /**
   * Bill a conflict.
   * 
   * @param md
   * @param historyOffset
   * @return
   */
  protected static boolean threshold(Object md, Offset historyOffset) {
    if (Dr.STATS) DrStats.thresholdChecked.inc();

    Word count = loadCount(md, historyOffset);
    if (count.GT(COUNTER_MASK)) {
      if (Dr.STATS) DrStats.thresholdPassed.inc();

      if (FibCas.PRINT) {
        DrDebug.lock();
        DrDebug.twrite();
        VM.sysWriteln("  threshold passed ", AccessHistory.address(md, historyOffset), " #", count.toInt());
        DrDebug.unlock();
      }

      return false;
    }
    count = count.plus(Word.one()).and(COUNTER_MASK);
    storeCount(md, historyOffset, count);
    
    if (count.isZero()) {
      if (Dr.STATS) DrStats.thresholdReached.inc();
      
      if (FibCas.PRINT) {
        DrDebug.lock();
        DrDebug.twrite();
        VM.sysWriteln("  threshold reached ", AccessHistory.address(md, historyOffset), " #", count.toInt());
        DrDebug.unlock();
      }

    }
    
    return count.isZero();
  }
  
  protected static void resetCount(Object md, Offset historyOffset) {
    if (FibCas.PRINT) {
      DrDebug.lock();
      DrDebug.twrite();
      VM.sysWriteln("  threshold reset ", AccessHistory.address(md, historyOffset));
      DrDebug.unlock();
    }

    storeCount(md, historyOffset, Word.zero());
  }
  
  protected static void pinCount(Object md, Offset historyOffset) {
    if (FibCas.PRINT) {
      DrDebug.lock();
      DrDebug.twrite();
      VM.sysWriteln("  threshold pin ", AccessHistory.address(md, historyOffset));
      DrDebug.unlock();
    }

    storeCount(md, historyOffset, Word.one().lsh(COUNTER_BITS));
    if (Dr.STATS) DrStats.thresholdPinned.inc();
  }
  
  /**
   * Load the counter.
   * 
   * @param md
   * @param historyOffset
   * @return
   */
  protected static Word loadCount(Object md, Offset historyOffset) {
    return AccessHistory.loadExtra2(md, historyOffset);
  }
  
  /**
   * Store the counter.
   * 
   * @param md
   * @param historyOffset
   * @param count
   */
  protected static void storeCount(Object md, Offset historyOffset, Word count) {
    AccessHistory.storeExtra2(md, historyOffset,count);
  }  
  


}
