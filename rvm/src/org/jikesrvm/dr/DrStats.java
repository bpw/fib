package org.jikesrvm.dr;

import org.jikesrvm.dr.metadata.Epoch;
import org.jikesrvm.octet.Stats;
import org.jikesrvm.scheduler.RVMThread;
import org.vmmagic.pragma.Uninterruptible;

@SuppressWarnings("unused")
@Uninterruptible
public class DrStats {
  
  private static final boolean ENABLED = Dr.STATS;
  
  @Uninterruptible
  public static abstract class LongThreadReducer extends Stats.CounterWithTotal {
    protected LongThreadReducer(String name) {
      super(name, false, ENABLED);
    }
    protected abstract long value(RVMThread thread);
    protected abstract long accumulate(long acc, long v);
    protected abstract long init();
    
    @Override
    protected final long total() {
      long acc = init();
      for (RVMThread t : DrRuntime.drThreads) {
        if (t != null) {
          acc = accumulate(acc, value(t));
        }
      }
      return acc;
    }
  }
  
  @Uninterruptible
  public static abstract class ThreadSum extends LongThreadReducer {
    protected ThreadSum(String name) {
      super(name);
    }
    @Override
    protected final long accumulate(long acc, long v) {
      return acc + v;
    }
    @Override
    protected final long init() {
      return 0L;
    }
  }

  @Uninterruptible
  public static abstract class ThreadMax extends LongThreadReducer {
    protected ThreadMax(String name) {
      super(name);
    }
    @Override
    protected final long accumulate(long acc, long v) {
      return acc > v ? acc : v;
    }
    @Override
    protected final long init() {
      return 0L;
    }
  }
  
  @Uninterruptible
  private static final class ThreadSafeCounterPair {
    private final Stats.ThreadSafeCounter a;
    private final Stats.ThreadSafeCounter b;
    public ThreadSafeCounterPair(String baseName, String aName, String bName) {
      a = new Stats.ThreadSafeCounter(baseName + aName, false, ENABLED);
      b = new Stats.ThreadSafeCounter(baseName + bName, false, ENABLED);
    }
    public ThreadSafeCounterPair(String baseName, String yesName) {
      this(baseName, yesName, "No" + yesName);
    }
    public void inc(boolean which) {
      (which ? a : b).inc();
    }
  }
  
  @Uninterruptible
  public static final class UnsyncHistogramPair {
    private final Stats.UnsyncHistogram a;
    private final Stats.UnsyncHistogram b;
    public UnsyncHistogramPair(String baseName, String aName, String bName, boolean log, int max) {
      a = new Stats.UnsyncHistogram(baseName + aName, log, max, ENABLED);
      b = new Stats.UnsyncHistogram(baseName + bName, log, max, ENABLED);
    }
    public UnsyncHistogramPair(String baseName, String yesName, boolean log, int max) {
      this(baseName, yesName, "No" + yesName, log, max);
    }
    public void incBin(int x, boolean which) {
      (which ? a : b).incBin(x);
    }
  }
  
  // -- Always collected, so special case. ---------------------------------
  public static final Stats.ThreadSafeCounter totalEpochs =
      new Stats.ThreadSafeCounter("FibTotalEpochs", false, ENABLED);

  public static final Stats.ThreadSafeCounter maxClock =
      new Stats.ThreadSafeCounter("FibMaxClock", false, ENABLED);
  
  public static final Stats.ThreadSafeCounter races =
      new Stats.ThreadSafeCounter("FibRaces", false, ENABLED);
  
  // -- Only collected when stats are enabled. -----------------------------
  
  // Escape hooks
  public static final Stats.ThreadSafeCounter escape =
      new Stats.ThreadSafeCounter("FibEscapedObjects", false, ENABLED);
  public static final Stats.ThreadSafeCounter escapeObjectWithCheckedFields =
      new Stats.ThreadSafeCounter("FibEscapedObjectsWithCheckedFields", false, ENABLED);
  public static final Stats.ThreadSafeCounter escapeInitOwned =
      new Stats.ThreadSafeCounter("FibEscapeInitOwned", false, ENABLED);
  public static final Stats.ThreadSafeCounter escapeInitOther =
      new Stats.ThreadSafeCounter("FibEscapeInitOther", false, ENABLED);
  public static final Stats.ThreadSafeCounter escapeEpochUsed =
      new Stats.ThreadSafeCounter("FibEscapeEpochsUsed", false, ENABLED);
  
  // VCs
  public static final Stats.ThreadSafeCounter vcs =
      new Stats.ThreadSafeCounter("FibVCs", false, ENABLED);
  public static final Stats.ThreadSafeCounter volVCs =
      new Stats.ThreadSafeCounter("FibVolatileVCs", false, ENABLED);
  public static final Stats.ThreadSafeCounter locks =
      new Stats.ThreadSafeCounter("FibLocks", false, ENABLED);
  public static final Stats.ThreadSafeCounter lockVCs =
      new Stats.ThreadSafeCounter("FibLockVCs", false, ENABLED);
  public static final Stats.ThreadSafeCounter vcEpochHB =
      new Stats.ThreadSafeCounter("FibVCEpochHB", false, ENABLED);
  public static final Stats.ThreadSafeCounter vcMapHB =
      new Stats.ThreadSafeCounter("FibVCMapHB", false, ENABLED);
  
  // Arrays
  public static final Stats.ThreadSafeCounter arrayShadows =
      new Stats.ThreadSafeCounter("FibArrayShadows", false, ENABLED);
  public static final Stats.ThreadSafeCounter aload =
      new Stats.ThreadSafeCounter("FibArrayRead", false, ENABLED);
  public static final Stats.ThreadSafeCounter astore =
      new Stats.ThreadSafeCounter("FibArrayWrite", false, ENABLED);
  
  // Synchronization

