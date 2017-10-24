package org.jikesrvm.dr.metadata;

import org.jikesrvm.SizeConstants;
import org.jikesrvm.VM;
import org.jikesrvm.dr.Dr;
import org.jikesrvm.dr.DrDebug;
import org.jikesrvm.dr.DrStats;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.ObjectReference;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;
import org.vmmagic.unboxed.WordArray;

/**
 * Epochs (pairs of thread ID and clock) are represented as single Words.
 * 
 * Epochs always have a tag bit set.
 * Since the tag is the LSB and all object addresses are expected to be
 * word-aligned, the tag bit distinguishes epochs from pointers.  (Last
 * Read metadata is either an epoch or a pointer to an EpochMap.)
 * 
 * Since the clock bits are stored in the higher-order bits, comparing
 * epochs with Less Than and Greater Than is equivalent, whether or not
 * the tid and tag have been masked off.
 *
 */
@Uninterruptible
public class Epoch implements SizeConstants {
  /**
   * Number of bits in an Epoch representation.
   */
  private static final int EPOCH_BITS = BITS_IN_WORD;
  public static final int LOG_BYTES_IN_EPOCH = LOG_BYTES_IN_WORD;
  public static final int BYTES_IN_EPOCH = BYTES_IN_WORD;
  public static final int LOG_WORDS_IN_EPOCH = LOG_BYTES_IN_EPOCH - LOG_BYTES_IN_WORD;
  
  /**
   * Number of bits used as a tag.
   */
  private static final int TAG_BITS = Dr.config().epochTagBits();

  /**
   * Mask to select the tag bits.
   */
  private static final Word TAG_MASK = Word.fromIntZeroExtend((1 << TAG_BITS) - 1);
  
  private static final Word EPOCH_MASK = Word.one();
  private static final Word ALT_MASK = EPOCH_MASK.lsh(1); // NON_REF_MASK.and(TAG_MASK.not());
  public static final Word RESERVED = ALT_MASK;
  
  /**
   * Position in epoch of LSB of tag.
   */
  private static final int TAG_START = 0;
  
  /**
   * Number of bits used for tid.
   */
  protected static final int TID_BITS = Dr.config().epochTidBits();
  static {
    if (VM.VerifyAssertions) VM._assert(TID_BITS >= 1 && TID_BITS < BITS_IN_WORD - TAG_BITS, "TID_BITS out of range.");
  }
  /**
   * The maximum number of threads supported by Epoch.
   */
  public static final int MAX_THREADS = (1 << TID_BITS);
  /**
   * Maximum representable tid.
   */
  public static final int MAX_TID = MAX_THREADS - 1;
  /**
   * Position in epoch of LSB of tid.
   */
  private static final int TID_START = TAG_START + TAG_BITS;
  /**
   * Mask to select tid bits.
   */
  private static final Word TID_MASK = Word.fromIntZeroExtend(MAX_TID << TID_START);
  
  // private static final Word TID_TAG_MASK = TID_MASK.or(TAG_MASK);
  private static final Word TID_EPOCH_TAG_MASK = TID_MASK.or(EPOCH_MASK);
  private static final Word NON_REF_MASK = Word.fromIntSignExtend(BYTES_IN_ADDRESS - 1);
  public static final Word REF_MASK = NON_REF_MASK.not();
  
  static {
    if (VM.VerifyAssertions) {
      VM._assert(!RESERVED.isZero());
      VM._assert(TAG_MASK.LE(NON_REF_MASK));
    }
  }
  
  /**
   * Number of bits used for clock.
   */
  private static final int CLOCK_BITS = EPOCH_BITS - TAG_BITS - TID_BITS;
  /**
   * Maximum representable clock.
   */
  public static final int MAX_CLOCK = (1 << CLOCK_BITS) - 1;
  /**
   * Position in epoch of LSB of clock.
   */
  private static final int CLOCK_START = TID_START + TID_BITS;
  /**
   * Mask to select clock bits.
   */
  private static final Word CLOCK_MASK = Word.fromIntZeroExtend(MAX_CLOCK << CLOCK_START);
  
  private static final int INITIAL_CLOCK = 1;
  private static final Word CLOCK_ONE = Word.fromIntSignExtend(INITIAL_CLOCK).lsh(CLOCK_START);
  
  public static final Word TAG = Epoch.make(0, 0);
  public static final Word ORIGIN = TAG;
  public static final Word NONE = Word.zero();
    
  /**
   * Make a tagged epoch from a tid and a clock.
   * @param tid
   * @param clock
   * @return
   */
  @Inline
  @Pure
  public static Word make(final int tid, final int clock) {
    return Word.fromIntZeroExtend(tid).lsh(TID_START).or(Word.fromIntZeroExtend(clock).lsh(CLOCK_START)).or(EPOCH_MASK);
  }
  
  @Inline
  @Pure
  public static Word one(final int tid) {
    return make(tid, INITIAL_CLOCK);
  }
  
  /**
   * Check if this word is an epoch using the tag bit.
   * @param w
   * @return
   */
  @Inline
  public static boolean isEpoch(final Word w) {
    return w.and(EPOCH_MASK).EQ(EPOCH_MASK);
  }
  
