package org.jikesrvm.dr.metadata;

import org.jikesrvm.SizeConstants;
import org.jikesrvm.VM;
import org.jikesrvm.dr.Dr;
import org.jikesrvm.dr.DrDebug;
import org.jikesrvm.mm.mminterface.Barriers;
import org.jikesrvm.mm.mminterface.MemoryManager;
import org.jikesrvm.objectmodel.ObjectModel;
import org.jikesrvm.runtime.Magic;
import org.jikesrvm.scheduler.RVMThread;
import org.mmtk.plan.TraceLocal;
import org.mmtk.plan.TransitiveClosure;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.pragma.Unpreemptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.ObjectReference;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;

/**
 * Access histories are stored as a pair of (32-bit) words,
 * representing the last write and last read(s) of a given
 * field or array element.
 * 
 * The last write word stores an epoch (thread ID and clock).
 * The last read word stores either an epoch or a pointer to
 * an object representing a mapping from thread ID to clock
 * (concurrent last reads).  To distinguish between epochs
 * and pointers in the last read word, the LSB is a tag:
 * when 1, an epoch is stored; when 0, a pointer is stored.
 * (See Epoch)
 * 
 * Access Histories for fields are stored inline in the object
 * (or the statics section).  Access histories for arrays are
 * stored in a shadow array.
 * 
 * NOTE: this is not compatible with the Poisoned GC, which also
 * uses the LSB.
 * 
 * processEdge needs to use the LSB in Last Reads to decide whether it's
 * an epoch (LSB = 1) or a pointer to concurrent readers info (LSB = 0)
 *
 * 
 */
@Uninterruptible
public final class AccessHistory implements SizeConstants {
  static {
    if (VM.VerifyAssertions) {
      VM._assert(VM.buildFor32Addr());
      VM._assert(!Barriers.NEEDS_OBJECT_GETFIELD_BARRIER);
      VM._assert(!Barriers.NEEDS_OBJECT_GETSTATIC_BARRIER);
      VM._assert(!Barriers.NEEDS_OBJECT_PUTSTATIC_BARRIER);
    }
  }

  /**
   * Size of race-checked field's first-order metadata.
   */
  public static final int WORDS_IN_HISTORY = 2 + Dr.config().drExtraWordsInHistory();
  public static final int BYTES_IN_HISTORY = WORDS_IN_HISTORY * BYTES_IN_WORD;

  protected static final int READ_WORD_OFFSET = 0;
  protected static final int WRITE_WORD_OFFSET = READ_WORD_OFFSET + Epoch.BYTES_IN_EPOCH;
  private static final int EXTRA_WORD_1_OFFSET = WRITE_WORD_OFFSET + Epoch.BYTES_IN_EPOCH;
  private static final int EXTRA_WORD_2_OFFSET = EXTRA_WORD_1_OFFSET + BYTES_IN_WORD;

  /**
   * Which of the words in this block should be treated as references (if untagged).
   */
  public static final int HISTORY_TRUE_REF_MASK = 0;
  public static final int HISTORY_MAYBE_REF_MASK = 1<<0;
  public static final int HISTORY_REF_MASK = HISTORY_TRUE_REF_MASK | HISTORY_MAYBE_REF_MASK;
  public static final int HISTORY_REF_WORD_COUNT;
  static {
    final int refs;
    switch (HISTORY_REF_MASK) {
    case 0:
      refs = 0;
      break;
    case 1:
    case 2:
    case 4:
    case 8:
      refs = 1;
      break;
    case 3:
    case 5:
    case 6:
      refs = 2;
      break;
    case 7:
      refs = 3;
      break;
    default:
      VM.sysFail("too many access history ref words");
      refs = 0;
    }
    HISTORY_REF_WORD_COUNT = refs;
  }

  /**
   * Compute the history address for the given metadata object and offset.
   * @param object
   * @param offset
   * @return
   */
  @Inline
  public static Address address(Object object, Offset offset) {
    return ObjectReference.fromObject(object).toAddress().plus(offset);
  }