  public static final Stats.ThreadSafeCounter acquire =
      new Stats.ThreadSafeCounter("FibAcquire", false, ENABLED);
  public static final Stats.ThreadSafeCounter release =
      new Stats.ThreadSafeCounter("FibRelease", false, ENABLED);
  public static final Stats.ThreadSafeCounter volatileRead =
      new Stats.ThreadSafeCounter("FibVolatileRead", false, ENABLED);
  public static final Stats.ThreadSafeCounter volatileWrite =
      new Stats.ThreadSafeCounter("FibVolatileWrite", false, ENABLED);
  public static final Stats.ThreadSafeCounter syncNonEscaped =
      new Stats.ThreadSafeCounter("FibSyncNonEscaped", false, ENABLED);
  public static final Stats.ThreadSafeCounter clockOverflow =
      new Stats.ThreadSafeCounter("FibClockOverflow", false, ENABLED);
  
  // All writes.
  public static final Stats.ThreadSafeCounter write =
      new Stats.ThreadSafeCounter("FibWrite", false, ENABLED);
  
  // Types of writes.
  // Local writes
  public static final Stats.ThreadSafeCounter writeNonEscaped =
      new Stats.ThreadSafeCounter("FibWriteNonEscaped", false, ENABLED);
  public static final Stats.ThreadSafeCounter writeSameWriteEpoch =
      new Stats.ThreadSafeCounter("FibWriteSameWriteEpoch", false, ENABLED);
  public static final Stats.ThreadSafeCounter writeSameReadEpoch =
      new Stats.ThreadSafeCounter("FibWriteSameReadEpoch", false, ENABLED);
  public static final Stats.ThreadSafeCounter writeOwned =
      new Stats.ThreadSafeCounter("FibWriteOwned", false, ENABLED);
  
  public static final Stats.ThreadSafeCounter writeCasSafe =
      new Stats.ThreadSafeCounter("FibCasWriteSafe", false, ENABLED);
  public static final Stats.ThreadSafeCounter writeCasConcurrentRace =
      new Stats.ThreadSafeCounter("FibCasWriteConcurrentRace", false, ENABLED);
  public static final Stats.ThreadSafeCounter writeCasOrderRace =
      new Stats.ThreadSafeCounter("FibCasWriteOrderRace", false, ENABLED);

  public static final Stats.ThreadSafeCounter writeDelegated =
      new Stats.ThreadSafeCounter("FibWriteDelegated", false, ENABLED);

  private static final Stats.ThreadSafeSumCounter writeLocal =
      new Stats.ThreadSafeSumCounter("FibWriteLocal", ENABLED, 
      writeSameWriteEpoch, writeSameReadEpoch, writeOwned,
      writeCasSafe, writeCasConcurrentRace, writeCasOrderRace,
      writeDelegated);

  

  public static final Stats.ThreadSafeCounter writeFirst =
      new Stats.ThreadSafeCounter("FibWriteFirst", false, ENABLED);
  public static final Stats.ThreadSafeCounter writeReserved =
      new Stats.ThreadSafeCounter("FibWriteReserved", false, ENABLED);
  
  // Communicating writes
  public static final Stats.ThreadSafeCounter writeRemote =
      new Stats.ThreadSafeCounter("FibWriteRemote", false, ENABLED);
  // Also counted in remote.
  public static final Stats.ThreadSafeCounter writeShared =
      new Stats.ThreadSafeCounter("FibWriteShared", false, ENABLED);

  // Write ratios
  private static final Stats.ThreadSafeRatioCounter writeSameWriteEpochRatio =
      new Stats.ThreadSafeRatioCounter("FibWriteSameWriteEpochRatio", writeSameWriteEpoch, write, ENABLED);
  private static final Stats.ThreadSafeRatioCounter writeSameReadEpochRatio =
      new Stats.ThreadSafeRatioCounter("FibWriteSameReadEpochRatio", writeSameReadEpoch, write, ENABLED);
  private static final Stats.ThreadSafeRatioCounter writeOwnedRatio =
      new Stats.ThreadSafeRatioCounter("FibWriteOwnedRatio", writeOwned, write, ENABLED);
  private static final Stats.ThreadSafeRatioCounter writeDelegatedRatio =
      new Stats.ThreadSafeRatioCounter("FibWriteDelegatedRatio", writeDelegated, write, ENABLED);
  private static final Stats.ThreadSafeRatioCounter writeLocalRatio =
      new Stats.ThreadSafeRatioCounter("FibWriteLocalRatio", writeLocal, write, ENABLED);

  private static final Stats.ThreadSafeRatioCounter writeFirstRatio =
      new Stats.ThreadSafeRatioCounter("FibWriteFirstRatio", writeFirst, write, ENABLED);
  
  private static final Stats.ThreadSafeRatioCounter writeRemoteRatio =
      new Stats.ThreadSafeRatioCounter("FibWriteRemoteRatio", writeRemote, write, ENABLED);
  private static final Stats.ThreadSafeRatioCounter writeSharedRatio =
      new Stats.ThreadSafeRatioCounter("FibWriteSharedRatio", writeShared, write, ENABLED);
  
  
  // All reads
  public static final Stats.ThreadSafeCounter read =
      new Stats.ThreadSafeCounter("FibRead", false, ENABLED);
  // Types of reads
  // Local reads
  public static final Stats.ThreadSafeCounter readNonEscaped =
      new Stats.ThreadSafeCounter("FibReadNonEscaped", false, ENABLED);
  public static final Stats.ThreadSafeCounter readSameReadEpoch =
      new Stats.ThreadSafeCounter("FibReadSameReadEpoch", false, ENABLED);
  public static final Stats.ThreadSafeCounter readOwned =
      new Stats.ThreadSafeCounter("FibReadOwned", false, ENABLED);
  
  public static final Stats.ThreadSafeCounter readCasExcl =
      new Stats.ThreadSafeCounter("FibCasReadExclSafe", false, ENABLED);
  public static final Stats.ThreadSafeCounter readCasShare =
      new Stats.ThreadSafeCounter("FibCasReadShareSafe", false, ENABLED);
  public static final Stats.ThreadSafeCounter readCasShareConcurrentRace =
      new Stats.ThreadSafeCounter("FibCasReadShareRace", false, ENABLED);
  public static final Stats.ThreadSafeCounter readCasOrderRace =
      new Stats.ThreadSafeCounter("FibCasReadRace", false, ENABLED);
 