  @Inline
  public static boolean isMapRef(final Word w) {
    final boolean isref = isRef(w);
    if (VM.VerifyAssertions && isref) {
      Dr.readers().assertValidMap((WordArray)w.toAddress().toObjectReference().toObject());
    }
    return isref;
  }
  @Inline
  public static boolean isRef(final Word w) {
    return w.and(NON_REF_MASK).isZero() && !w.isZero();
  }
  
  @Inline
  public static boolean isNone(final Word w) {
    return w.EQ(NONE);
  }
  
  @Inline
  public static boolean isNoneOrOrigin(final Word w) {
    return w.and(TAG_MASK.not()).EQ(NONE);
  }
  
  @Inline
  public static boolean isOrigin(final Word w) {
    return w.EQ(ORIGIN);
  }
  
  @Inline
  public static boolean isReserved(Word w) {
    return w.EQ(RESERVED);
  }
  
  @Inline
  public static boolean isAlt(Word w) {
    // In alternate mode iff it is an epoch AND the alt bit is also set.
    return Dr.config().fibAdaptiveCas() && w.and(TAG_MASK).EQ(TAG_MASK);
  }
  
  /**
   * Treat the epoch as a map reference.
   * 
   * @param word
   * @return
   */
  @Inline
  public static WordArray asMapRef(final Word word) {
    if (VM.VerifyAssertions) VM._assert(isMapRef(word));
    return (WordArray)word.toAddress().toObjectReference().toObject();
  }
  
  /**
   * Get the tid of an epoch.
   * @param epoch
   * @return
   */
  @Inline
  public static int tid(final Word epoch) {
    return epoch.and(TID_MASK).rshl(TID_START).toInt();
  }
  
  @Inline
  public static boolean sameTid(final Word e, final Word f) {
    // if (VM.VerifyAssertions) VM._assert(isEpoch(e) || isEpoch(f));
    // e and f do not differ in tid or tag
    // e has tag set
    return e.xor(f).and(TID_EPOCH_TAG_MASK).isZero() && Epoch.isEpoch(e);
  }
  @Inline
  public static boolean sameEpoch(final Word e1, final Word e2) {
    if (Dr.config().fibAdaptiveCas()) {
      return e1.xor(e2).and(ALT_MASK.not()).isZero() && Epoch.isEpoch(e1);
    } else {
      return e1.EQ(e2);
    }
  }
  @Inline
  public static Word withDelegateBitFrom(Word e, Word status) {
    if (Dr.config().fibAdaptiveCas()) {
      return e.or(status.and(ALT_MASK));
    } else {
      return e;
    }
  }
  
  /**
   * Get the clock of an epoch.
   * @param epoch
   * @return
   */
  @Inline
  public static int clock(final Word epoch) {
    return epoch.and(CLOCK_MASK).rshl(CLOCK_START).toInt();
  }
  
  /**
   * Has any epoch overflowed yet?  (racy flag)
   */
  private static boolean clocksOverflowed = false;
  
  /**
   * Increment an epoch, returning the next epoch
   * or (if already max) the same epoch.  (i.e.,
   * increment is sticky)
   * 
   * @param epoch
   * @return
   */
  @Inline
  public static Word inc(final Word epoch) {
    // Overflow check.
    if (epoch.and(CLOCK_MASK).EQ(CLOCK_MASK)) {
//      if (VM.VerifyAssertions) {
//        // When checking assertions,
//        // treat overflow as failure.
//        DrDebug.twriteln("FAILURE: Clock overflow.");
//        VM.sysExit(124);
//      } else
      {
        // When not checking assertions,
        // treat overflow as a warning (first time)
        // and "stick" clock to max.
        if (!clocksOverflowed) {
          clocksOverflowed = true;
          DrDebug.twriteln("WARNING: Clock overflow.");
          if (Dr.STATS) DrStats.clockOverflow.inc();
        }
        return epoch;
      }
    }
    return epoch.plus(CLOCK_ONE);
  }
  
  /**
   * Print a read word.
   * 
   * If it is an epoch, print c@t.
   * If it is a map ref, print the map.
   * If it is reserved or none, print that.
   * 
   * @param epoch
   */
  public static void print(final Word epoch) {
    if (isEpoch(epoch)) {
      if (epoch.and(CLOCK_MASK).EQ(CLOCK_MASK)) {
        VM.sysWrite("MAX@T", Epoch.tid(epoch));
      } else {
        VM.sysWrite(Epoch.clock(epoch), "@T", Epoch.tid(epoch));
      }
      if (isAlt(epoch)) {
        VM.sysWrite("[delegated]");
      }
    } else if (isMapRef(epoch)) {
      Dr.readers().print((WordArray)ObjectReference.fromObject(Epoch.asMapRef(epoch)).toAddress().toObjectReference().toObject());
    } else if (isReserved(epoch)) {
      VM.sysWrite("RESERVED");
    } else {
      VM.sysWrite("NONE");
    }
  }
  
  public static boolean attemptReserve(WordArray map, Offset off,  Word old) {
    return ObjectReference.fromObject(map).toAddress().attempt(old, Epoch.RESERVED, off);
  }
  
  public static Word asAlt(Word epoch) {
    return Dr.config().fibAdaptiveCas() ? epoch.or(ALT_MASK) : epoch;
  }
  public static Word asDefault(Word epoch) {
    return Dr.config().fibAdaptiveCas() ? epoch.and(ALT_MASK.not()) : epoch;
  }

}