  /**
   * Compute the offset where this history's possible reference lives.
   * Result is offset from same base as used by argument.
   * 
   * @param history
   * @return
   */
  @Inline
  public static Offset readWordOffset(Offset history) {
    if (VM.VerifyAssertions) {
      VM._assert(history.toInt() % Epoch.BYTES_IN_EPOCH == 0, "Misaligned history");
    }
    return history.plus(READ_WORD_OFFSET);
  }

  /**
   * Check for valid base object pointer, aligned history offset,
   * in-bounds history offset, well-formed read map ref.
   * @param md
   * @param historyOffset
   */
  public static void assertValid(Object md, Offset historyOffset) {
    if (VM.VerifyAssertions) {
      VM._assert(MemoryManager.validRef(ObjectReference.fromObject(md)));
      VM._assert(historyOffset.toInt() % Epoch.BYTES_IN_EPOCH == 0, "Misaligned history");
      if (!ObjectReference.fromObject(md).isNull()) {
        VM._assert(Offset.zero().sLE(historyOffset),
            "History offset out of bounds (too small)");
        VM._assert(ObjectReference.fromObject(md).toAddress().plus(historyOffset).LT(ObjectModel.getObjectEndAddress(md)),
            "History offset out of bounds (too large)");
      }
      Epoch.isMapRef(ObjectReference.fromObject(md).toAddress().loadWord(historyOffset.plus(READ_WORD_OFFSET)));
    }
  }

  /**
   * Compute the offset for the history for a particular array index.
   * @param index
   * @return
   */
  @Inline
  public static Offset arrayHistoryOffset(int index) {
    if (VM.VerifyAssertions) {
      VM._assert(index >= 0 && index < (Integer.MAX_VALUE / BYTES_IN_HISTORY));
    }
    return Offset.fromIntSignExtend(index * BYTES_IN_HISTORY);
  }

  public static void print(final Word lw, final Word lr) {
    if (VM.VerifyAssertions && Epoch.isMapRef(lw)) {
      VM.tsysWriteln("Bad Write Epoch: ", lw.toAddress());
      VM.tsysWriteln("  tag = ", Epoch.isEpoch(lw) ? 1 : 0);
      VM.tsysWriteln("  tid = ", Epoch.tid(lw));
      VM.tsysWriteln("clock = ", Epoch.clock(lw));
      VM._assert(false);
    }
    VM.sysWrite("Last Write: ");  Epoch.print(lw);  VM.sysWriteln();
    VM.sysWrite("Last Reads: ");  Epoch.print(lr);   VM.sysWriteln();
  }
  public static void print(Object md, Offset mdOffset) {
    print(AccessHistory.loadWriteWord(md, mdOffset),
        AccessHistory.loadReadWord(md, mdOffset));
  }
  
  
  /*
   * GC Scanning of FIB's inserted metadata, with special treatment of
   * last readers, which can be either an epoch (not a reference) or a
   * reference to an EpochMap.
   *
   */

  public static void scanStatic(TraceLocal trace, Address historyAddress) {
    for (int i = 0; i < WORDS_IN_HISTORY; i++) {
      if ((HISTORY_REF_MASK & (1<<i)) != 0) {
        final Offset slotOffset = Offset.fromIntSignExtend(i<<LOG_BYTES_IN_WORD);
        // If this slot does not contain an epoch, trace it.
        final Address slotAddress = historyAddress.plus(slotOffset);
        final Word x = slotAddress.loadWord();
        if (Epoch.isMapRef(x)) {
          if (slotOffset.EQ(Offset.fromIntSignExtend(READ_WORD_OFFSET))) {
            // report a reachable epoch map.
            Dr.readers().processRootEdgeGC(trace, historyAddress, x);
          } else {
            // report a reachable object.
            trace.processRootEdge(slotAddress, false);
          }
        }
      }
    }
  }
  public static void scan(TransitiveClosure trace, ObjectReference objectRef, Address historyAddress) {
    for (int i = 0; i < WORDS_IN_HISTORY; i++) {
      if ((HISTORY_REF_MASK & (1<<i)) != 0) {
        final Offset slotOffset = Offset.fromIntSignExtend(i<<LOG_BYTES_IN_WORD);
        // If this slot does not contain an epoch, trace it.
        final Address slotAddress = historyAddress.plus(slotOffset);
        final Word x = slotAddress.loadWord();
        if (Epoch.isMapRef(x)) {
          if (slotOffset.EQ(Offset.fromIntSignExtend(READ_WORD_OFFSET))) {
            // report a reachable epoch map.
            Dr.readers().processEdgeGC(trace, objectRef, historyAddress, x);
          } else {
            // report a reachable object.
            trace.processEdge(objectRef, slotAddress);
          }
        }
      }
    }
  }

  
  
  
  