  public static final Stats.ThreadSafeCounter readCasShareRetry =
      new Stats.ThreadSafeCounter("FibCasReadShareRetry", false, ENABLED);
  public static final Stats.ThreadSafeCounter readCasConcurrentUnCas =
      new Stats.ThreadSafeCounter("FibCasReadConcurrentUnCas", false, ENABLED);
  public static final Stats.ThreadSafeCounter readCasShared =
      new Stats.ThreadSafeCounter("FibCasReadShared", false, ENABLED);
  
  public static final Stats.ThreadSafeCounter readDelegated =
      new Stats.ThreadSafeCounter("FibReadDelegated", false, ENABLED);
  
  public static final Stats.ThreadSafeCounter readSharedSameEpoch =
      new Stats.ThreadSafeCounter("FibReadSharedSameEpoch", false, ENABLED);
  public static final Stats.ThreadSafeCounter readSharedAgain =
      new Stats.ThreadSafeCounter("FibReadSharedAgain", false, ENABLED);
  public static final Stats.ThreadSafeCounter readShared =
      new Stats.ThreadSafeCounter("FibReadShared", false, ENABLED);
  
  private static final Stats.ThreadSafeSumCounter readLocal =
      new Stats.ThreadSafeSumCounter("FibReadLocal", ENABLED,
          readSameReadEpoch, readOwned,
          readCasExcl, readCasShare, readCasOrderRace,
          readDelegated,
          readShared, readSharedSameEpoch, readSharedAgain);

  public static final Stats.ThreadSafeCounter readFirst =
      new Stats.ThreadSafeCounter("FibReadFirst", false, ENABLED);
  public static final Stats.ThreadSafeCounter readReserved =
      new Stats.ThreadSafeCounter("FibReadReserved", false, ENABLED);
  
  public static final Stats.ThreadSafeCounter readRemote =
      new Stats.ThreadSafeCounter("FibReadRemote", false, ENABLED);
  // This is also counted in readRemote!
  public static final Stats.ThreadSafeCounter readShare =
      new Stats.ThreadSafeCounter("FibReadShare", false, ENABLED);

  
  // Read ratios
  private static final Stats.ThreadSafeRatioCounter readSameEpochRatio =
      new Stats.ThreadSafeRatioCounter("FibReadSameEpochRatio", readSameReadEpoch, read, ENABLED);
  private static final Stats.ThreadSafeRatioCounter readOwnedRatio =
      new Stats.ThreadSafeRatioCounter("FibReadOwnedRatio", readOwned, read, ENABLED);
  private static final Stats.ThreadSafeRatioCounter readDelegatedRatio =
      new Stats.ThreadSafeRatioCounter("FibReadDelegatedRatio", readDelegated, read, ENABLED);
  private static final Stats.ThreadSafeRatioCounter readSharedSameEpochRatio =
      new Stats.ThreadSafeRatioCounter("FibReadSharedSameEpochRatio", readSharedSameEpoch, read, ENABLED);
  private static final Stats.ThreadSafeRatioCounter readSharedAgainRatio =
      new Stats.ThreadSafeRatioCounter("FibReadSharedAgainRatio", readSharedAgain, read, ENABLED);
  private static final Stats.ThreadSafeRatioCounter readSharedRatio =
      new Stats.ThreadSafeRatioCounter("FibReadSharedRatio", readShared, read, ENABLED);
  private static final Stats.ThreadSafeRatioCounter readLocalRatio =
      new Stats.ThreadSafeRatioCounter("FibReadLocalRatio", readLocal, read, ENABLED);

  private static final Stats.ThreadSafeRatioCounter readFirstRatio =
      new Stats.ThreadSafeRatioCounter("FibReadFirstRatio", readFirst, read, ENABLED);
  
  private static final Stats.ThreadSafeRatioCounter readRemoteRatio =
      new Stats.ThreadSafeRatioCounter("FibReadRemoteRatio", readRemote, read, ENABLED);
  private static final Stats.ThreadSafeRatioCounter readShareRatio =
      new Stats.ThreadSafeRatioCounter("FibReadShareRatio", readShare, read, ENABLED);

  
  // All accesses
  private static final Stats.ThreadSafeSumCounter access =
      new Stats.ThreadSafeSumCounter("FibAccess", ENABLED, write, read);
  
  // All local accesses
  private static final Stats.ThreadSafeSumCounter localAccess =
      new Stats.ThreadSafeSumCounter("FibLocalAccess", ENABLED, writeLocal, readLocal);

  // All first accesses
  private static final Stats.ThreadSafeSumCounter firstAccess =
      new Stats.ThreadSafeSumCounter("FibFirstAccess", ENABLED, writeFirst, readFirst);
  
  // All communicating accesses
  private static final Stats.ThreadSafeSumCounter commAccess =
      new Stats.ThreadSafeSumCounter("FibCommAccess", ENABLED, writeRemote, readRemote);

  private static final Stats.ThreadSafeRatioCounter writeRatio =
      new Stats.ThreadSafeRatioCounter("FibWriteRatio", write, access, ENABLED);
  private static final Stats.ThreadSafeRatioCounter readRatio =
      new Stats.ThreadSafeRatioCounter("FibReadRatio", read, access, ENABLED);
  private static final Stats.ThreadSafeRatioCounter localAccessRatio =
      new Stats.ThreadSafeRatioCounter("FibLocalAccessRatio", localAccess, access, ENABLED);
  private static final Stats.ThreadSafeRatioCounter firstAccessRatio =
      new Stats.ThreadSafeRatioCounter("FibFirstAccessRatio", firstAccess, access, ENABLED);
  private static final Stats.ThreadSafeRatioCounter commAccessRatio =
      new Stats.ThreadSafeRatioCounter("FibCommAccessRatio", commAccess, access, ENABLED);
  
  
  
  
  // Types of protocol races during transitioning writes.
  public static final Stats.ThreadSafeCounter writeFirstConflict =
      new Stats.ThreadSafeCounter("FibWriteFirstConflict", false, ENABLED);
  // Types of races during transitioning reads.
  public static final Stats.ThreadSafeCounter readFirstConflict =
      new Stats.ThreadSafeCounter("FibReadFirstConflict", false, ENABLED);
  
  
  // Histogram of size of reader set upon deflation.
  public static final Stats.UnsyncHistogram deflationWidth =
      new Stats.UnsyncHistogram("FibDeflationWidth", false, Epoch.MAX_THREADS + 1, ENABLED);

