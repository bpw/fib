package org.jikesrvm.octet;

import org.jikesrvm.Constants;
import org.jikesrvm.VM;
import org.jikesrvm.scheduler.RVMThread;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Word;

/** Octet: helper methods for managing each thread's communication queue word. */
@Uninterruptible
public final class CommunicationQueue implements Constants {

  /** The high bit of the request word indicates whether the thread is in the blocked state (including the dead state). */
  private static final int BLOCKED_OR_DEAD_STATE_BITS = 1;
  
  /** The middle bits of the request word store a requesting thread's ID.
      Zero (an invalid thread ID) indicates that the request queue is empty.
      If the thread is in the blocked state, then these bits instead represent the number of holding threads. */
  private static final int THREAD_OR_HOLD_BITS =
    (Octet.getClientAnalysis().useHoldingState() ||
     Octet.getClientAnalysis().needsCommunicationQueue()) ?
    RVMThread.LOG_MAX_THREADS : 0; // Don't need any bits if we're not using queues or the hold state

  /** The low bits of the request word are the request-respond counter. */
  public static final int COUNTER_BITS = BITS_IN_WORD - THREAD_OR_HOLD_BITS - BLOCKED_OR_DEAD_STATE_BITS;

  private static final Word BLOCKED_OR_DEAD_STATE_MASK = Word.max().lsh(BITS_IN_WORD - BLOCKED_OR_DEAD_STATE_BITS);
  private static final Word DEAD_STATE = Word.max();
  private static final Word THREAD_OR_HOLD_MASK = Word.one().lsh(THREAD_OR_HOLD_BITS).minus(Word.one()).lsh(COUNTER_BITS);
  private static final Word COUNTER_MASK = Word.one().lsh(COUNTER_BITS).minus(Word.one());
  //Man: This mask covers both THREAD_OR_HOLD_MASK and BLOCKED_OR_DEAD_STATE_MASK
  private static final Word HIGHBITS_MASK = COUNTER_MASK.not();
  static {
    if (VM.VerifyAssertions) {
      if (THREAD_OR_HOLD_BITS == 0) {
        VM._assert(THREAD_OR_HOLD_MASK.isZero());
      }
    }
  }

  @Inline
  public static final boolean isBlockedOrDead(Word requests) {
    return !requests.and(BLOCKED_OR_DEAD_STATE_MASK).isZero();
  }
  
  @Inline
  public static final boolean isBlockedAssertNotDead(Word requests) {
    if (VM.VerifyAssertions) { VM._assert(!isDead(requests)); }
    return isBlockedOrDead(requests);
  }
  
  /** In the dead state, all bits are set. */
  @Inline
  static final boolean isDead(Word requests) {
    return requests.EQ(DEAD_STATE);
  }
  
  @Inline
  static final Word makeDead() {
    return DEAD_STATE;
  }
  
  @Inline
  static final int getThreadID(Word requests) {
    if (VM.VerifyAssertions) { VM._assert(!isBlockedAssertNotDead(requests)); }
    return requests.rshl(COUNTER_BITS).toInt();
  }
  
  @Inline
  static final int getHoldCount(Word requests) {
    if (VM.VerifyAssertions) { VM._assert(isBlockedAssertNotDead(requests)); }
    return requests.and(THREAD_OR_HOLD_MASK).rshl(COUNTER_BITS).toInt();
  }

  @Inline
  public static final int getCounter(Word requests) {
    return requests.and(COUNTER_MASK).toInt();
  }

  @Inline
  static final Word block(Word requests) {
    if (VM.VerifyAssertions) { VM._assert(!isBlockedAssertNotDead(requests)); }
    Word newRequests = requests.or(BLOCKED_OR_DEAD_STATE_MASK).and(THREAD_OR_HOLD_MASK.not());
    if (VM.VerifyAssertions) { VM._assert(isBlockedAssertNotDead(newRequests)); }
    if (VM.VerifyAssertions) { VM._assert(getHoldCount(newRequests) == 0); }
    return newRequests;
  }
  
  @Inline
  static final Word unblock(Word requests) {
    if (VM.VerifyAssertions) { VM._assert(isBlockedAssertNotDead(requests)); }
    if (VM.VerifyAssertions) { VM._assert(getHoldCount(requests) == 0); }
    Word newRequests = requests.and(BLOCKED_OR_DEAD_STATE_MASK.not());
    if (VM.VerifyAssertions) { VM._assert(!isBlockedAssertNotDead(newRequests)); }
    return newRequests;
  }

  @Inline
  static final Word clearRequests(Word requests) {
    if (VM.VerifyAssertions) { VM._assert(getThreadID(requests) != 0); }
    Word newRequests = requests.and(THREAD_OR_HOLD_MASK.not());
    if (VM.VerifyAssertions) { VM._assert(getThreadID(newRequests) == 0); }
    return newRequests;
  }

  @Inline
  static final Word push(Word requests, int newHeadThreadID) {
    if (VM.VerifyAssertions) { VM._assert(!isBlockedAssertNotDead(requests)); }
    Word oldCounter = requests.and(COUNTER_MASK);
    // Man: commented out this assertion because we are now allowing counter to overflow
    // if (VM.VerifyAssertions) { VM._assert(oldCounter.NE(COUNTER_MASK)); }
    // return Word.fromIntZeroExtend(newHeadThreadID).lsh(COUNTER_BITS).or(oldCounter).plus(Word.one());
    return Word.fromIntZeroExtend(newHeadThreadID).lsh(COUNTER_BITS).or(oldCounter.plus(Word.one()).and(COUNTER_MASK));
  }

  /** Increment the counter of the number of requests. */
  @Inline
  static final Word inc(Word requests) {
    if (VM.VerifyAssertions) { VM._assert(!isDead(requests)); }
    // if (VM.VerifyAssertions) { VM._assert(requests.and(COUNTER_MASK).NE(COUNTER_MASK)); }
    // return requests.plus(Word.one());
    return requests.and(HIGHBITS_MASK).or(requests.plus(Word.one()).and(COUNTER_MASK));
  }
  
  /** Add a hold (i.e., increment the hold count) and also increment the number of requests. */
  @Inline
  static final Word addHoldAndIncrement(Word requests) {
    if (VM.VerifyAssertions) { VM._assert(isBlockedAssertNotDead(requests)); }
    if (VM.VerifyAssertions) { VM._assert(requests.and(THREAD_OR_HOLD_MASK).NE(THREAD_OR_HOLD_MASK)); }
    // if (VM.VerifyAssertions) { VM._assert(requests.and(COUNTER_MASK).NE(COUNTER_MASK)); }
    // return requests.plus(Word.one().lsh(COUNTER_BITS).plus(Word.one()));
    return requests.and(HIGHBITS_MASK).plus(Word.one().lsh(COUNTER_BITS)).or(requests.plus(Word.one()).and(COUNTER_MASK));
  }
  
  @Inline
  static final Word addHold(Word requests) {
    if (VM.VerifyAssertions) { VM._assert(isBlockedAssertNotDead(requests)); }
    if (VM.VerifyAssertions) { VM._assert(requests.and(THREAD_OR_HOLD_MASK).NE(THREAD_OR_HOLD_MASK)); }
    return requests.plus(Word.one().lsh(COUNTER_BITS));
  }

  @Inline
  static final Word removeHold(Word requests) {
    if (VM.VerifyAssertions) { VM._assert(isBlockedAssertNotDead(requests)); }
    if (VM.VerifyAssertions) { VM._assert(!requests.and(THREAD_OR_HOLD_MASK).isZero()); }
    return requests.minus(Word.one().lsh(COUNTER_BITS));
  }

}