  /**
   * Get the read word.
   * @param md
   * @param mdOffset
   * @return
   */
  @Inline
  public static Word loadReadWord(Object md, Offset mdOffset) {
    if (VM.VerifyAssertions) assertValid(md, mdOffset);
    return ObjectReference.fromObject(md).toAddress().loadWord(mdOffset.plus(READ_WORD_OFFSET));
  }

  /**
   * Set the read word as an epoch.  No GC barrier.
   * @param md
   * @param mdOffset
   * @param w
   */
  @Inline
  public static void storeReadWord(Object md, Offset mdOffset, Word w) {
    if (VM.VerifyAssertions) assertValid(md, mdOffset);
    if (VM.VerifyAssertions) VM._assert(!Epoch.isMapRef(w));
    ObjectReference.fromObject(md).toAddress().store(w, mdOffset.plus(READ_WORD_OFFSET));
  }

  /**
   * Set the read word as reserved.  No GC barrier.
   * @param md
   * @param mdOffset
   */
  @Inline
  public static void storeReadReserved(Object md, Offset mdOffset) {
    storeReadWord(md, mdOffset, Epoch.RESERVED);
  }

  /**
   * Get the read word preparing for CAS.
   * @param md
   * @param mdOffset
   * @return
   */
  @Inline
  public static Word prepareReadWord(Object md, Offset mdOffset) {
    if (VM.VerifyAssertions) assertValid(md, mdOffset);
    return ObjectReference.fromObject(md).toAddress().prepareWord(mdOffset.plus(READ_WORD_OFFSET));
  }

  /**
   * Try to CAS the read word as the given word w.  No GC barrier.
   * @param md
   * @param mdOffset
   * @param old
   * @param r
   * @return
   */
  @Inline
  public static boolean attemptReadWord(Object md, Offset mdOffset, Word old, Word r) {
    if (VM.VerifyAssertions) assertValid(md, mdOffset);
    if (VM.VerifyAssertions) VM._assert(!Epoch.isMapRef(r));
    return ObjectReference.fromObject(md).toAddress().attempt(old, r, mdOffset.plus(READ_WORD_OFFSET));
  }

  /**
   * Try to CAS the read word as reserved.  No GC barrier.
   * @param md
   * @param mdOffset
   * @param old
   * @return
   */
  @Inline
  public static boolean attemptReadReserved(Object md, Offset mdOffset, Word old) {
    return attemptReadWord(md, mdOffset, old, Epoch.RESERVED);
  }