  public static final Stats.ThreadSafeCounter fibThreadMonitorOps =
      new Stats.ThreadSafeCounter("FibFibThreadMonitorOps", false, ENABLED);
  public static final Stats.ThreadSafeCounter nonFibThreadMonitorOps =
      new Stats.ThreadSafeCounter("FibNonFibThreadMonitorOps", false, ENABLED);
  public static final Stats.ThreadSafeCounter recursiveMonitorOps =
      new Stats.ThreadSafeCounter("FibRecursiveMonitorOps", false, ENABLED);
  public static final Stats.ThreadSafeCounter nonRecursiveMonitorOps =
      new Stats.ThreadSafeCounter("FibNonRecursiveMonitorOps", false, ENABLED);
  

  
  // Stats for communication protocol.
  // requests
  public static final Stats.ThreadSafeCounter requestExcl =
      new Stats.ThreadSafeCounter("FibRequestExcl", false, ENABLED);
  public static final Stats.ThreadSafeCounter requestExclFail =
      new Stats.ThreadSafeCounter("FibRequestExclFail", false, ENABLED);
  
  public static final Stats.ThreadSafeCounter requestExclBecameShared =
      new Stats.ThreadSafeCounter("FibRequestExclBecameShared", false, ENABLED);
  
  public static final Stats.ThreadSafeCounter requestExclSelfService =
      new Stats.ThreadSafeCounter("FibRequestExclSelfService", false, ENABLED);
  public static final Stats.ThreadSafeCounter requestExclSelfServiceFail =
      new Stats.ThreadSafeCounter("FibRequestExclSelfServiceFail", false, ENABLED);
  
  public static final UnsyncHistogramPair requestHops =
      new UnsyncHistogramPair("FibRequestHops", "RaceFree", "Race", false, 8 * Epoch.MAX_THREADS);
    
  public static final Stats.ThreadSafeCounter requestAck =
      new Stats.ThreadSafeCounter("FibRequestAck", false, ENABLED);
  public static final Stats.ThreadSafeCounter requestAckSelfService =
      new Stats.ThreadSafeCounter("FibRequestAckSelfService", false, ENABLED);
  
  public static final UnsyncHistogramPair requestRetryIters =
      new UnsyncHistogramPair("FibRequestRetryIters", "RaceFree", "Race", true, Integer.MAX_VALUE);
  public static final Stats.ThreadSafeCounter slowRequests =
      new Stats.ThreadSafeCounter("FibSlowRequests", false, ENABLED);
  public static final Stats.ThreadSafeCounter yieldsWhileRetryingRequest =
      new Stats.ThreadSafeCounter("FibYieldsWhileRetryingRequest", false, ENABLED);
  

  // normalized
  public static final Stats.ThreadSafeRatioCounter requestExclPerAccess =
      new Stats.ThreadSafeRatioCounter("FibRequestExclPerAccess", requestExcl, access, ENABLED);
  public static final Stats.ThreadSafeRatioCounter requestExclFailPerAccess =
      new Stats.ThreadSafeRatioCounter("FibRequestExclFailPerAccess", requestExclFail, access, ENABLED);
  
  public static final Stats.ThreadSafeRatioCounter requestExclBecameSharedPerAccess =
      new Stats.ThreadSafeRatioCounter("FibRequestExclBecameSharedPerAccess", requestExclBecameShared, access, ENABLED);
  
  public static final Stats.ThreadSafeRatioCounter requestExclSelfServicePerAccess =
      new Stats.ThreadSafeRatioCounter("FibRequestExclSelfServicePerAccess", requestExclSelfService, access, ENABLED);
  public static final Stats.ThreadSafeRatioCounter requestExclSelfServiceFailPerAccess =
      new Stats.ThreadSafeRatioCounter("FibRequestExclSelfServiceFailPerAccess", requestExclSelfServiceFail, access, ENABLED);
  
  public static final Stats.ThreadSafeRatioCounter requestAckPerAccess =
      new Stats.ThreadSafeRatioCounter("FibRequestAckPerAccess", requestAck, access, ENABLED);
  public static final Stats.ThreadSafeRatioCounter requestAckSelfServicePerAccess =
      new Stats.ThreadSafeRatioCounter("FibRequestAckSelfServicePerAccess", requestAckSelfService, access, ENABLED);

  // responses
  public static final Stats.ThreadSafeCounter respondExclRead =
      new Stats.ThreadSafeCounter("FibRespondRead", false, ENABLED);

  public static final Stats.ThreadSafeCounter respondExclWrite =
      new Stats.ThreadSafeCounter("FibRespondWrite", false, ENABLED);

  private static final Stats.ThreadSafeSumCounter respondExcl =
      new Stats.ThreadSafeSumCounter("FibRespondExcl", ENABLED, respondExclRead, respondExclWrite);

  public static final Stats.ThreadSafeCounter respondShare =
      new Stats.ThreadSafeCounter("FibRespondShare", false, ENABLED);
  
  public static final Stats.ThreadSafeCounter respondShared =
      new Stats.ThreadSafeCounter("FibRespondShared", false, ENABLED);
  
  public static final Stats.ThreadSafeCounter respondRace =
      new Stats.ThreadSafeCounter("FibRespondRace", false, ENABLED);
  
  public static final Stats.ThreadSafeCounter ack =
      new Stats.ThreadSafeCounter("FibAck", false, ENABLED);
  
  private static final Stats.ThreadSafeSumCounter responses =
      new Stats.ThreadSafeSumCounter("FibResponses", ENABLED, respondExcl, respondShare, respondShared, respondRace, ack);
  
