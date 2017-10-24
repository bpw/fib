package org.jikesrvm.dr.metadata;

import org.jikesrvm.VM;
import org.jikesrvm.dr.Dr;
import org.jikesrvm.dr.DrRuntime;
import org.jikesrvm.dr.DrStats;
import org.jikesrvm.mm.mminterface.MemoryManager;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.NoInline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.pragma.UninterruptibleNoWarn;
import org.vmmagic.pragma.Unpreemptible;
import org.vmmagic.unboxed.ObjectReference;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;
import org.vmmagic.unboxed.WordArray;

/**
 * Methods to create and manipulate vector clocks.
 * Epochs are assumed to be one word in size.
 * 
 * @author bpw
 *
 */
@Uninterruptible
public final class VC {
  
  public static final WordArray ORIGIN = VC.create();
  
  @UninterruptibleNoWarn
  public static WordArray create() {
    if (VM.runningVM) MemoryManager.startAllocatingInUninterruptibleCode();
    final WordArray vc = WordArray.create(Epoch.MAX_THREADS << Epoch.LOG_WORDS_IN_EPOCH);
    if (VM.runningVM) MemoryManager.stopAllocatingInUninterruptibleCode();
    if (Dr.STATS) DrStats.vcs.inc();
    return vc;
  }
  
  @NoInline
  public static void nullAndBoundsCheck(WordArray vc, int tid) {
    if (!(MemoryManager.validRef(ObjectReference.fromObject(vc)) && vc != null && tid >= 0 && tid < vc.length())) {
      VM.tsysWriteln("ArrayEpochMap fail tid = ", tid);
      VM.tsysWriteln("                length = ", vc.length());
      VM._assert(false);
    }
  }
  
  @Inline
  public static Word get(WordArray vc, int tid) {
    if (VM.VerifyAssertions) nullAndBoundsCheck(vc, tid);
    // TODO does this do bounds checking?  I don't want it.
    // return vc.get(tid << Epoch.LOG_BYTES_IN_EPOCH);
    return ObjectReference.fromObject(vc).toAddress().loadWord(Offset.fromIntSignExtend(tid << Epoch.LOG_BYTES_IN_EPOCH));
  }
  
  @Inline
  public static void set(WordArray vc, int tid, Word epoch) {
    if (VM.VerifyAssertions) nullAndBoundsCheck(vc, tid);
    // TODO does this do bounds checking?  I don't want it.
    // vc.set(tid << Epoch.LOG_BYTES_IN_EPOCH, epoch);
    ObjectReference.fromObject(vc).toAddress().store(epoch, Offset.fromIntSignExtend(tid << Epoch.LOG_BYTES_IN_EPOCH));
  }
    
  @Inline
  public static void set(WordArray vc, Word epoch) {
    final int tid = Epoch.tid(epoch);
    if (VM.VerifyAssertions) nullAndBoundsCheck(vc, tid);
    // TODO does this do bounds checking?  I don't want it.
    // vc.set(tid << Epoch.LOG_BYTES_IN_EPOCH, epoch);
    ObjectReference.fromObject(vc).toAddress().store(epoch, Offset.fromIntSignExtend(tid << Epoch.LOG_BYTES_IN_EPOCH));
  }
  
  @Inline
  public static boolean attempt(WordArray vc, int tid, Word oldEpoch, Word newEpoch) {
    if (VM.VerifyAssertions) nullAndBoundsCheck(vc, tid);
    return ObjectReference.fromObject(vc).toAddress().attempt(oldEpoch, newEpoch, Offset.fromIntSignExtend(tid << Epoch.LOG_BYTES_IN_EPOCH));
  }
  
  public static boolean epochHB(Word earlierEpoch, WordArray threadVC) {
    if (VM.VerifyAssertions) VM._assert(Epoch.isEpoch(earlierEpoch) || Epoch.isNone(earlierEpoch));
    if (Dr.STATS) DrStats.vcEpochHB.inc();
    return Epoch.isNone(earlierEpoch) || earlierEpoch.LE(get(threadVC, Epoch.tid(earlierEpoch)));
  }
  
  /**
   * Not the best performing option, but it's a baseline.
   * @param readers - epochmap
   * @param threadVC
   * @return
   */
  public static boolean hb(WordArray readers, WordArray threadVC) {
    if (Dr.STATS) DrStats.vcMapHB.inc();
    final int bound = DrRuntime.maxLiveDrThreads();
    for (int tid = 0; tid < bound; tid++) {
      final Word earlierEpoch = Dr.readers().get(readers, tid);
      if (!Epoch.isNone(earlierEpoch)
          && earlierEpoch.GT(get(threadVC, tid))) {
        return false;
      }
    }
    return true;
  }
  
  /**
   * Advance any parts of this vector clock that are behind the given frontier.
   * Not the best performing option, but it's a baseline.
   * @param frontier
   */
  public static void advanceTo(WordArray vc, WordArray frontier) {
    final int bound = DrRuntime.maxLiveDrThreads();
    if (VM.VerifyAssertions) {
      VM._assert(bound <= Epoch.MAX_THREADS);
      VM._assert(vc != null);
      VM._assert(frontier != null);
    }
    for (int tid = 0; tid < bound; tid++) {
      final Word frontierEpoch = get(frontier, tid);
      if (get(vc, tid).LT(frontierEpoch)) {
        set(vc, tid, frontierEpoch);
      }
    }
  }
  
  @Unpreemptible
  public static WordArray copy(WordArray original) {
    WordArray copy = create();
    advanceTo(copy, original);
    return copy;
  }
    
  @Inline
  public static void inc(WordArray vc, int tid) {
    set(vc, tid, Epoch.inc(get(vc, tid)));
  }

  public static void print(WordArray vc) {
    VM.sysWrite("{  ");
    final int bound = DrRuntime.maxLiveDrThreads();
    for (int tid = 0; tid < bound; tid++) {
      Word w = get(vc, tid);
      if (Epoch.isNone(w)) continue;
      Epoch.print(w);
      VM.sysWrite("  ");
    }
    VM.sysWrite("}");
  }

}