  /**
   * CAS-lock the read word and return its value.  No GC barrier.
   * @param md
   * @param mdOffset
   * @return
   */
  @Inline
  @Unpreemptible
  public static Word acquireReadWord(Object md, Offset mdOffset) {
    final Offset readOffset = mdOffset.plus(READ_WORD_OFFSET);
    if (VM.VerifyAssertions) assertValid(md, readOffset);
    Word w;
    int spins = 0;
    boolean longTime = false;
    do {
      w = ObjectReference.fromObject(md).toAddress().prepareWord(readOffset);
      spins++;
      if (VM.octetWaitSpinCount < spins) {
        RVMThread.yieldWithHandshake();
      }
      if (VM.VerifyAssertions) {
        if (spins == 10000) {
          longTime = true;
          DrDebug.twriteln("Passed 10000 spins in AccessHistory.acquireReadWord");
        } else if (spins == Integer.MIN_VALUE) {
          DrDebug.twriteln("Spin counter rolling over in AccessHistory.acquireReadWord");
        }
      }
    } while (Epoch.isReserved(w) || !ObjectReference.fromObject(md).toAddress().attempt(w, Epoch.RESERVED, readOffset));
    if (VM.VerifyAssertions && longTime) {
      DrDebug.twriteln("Long-running spin completed.");
    }
    Magic.readCeiling();
    
//    if (Epoch.isMapRef(w)) {
//      RVMType ty = ObjectModel.getObjectType(w.toAddress().toObjectReference().toObject());
//      if (!ty.equals(RVMType.WordArrayType)) {
//        DrDebug.lock();
//        DrDebug.twrite();
//        VM.sysWriteln("Found last read ref ", w.toAddress());
//        VM.sysWriteln(" of wrong type ", ty.getDescriptor());
//        VM.sysWrite("For ", ObjectReference.fromObject(md).toAddress());
//        VM.sysWriteln(".", mdOffset);
//        DrDebug.unlock();
//        VM.sysFail("bad read map type in acquireReadWord");
//      }
//    }

    return w;
  }

  /**
   * Store a word into (and unlock) a CAS-locked read word.  NO GC write barrier.
   * @param md
   * @param mdOffset
   * @param map
   */
  public static void releaseReadWord(Object md, Offset mdOffset, Word word) {
//    if (Epoch.isMapRef(word)) {
//      RVMType ty = ObjectModel.getObjectType(word.toAddress().toObjectReference().toObject());
//      if (!ty.equals(RVMType.WordArrayType)) {
//        DrDebug.lock();
//        DrDebug.twrite();
//        VM.sysWriteln("Found last read ref ", word.toAddress());
//        VM.sysWriteln(" of wrong type ", ty.getDescriptor());
//        VM.sysWrite("For ", ObjectReference.fromObject(md).toAddress());
//        VM.sysWriteln(".", mdOffset);
//        DrDebug.unlock();
//        VM.sysFail("bad read map type in releaseReadWord");
//      }
//    }

    final Offset readOffset = mdOffset.plus(READ_WORD_OFFSET);
    if (VM.VerifyAssertions) {
      assertValid(md, readOffset);
      VM._assert(Epoch.isReserved(ObjectReference.fromObject(md).toAddress().loadWord(readOffset)));
      VM._assert(!Epoch.isMapRef(word), "Cannot store ref -- use Fib.readers().install().");
    }
    Magic.writeFloor();
    ObjectReference.fromObject(md).toAddress().store(word, readOffset);
  }


  /**
   * Get the write word.
   * @param md
   * @param mdOffset
   * @return
   */
  @Inline
  public static Word loadWriteWord(Object md, Offset mdOffset) {
    final Offset writeOffset = mdOffset.plus(WRITE_WORD_OFFSET);
    if (VM.VerifyAssertions) assertValid(md, mdOffset);
    final Word w = ObjectReference.fromObject(md).toAddress().loadWord(writeOffset);
    if (VM.VerifyAssertions) {
      if (Epoch.isMapRef(w)) {
        VM.sysWriteln("Getting bad write word.  history = ", address(md, writeOffset),
            "  write word = ", w.toAddress());
        if (MemoryManager.validRef(w.toAddress().toObjectReference())) {
          VM.sysWriteln("It's a valid ref!?");
          VM.sysWriteln("It's a ", ObjectModel.getTIB(w.toAddress().toObjectReference()).getType().getDescriptor());
        } else {
          VM.sysWriteln("At least it's not a valid ref.");
        }
        VM.sysFail("Bad write word.");
      }
    }
    return w;
  }

  /**
   * Set the write word.  No GC barrier.
   * @param md
   * @param mdOffset
   * @param w
   */
  @Inline
  public static void storeWriteWord(Object md, Offset mdOffset, Word w) {
    if (VM.VerifyAssertions) assertValid(md, mdOffset);
    if (VM.VerifyAssertions) VM._assert(Epoch.isEpoch(w) || Epoch.isNone(w));
    ObjectReference.fromObject(md).toAddress().store(w, mdOffset.plus(WRITE_WORD_OFFSET));
  }