  public static final UnsyncHistogramPair triesAwaitingResponse =
      new UnsyncHistogramPair("FibTriesAwaitingResponse", "RaceFree", "Race", true, Integer.MAX_VALUE);
  public static final Stats.UnsyncHistogram missedYieldOpportunityWhileAwaitingResponse =
      new Stats.UnsyncHistogram("MissedYieldOpportunityWhileAwaitingResponse", true, Integer.MAX_VALUE, ENABLED);
  public static final Stats.ThreadSafeCounter heavyResponses =
      new Stats.ThreadSafeCounter("FibHeavyResponses", false, ENABLED);
  private static final Stats.ThreadSafeRatioCounter heavyResponseRatio =
      new Stats.ThreadSafeRatioCounter("FibHeavyResponseRatio", heavyResponses, responses, ENABLED);
  public static final Stats.ThreadSafeCounter slowResponses =
      new Stats.ThreadSafeCounter("FibSlowResponses", false, ENABLED);
  private static final Stats.ThreadSafeRatioCounter slowResponseRatio =
      new Stats.ThreadSafeRatioCounter("FibSlowResponseRatio", slowResponses, responses, ENABLED);
  public static final Stats.ThreadSafeCounter yieldsWhileAwaitingResponse =
      new Stats.ThreadSafeCounter("FibYieldsWhileAwaitingResponse", false, ENABLED);
  public static final Stats.ThreadSafeCounter heavyWaitsWhileAwaitingResponse =
      new Stats.ThreadSafeCounter("FibHeavyWaitsWhileAwaitingResponse", false, ENABLED);

  public static final Stats.UnsyncHistogram triesAwaitingReservedReadWord =
      new Stats.UnsyncHistogram("FibTriesAwaitingReservedReadWord", true, Integer.MAX_VALUE, ENABLED);
  public static final Stats.ThreadSafeCounter slowWaitsForReservedReadWord =
      new Stats.ThreadSafeCounter("FibSlowWaitsForReservedReadWord", false, ENABLED);
  public static final Stats.ThreadSafeCounter yieldsWhileAwaitingReservedReadWord =
      new Stats.ThreadSafeCounter("FibYieldsWhileAwaitingReservedReadWord", false, ENABLED);

  // normalized
  private static final Stats.ThreadSafeRatioCounter responsesExclPerAccess =
      new Stats.ThreadSafeRatioCounter("FibRespondExclPerAccess", respondExcl, access, ENABLED);
  private static final Stats.ThreadSafeRatioCounter responsesExclPerResponse =
      new Stats.ThreadSafeRatioCounter("FibRespondExclPerResponse", respondExcl, responses, ENABLED);

  private static final Stats.ThreadSafeRatioCounter acksPerAccess =
      new Stats.ThreadSafeRatioCounter("FibAckPerAccess", ack, access, ENABLED);
  private static final Stats.ThreadSafeRatioCounter acksPerWrite =
      new Stats.ThreadSafeRatioCounter("FibAckPerWrite", ack, write, ENABLED);
  private static final Stats.ThreadSafeRatioCounter acksPerResponse =
      new Stats.ThreadSafeRatioCounter("FibAckPerResponse", ack, responses, ENABLED);
  
  private static final Stats.ThreadSafeRatioCounter respondSharePerAccess =
      new Stats.ThreadSafeRatioCounter("FibRespondSharePerAccess", respondShare, access, ENABLED);
  private static final Stats.ThreadSafeRatioCounter respondSharePerRead =
      new Stats.ThreadSafeRatioCounter("FibRespondSharePerRead", respondShare, read, ENABLED);
  private static final Stats.ThreadSafeRatioCounter respondSharePerResponse =
      new Stats.ThreadSafeRatioCounter("FibRespondSharePerResponse", respondShare, responses, ENABLED);
  
  private static final Stats.ThreadSafeRatioCounter respondSharedPerAccess =
      new Stats.ThreadSafeRatioCounter("FibRespondSharedPerAccess", respondShared, access, ENABLED);
  private static final Stats.ThreadSafeRatioCounter respondSharedPerRead =
      new Stats.ThreadSafeRatioCounter("FibRespondSharedPerRead", respondShared, read, ENABLED);
  private static final Stats.ThreadSafeRatioCounter respondSharedPerResponse=
      new Stats.ThreadSafeRatioCounter("FibRespondSharedPerResponse", respondShared, responses, ENABLED);

  private static final Stats.ThreadSafeRatioCounter respondExclReadPerAccess =
      new Stats.ThreadSafeRatioCounter("FibRespondExclReadPerAccess", respondExclRead, access, ENABLED);
  private static final Stats.ThreadSafeRatioCounter respondExclReadPerRead =
      new Stats.ThreadSafeRatioCounter("FibRespondExclReadPerRead", respondExclRead, read, ENABLED);
  private static final Stats.ThreadSafeRatioCounter respondExclReadPerResponse=
      new Stats.ThreadSafeRatioCounter("FibRespondExclReadPerResponse", respondExclRead, responses, ENABLED);

  private static final Stats.ThreadSafeRatioCounter respondExclWritePerAccess =
      new Stats.ThreadSafeRatioCounter("FibRespondExclWritePerAccess", respondExclWrite, access, ENABLED);
  private static final Stats.ThreadSafeRatioCounter respondExclWritePerWrite =
      new Stats.ThreadSafeRatioCounter("FibRespondExclWritePerWrite", respondExclWrite, write, ENABLED);
  private static final Stats.ThreadSafeRatioCounter respondExclWritePerResponse =
      new Stats.ThreadSafeRatioCounter("FibRespondExclWritePerResponse", respondExclWrite, responses, ENABLED);

  private static final Stats.ThreadSafeRatioCounter respondRacePerAccess =
      new Stats.ThreadSafeRatioCounter("FibRespondRacePerAccess", respondRace, access, ENABLED);
  private static final Stats.ThreadSafeRatioCounter respondRacePerResponse =
      new Stats.ThreadSafeRatioCounter("FibRespondRacePerResponse", respondRace, responses, ENABLED);
  private static final Stats.ThreadSafeRatioCounter respondRacePerRace =
      new Stats.ThreadSafeRatioCounter("FibRespondRacePerRace", respondRace, races, ENABLED);

  
  
  // yields, blocks
  public static final Stats.ThreadSafeCounter yieldsRespondExcl =
      new Stats.ThreadSafeCounter("FibYieldsRespondExcl", false, ENABLED);
  
  public static final Stats.UnsyncHistogram requestQueueSize =
      new Stats.UnsyncHistogram("FibRequestQueueSize", false, Epoch.MAX_THREADS, ENABLED); 
  
  public static final Stats.ThreadSafeCounter yieldsRespondAck =
      new Stats.ThreadSafeCounter("FibYieldsRespondAck", false, ENABLED);
  
  public static final Stats.ThreadSafeCounter recursiveBlock =
      new Stats.ThreadSafeCounter("FibRecursiveBlock", false, ENABLED);
  public static final Stats.ThreadSafeCounter recursiveUnblock =
      new Stats.ThreadSafeCounter("FibRecursiveUnblock", false, ENABLED);
  public static final Stats.ThreadSafeCounter fastBlock =
      new Stats.ThreadSafeCounter("FibFastBlock", false, ENABLED);
  public static final Stats.ThreadSafeCounter slowBlock =
      new Stats.ThreadSafeCounter("FibSlowBlock", false, ENABLED);
  public static final Stats.ThreadSafeSumCounter block =
      new Stats.ThreadSafeSumCounter("FibBlock", ENABLED, recursiveBlock, fastBlock, slowBlock);
  public static final Stats.ThreadSafeCounter nonRecursiveUnblock =
      new Stats.ThreadSafeCounter("FibNonRecursiveUnblock", false, ENABLED);
  public static final Stats.ThreadSafeSumCounter unblock =
      new Stats.ThreadSafeSumCounter("FibUnblock", ENABLED, recursiveUnblock, nonRecursiveUnblock);

  public static final Stats.ThreadSafeCounter fastProtocolYield =
      new Stats.ThreadSafeCounter("FibFastProtocolYield", false, ENABLED);
  public static final Stats.ThreadSafeCounter slowProtocolYield =
      new Stats.ThreadSafeCounter("FibSlowProtocolYield", false, ENABLED);
  public static final Stats.ThreadSafeSumCounter protocolYield =
      new Stats.ThreadSafeSumCounter("FibProtocolYield", ENABLED, fastProtocolYield, slowProtocolYield);

  private static final Stats.ThreadSafeSumCounter yield =
      new Stats.ThreadSafeSumCounter("FibYield", ENABLED, block, protocolYield);
  private static final Stats.ThreadSafeRatioCounter responsesPerYield =
      new Stats.ThreadSafeRatioCounter("FibResponsesPerYield", responses, yield, ENABLED);
  

  // normalized
  private static final Stats.ThreadSafeRatioCounter responsesYieldExclPerYield =
      new Stats.ThreadSafeRatioCounter("FibResponsesYieldExclPerYield", yieldsRespondExcl, yield, ENABLED);
  
  private static final Stats.ThreadSafeRatioCounter responseYieldAckPerYield =
      new Stats.ThreadSafeRatioCounter("FibResponsesYieldAckPerYield", yieldsRespondAck, yield, ENABLED);
  
  private static final Stats.ThreadSafeRatioCounter recursiveBlocksPerBlock =
      new Stats.ThreadSafeRatioCounter("FibRecursiveBlocksPerBlock", recursiveBlock, block, ENABLED);
  private static final Stats.ThreadSafeRatioCounter fastBlocksPerBlock =
      new Stats.ThreadSafeRatioCounter("FibFastBlocksPerBlock", fastBlock, block, ENABLED);
  private static final Stats.ThreadSafeRatioCounter slowBlocksPerBlock =
      new Stats.ThreadSafeRatioCounter("FibSlowBlocksPerBlock", slowBlock, block, ENABLED);
  private static final Stats.ThreadSafeRatioCounter fastProtocolYieldsPerProtocolYield =
      new Stats.ThreadSafeRatioCounter("FibFastProtocolYieldsPerProtocolYield", fastProtocolYield, protocolYield, ENABLED);
  private static final Stats.ThreadSafeRatioCounter slowProtocolYieldsPerProtocolYield =
      new Stats.ThreadSafeRatioCounter("FibSlowProtocolYieldsPerProtocolYield", slowProtocolYield, protocolYield, ENABLED);

  
  // Sanity checking.
  public static final Stats.ThreadSafeCounter unexpectedReservationRollback =
      new Stats.ThreadSafeCounter("FibUnexpectedReservationRollback", false, ENABLED);
  
  
  public static final Stats.UnsyncHistogram requestsSentPerEpoch =
      new Stats.UnsyncHistogram("FibRequestsSentPerEpoch", true, Integer.MAX_VALUE, ENABLED);
  public static final Stats.UnsyncHistogram requestsReceivedPerEpoch =
      new Stats.UnsyncHistogram("FibRequestsReceivedPerEpoch", true, Integer.MAX_VALUE, ENABLED);

  public static final Stats.UnsyncHistogram sharedResponsesSentPerEpoch =
      new Stats.UnsyncHistogram("FibSharedResponsesSentPerEpoch", true, Integer.MAX_VALUE, ENABLED);
  public static final Stats.UnsyncHistogram sharedResponsesReceivedPerEpoch =
      new Stats.UnsyncHistogram("FibSharedResponsesReceivedPerEpoch", true, Integer.MAX_VALUE, ENABLED);
  
  public static final Stats.UnsyncHistogram exclusiveResponsesSentPerEpoch =
      new Stats.UnsyncHistogram("FibExclusiveResponsesSentPerEpoch", true, Integer.MAX_VALUE, ENABLED);
  public static final Stats.UnsyncHistogram exclusiveResponsesReceivedPerEpoch =
      new Stats.UnsyncHistogram("FibExclusiveResponsesReceivedPerEpoch", true, Integer.MAX_VALUE, ENABLED);
  