  public static Word loadExtra1(Object md, Offset mdOffset) {
    if (VM.VerifyAssertions) assertValid(md, mdOffset);
    if (VM.VerifyAssertions) VM._assert(WORDS_IN_HISTORY >= 3);
    return ObjectReference.fromObject(md).toAddress().loadWord(mdOffset.plus(EXTRA_WORD_1_OFFSET));
  }
  public static Word loadExtra2(Object md, Offset mdOffset) {
    if (VM.VerifyAssertions) assertValid(md, mdOffset);
    if (VM.VerifyAssertions) VM._assert(WORDS_IN_HISTORY >= 4);
    return ObjectReference.fromObject(md).toAddress().loadWord(mdOffset.plus(EXTRA_WORD_2_OFFSET));
  }
  public static Word prepareExtra1(Object md, Offset mdOffset) {
    if (VM.VerifyAssertions) assertValid(md, mdOffset);
    if (VM.VerifyAssertions) VM._assert(WORDS_IN_HISTORY >= 3);
    return ObjectReference.fromObject(md).toAddress().prepareWord(mdOffset.plus(EXTRA_WORD_1_OFFSET));
  }
  public static Word prepareExtra2(Object md, Offset mdOffset) {
    if (VM.VerifyAssertions) assertValid(md, mdOffset);
    if (VM.VerifyAssertions) VM._assert(WORDS_IN_HISTORY >= 4);
    return ObjectReference.fromObject(md).toAddress().prepareWord(mdOffset.plus(EXTRA_WORD_2_OFFSET));
  }
  public static void storeExtra1(Object md, Offset mdOffset, Word v) {
    if (VM.VerifyAssertions) assertValid(md, mdOffset);
    if (VM.VerifyAssertions) VM._assert(WORDS_IN_HISTORY >= 3);
    ObjectReference.fromObject(md).toAddress().store(v, mdOffset.plus(EXTRA_WORD_1_OFFSET));
  }
  public static void storeExtra1(Object md, Offset mdOffset, Object v) {
    if (VM.VerifyAssertions) assertValid(md, mdOffset);
    if (VM.VerifyAssertions) VM._assert(WORDS_IN_HISTORY >= 3);
    if (Barriers.NEEDS_OBJECT_PUTFIELD_BARRIER) {
      Barriers.objectFieldWrite(md, v, mdOffset.plus(EXTRA_WORD_1_OFFSET), 0);
    } else {
      ObjectReference.fromObject(md).toAddress().store(ObjectReference.fromObject(v), mdOffset.plus(EXTRA_WORD_1_OFFSET));
    }
  }
  public static void storeExtra2(Object md, Offset mdOffset, Word v) {
    if (VM.VerifyAssertions) assertValid(md, mdOffset);
    if (VM.VerifyAssertions) VM._assert(WORDS_IN_HISTORY >= 4);
    ObjectReference.fromObject(md).toAddress().store(v, mdOffset.plus(EXTRA_WORD_2_OFFSET));
  }
  public static boolean attemptExtra1(Object md, Offset mdOffset, Object old, Object v) {
    if (VM.VerifyAssertions) assertValid(md, mdOffset);
    if (VM.VerifyAssertions) VM._assert(WORDS_IN_HISTORY >= 3);
    return ObjectReference.fromObject(md).toAddress().attempt(ObjectReference.fromObject(old), 
        ObjectReference.fromObject(v), mdOffset.plus(EXTRA_WORD_1_OFFSET));
  }
  public static boolean attemptExtra2(Object md, Offset mdOffset, Word old, Word v) {
    if (VM.VerifyAssertions) assertValid(md, mdOffset);
    if (VM.VerifyAssertions) VM._assert(WORDS_IN_HISTORY >= 4);
    return ObjectReference.fromObject(md).toAddress().attempt(old, v, mdOffset.plus(EXTRA_WORD_2_OFFSET));
  }
}