  public static final Stats.UnsyncHistogram distinctEpochsRequestedPerEpoch =
      new Stats.UnsyncHistogram("FibDistinctEpochsRequestedPerEpoch", true, Integer.MAX_VALUE, ENABLED);
  public static final Stats.ThreadSafeCounter distinctEpochsOverflow =
      new Stats.ThreadSafeCounter("FibDistinctEpochsOverflow", false, ENABLED);
  public static final Stats.UnsyncHistogram distinctThreadsRequestedPerEpoch =
      new Stats.UnsyncHistogram("FibDistinctThreadsRequestedPerEpoch", true, Integer.MAX_VALUE, ENABLED);
  public static final Stats.UnsyncHistogram percentDistinctEpochRequestsPerEpoch =
      new Stats.UnsyncHistogram("FibPercentDistinctEpochRequestsPerEpoch", true, Integer.MAX_VALUE, ENABLED);

  public static final Stats.UnsyncHistogram transitionsPerLoc =
      new Stats.UnsyncHistogram("FibTransitionsPerLoc", true, Integer.MAX_VALUE, ENABLED);
  public static final Stats.UnsyncHistogram lockDominatedTransitionsLatestHolderPerLoc =
      new Stats.UnsyncHistogram("FibLockDominatedTransitionsLatestHolderPerLoc", true, Integer.MAX_VALUE, ENABLED);
  
  // Preemptive Read Share
  public static final Stats.ThreadSafeCounter preemptiveReadShare =
      new Stats.ThreadSafeCounter("FibPreemptiveReadShare", false, ENABLED);
  public static final Stats.ThreadSafeCounter preemptiveReadShareBlocked =
      new Stats.ThreadSafeCounter("FibPreemptiveReadShareBlocked", false, ENABLED);
  public static final Stats.ThreadSafeCounter preemptiveReadShareRemote =
      new Stats.ThreadSafeCounter("FibPreemptiveReadShareRemote", false, ENABLED);
  public static final Stats.ThreadSafeCounter preemptiveReadShareTermDel =
      new Stats.ThreadSafeCounter("FibPreemptiveReadShareTermDel", false, ENABLED);
  
  // Threshold
  public static final Stats.ThreadSafeCounter thresholdChecked =
      new Stats.ThreadSafeCounter("FibThresholdChecked", false, ENABLED);
  public static final Stats.ThreadSafeCounter thresholdPassed =
      new Stats.ThreadSafeCounter("FibThresholdPassed", false, ENABLED);
  public static final Stats.ThreadSafeCounter thresholdPinned =
      new Stats.ThreadSafeCounter("FibPinThreshold", false, ENABLED);
  public static final Stats.ThreadSafeCounter thresholdReached =
      new Stats.ThreadSafeCounter("FibThresholdReached", false, ENABLED);
  
  // Stride Epoch Maps
  public static final Stats.ThreadSafeCounter strideBlobsAllocated =  
      new Stats.ThreadSafeCounter("FibStrideBlobsAllocated", false, ENABLED);
  public static final Stats.ThreadSafeCounter strideMapsAllocated =
      new Stats.ThreadSafeCounter("FibStrideMapsAllocated", false, ENABLED);
  public static final Stats.ThreadSafeCounter strideBlobsReleased =
      new Stats.ThreadSafeCounter("FibStrideBlobsReleased", false, ENABLED);
  public static final Stats.ThreadSafeCounter strideMapsReleased =
      new Stats.ThreadSafeCounter("FibStrideMapsReleased", false, ENABLED);
  
  public static final Stats.UnsyncHistogram strideBlobsLiveBeforeGC =
      new Stats.UnsyncHistogram("FibStrideBlobsLiveBeforeGC", true, 1024, ENABLED);
  public static final Stats.UnsyncHistogram strideMapsLiveBeforeGC =
      new Stats.UnsyncHistogram("FibStrideMapsLiveBeforeGC", true, 8192, ENABLED);
  public static final Stats.UnsyncHistogram strideBlobsPerThreadLiveBeforeGC =
      new Stats.UnsyncHistogram("FibStrideBlobsPerThreadLiveBeforeGC", true, 1024, ENABLED);
  public static final Stats.UnsyncHistogram strideMapsPerThreadLiveBeforeGC =
      new Stats.UnsyncHistogram("FibStrideMapsPerThreadLiveBeforeGC", true, 1024, ENABLED);
  public static final Stats.UnsyncHistogram strideMapsLivePerBlobBeforeGC =
      new Stats.UnsyncHistogram("FibStrideMapsLivePerBlobBeforeGC", false, 16, ENABLED);
  
  public static final Stats.UnsyncHistogram strideBlobsLiveAfterGC =
      new Stats.UnsyncHistogram("FibStrideBlobsLiveAfterGC", true, 1024, ENABLED);
  public static final Stats.UnsyncHistogram strideMapsLiveAfterGC =
      new Stats.UnsyncHistogram("FibStrideMapsLiveAfterGC", true, 8192, ENABLED);
  public static final Stats.UnsyncHistogram strideBlobsPerThreadLiveAfterGC =
      new Stats.UnsyncHistogram("FibStrideBlobsPerThreadLiveAfterGC", true, 1024, ENABLED);
  public static final Stats.UnsyncHistogram strideMapsPerThreadLiveAfterGC =
      new Stats.UnsyncHistogram("FibStrideMapsPerThreadLiveAfterGC", true, 1024, ENABLED);
  public static final Stats.UnsyncHistogram strideMapsLivePerBlobAfterGC =
      new Stats.UnsyncHistogram("FibStrideMapsLivePerBlobAfterGC", false, 16, ENABLED);

  public static final Stats.ThreadSafeCounter strideBlobCompactions =
      new Stats.ThreadSafeCounter("FibStrideBlobCompactions", false, ENABLED);

  
  // Class init sync
  public static final Stats.ThreadSafeCounter classInitVCs =
      new Stats.ThreadSafeCounter("FibClassInitVCs", false, ENABLED);
  public static final Stats.ThreadSafeCounter getStaticObserveInitAll =
      new Stats.ThreadSafeCounter("FibGetStaticObserveInitAll", false, ENABLED);
  public static final Stats.ThreadSafeCounter getStaticObserveInitMultithreaded =
      new Stats.ThreadSafeCounter("FibGetStaticObserveInitMultithreaded", false, ENABLED);
  public static final Stats.ThreadSafeCounter getStaticObserveInitSlow =
      new Stats.ThreadSafeCounter("FibGetStaticObserveInitSlow", false, ENABLED);
  
  private static final Stats.ThreadSafeRatioCounter getStaticObserveInitMultithreadedAllRatio =
      new Stats.ThreadSafeRatioCounter("FibGetStaticObserveInitMultithreadedAllRate", getStaticObserveInitMultithreaded, getStaticObserveInitAll, ENABLED);
  private static final Stats.ThreadSafeRatioCounter getStaticObserveInitSlowAllRatio =
      new Stats.ThreadSafeRatioCounter("FibGetStaticObserveInitSlowAllRate", getStaticObserveInitSlow, getStaticObserveInitAll, ENABLED);
  private static final Stats.ThreadSafeRatioCounter getStaticObserveInitSlowMultithreadedRatio =
      new Stats.ThreadSafeRatioCounter("FibGetStaticObserveInitSlowMultithreadedRate", getStaticObserveInitSlow, getStaticObserveInitAll, ENABLED);
//  public static final Stats.ThreadSafeCounter staticFinalFields =
//      new Stats.ThreadSafeCounter("FibStaticFinalFields", false, ENABLED);
//  public static final Stats.ThreadSafeCounter staticFinalReads =
//      new Stats.ThreadSafeCounter("FibStaticFinalReads", false, ENABLED);
//  public static final Stats.ThreadSafeCounter redudantStaticFinalReads =
//      new Stats.ThreadSafeCounter("FibRedundantStaticFinalReads", false, ENABLED);
//  public static final Stats.ThreadSafeCounter staticFinalReadInstrs =
//      new Stats.ThreadSafeCounter("FibStaticFinalReadInstrs", false, ENABLED);
  
  
  // Instrumentation choices
  
  public static final Stats.UnsyncCounter baselineStaticBarrierResolvedYes = 
      new Stats.UnsyncCounter("FibBaselineStaticBarrierResolvedYes", ENABLED);
  public static final Stats.UnsyncCounter baselineStaticBarrierResolvedNo = 
      new Stats.UnsyncCounter("FibBaselineStaticBarrierResolvedNo", ENABLED);
  public static final Stats.UnsyncCounter baselineStaticBarrierUnresolvedYes = 
      new Stats.UnsyncCounter("FibBaselineStaticBarrierUnresolvedYes", ENABLED);
  public static final Stats.UnsyncCounter baselineStaticBarrierUnresolvedNo= 
      new Stats.UnsyncCounter("FibBaselineStaticBarrierUnresolvedNo", ENABLED);
  public static final Stats.UnsyncCounter baselineFieldBarrierResolvedYes = 
      new Stats.UnsyncCounter("FibBaselineFieldBarrierResolvedYes", ENABLED);
  public static final Stats.UnsyncCounter baselineFieldBarrierResolvedNo = 
      new Stats.UnsyncCounter("FibBaselineFieldBarrierResolvedNo", ENABLED);
  public static final Stats.UnsyncCounter baselineFieldBarrierUnresolvedYes = 
      new Stats.UnsyncCounter("FibBaselineFieldBarrierUnresolvedYes", ENABLED);
  public static final Stats.UnsyncCounter baselineFieldBarrierUnresolvedNo = 
      new Stats.UnsyncCounter("FibBaselineFieldBarrierUnresolvedNo", ENABLED);
  public static final Stats.UnsyncCounter baselineArrayBarrierYes = 
      new Stats.UnsyncCounter("FibBaselineArrayBarrierYes", ENABLED);
  public static final Stats.UnsyncCounter baselineArrayBarrierNo = 
      new Stats.UnsyncCounter("FibBaselineArrayBarrierNo", ENABLED);

  public static final Stats.UnsyncCounter optStaticBarrierResolvedYes = 
      new Stats.UnsyncCounter("FibOptStaticBarrierResolvedYes", ENABLED);
  public static final Stats.UnsyncCounter optStaticBarrierResolvedNo = 
      new Stats.UnsyncCounter("FibOptStaticBarrierResolvedNo", ENABLED);
  public static final Stats.UnsyncCounter optStaticBarrierUnresolvedYes = 
      new Stats.UnsyncCounter("FibOptStaticBarrierUnresolvedYes", ENABLED);
  public static final Stats.UnsyncCounter optStaticBarrierUnresolvedNo= 
      new Stats.UnsyncCounter("FibOptStaticBarrierUnresolvedNo", ENABLED);
  public static final Stats.UnsyncCounter optFieldBarrierResolvedYes = 
      new Stats.UnsyncCounter("FibOptFieldBarrierResolvedYes", ENABLED);
  public static final Stats.UnsyncCounter optFieldBarrierResolvedNo = 
      new Stats.UnsyncCounter("FibOptFieldBarrierResolvedNo", ENABLED);
  public static final Stats.UnsyncCounter optFieldBarrierUnresolvedYes = 
      new Stats.UnsyncCounter("FibOptFieldBarrierUnresolvedYes", ENABLED);
  public static final Stats.UnsyncCounter optFieldBarrierUnresolvedNo = 
      new Stats.UnsyncCounter("FibOptFieldBarrierUnresolvedNo", ENABLED);
  public static final Stats.UnsyncCounter optArrayBarrierYes = 
      new Stats.UnsyncCounter("FibOptArrayBarrierYes", ENABLED);
  public static final Stats.UnsyncCounter optArrayBarrierNo = 
      new Stats.UnsyncCounter("FibOptArrayBarrierNo", ENABLED);

}
