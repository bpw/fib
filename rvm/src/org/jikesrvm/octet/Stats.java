package org.jikesrvm.octet;

import java.text.DecimalFormat;
import java.util.Arrays;
import java.util.Comparator;

import org.jikesrvm.Callbacks;
import org.jikesrvm.Callbacks.ExitMonitor;
import org.jikesrvm.Constants;
import org.jikesrvm.VM;
import org.jikesrvm.esc.Esc;
import org.jikesrvm.mm.mminterface.MemoryManager;
import org.jikesrvm.runtime.Time;
import org.jikesrvm.scheduler.RVMThread;
import org.jikesrvm.util.LinkedListRVM;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.pragma.NoInline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.pragma.UninterruptibleNoWarn;
import org.vmmagic.unboxed.Address;

/**
 * Octet: statistics and debugging.
 * 
 * In this file stats and counters are used interchangeably to refer to the same
 * idea. E.g., static stats means stats that are collected with a static counter.
 * 
 * @author Mike Bond
 * @author Meisam
 * @author Man
 * 
 */
@Uninterruptible
public class Stats implements Constants {

  private static final LinkedListRVM<Stat> all = new LinkedListRVM<Stat>();
  private static final boolean stats = Octet.getConfig().stats();
  private static final boolean measureFP = false; // Octet.getConfig().measureTimeFastPath();
  private static final boolean measureCT = false; // Octet.getConfig().measureTimeConflTrans();
  private static final boolean measurePT = false; // Octet.getConfig().measureTimePessiTrans();
  private static final boolean measureTime = measureFP | measureCT | measurePT;
  private static final boolean needThreadSafeCounter = stats|measureTime|Esc.getConfig().escapeStats();

  // measuring the communication algorithm:
  static final UnsyncHistogram logTimeCommunicateRequests = new UnsyncHistogram("logTimeCommunicateRequests", true, 1L<<32, stats);
  static final UnsyncHistogram logTimeSlowPath = new UnsyncHistogram("logTimeSlowPath", true, 1L<<32, stats);
  static final UnsyncHistogram threadsWaitedFor = new UnsyncHistogram("threadsWaitedFor", false, 128, stats);
  static final UnsyncHistogram logWaitIter = new UnsyncHistogram("waitIter", true, 1L<<16, stats);
  static final UnsyncHistogram logSlowPathIter = new UnsyncHistogram("slowPathIter", true, 1L<<32, stats);
  static final UnsyncHistogram logSlowPathObjectSize = new UnsyncHistogram("logSlowPathObjectSize", true, 1L<<32, stats);

  static final ThreadSafeCounter storeBufferFlushes = new ThreadSafeCounter("storeBufferFlushes", false, stats);
  public static final ThreadSafeCounter flushesInBlockComm = new ThreadSafeCounter("flushesInBlockComm", false, stats);
  public static final ThreadSafeCounter flushesAtYieldPoint = new ThreadSafeCounter("flushesAtYieldPoint", false, stats);
  static final ThreadSafeCounter flushesDuringTransitionSelf = new ThreadSafeCounter("flushesDuringTransitionSelf", false, stats);
  static final ThreadSafeCounter requestsToFlush = new ThreadSafeCounter("requestsToFlush", false, stats);
  public static final ThreadSafeCounter flushesBeforeGC = new ThreadSafeCounter("flushesBeforeGC", false, stats);
  static final ThreadSafeCounter flushesOnCommRequest = new ThreadSafeCounter("flushesOnCommRequest", false, stats); // For explicit protocol
  static final ThreadSafeCounter flushesForOtherThread = new ThreadSafeCounter("flushesForOtherThread", false, stats); // For implicit protocol
  static final ThreadSafeCounter flushesButAlreadyHeld = new ThreadSafeCounter("flushesButAlreadyHeld", false, stats);

  // histogram for # of objects/fields vs # of conflicting transitions
  static final ThreadSafeLog2Histogram logConflTransitions = new ThreadSafeLog2Histogram("logConflTransitions", true, 0xffffffff, stats);
  // # of objects/fields that have only one conflicting transition in their lifetime
  static final ThreadSafeCounter oneConflTransition = new ThreadSafeCounter("oneConflTransition", false, stats);
  static final ThreadSafeCounter oneConflWrEx_RdEx = new ThreadSafeCounter("oneConflWrEx_RdEx", false, stats);
  static final ThreadSafeCounter oneConflWrEx_WrEx = new ThreadSafeCounter("oneConflWrEx_WrEx", false, stats);
  
  static final ThreadSafeCounter totalTimeConflTransitions = new ThreadSafeCounter("totalTimeConflTransitions", false, measureCT);
  static final ThreadSafeCounter countConflTransitions = new ThreadSafeCounter("countConflTransitions", false, measureCT);
  static final ThreadSafeCounter totalTimePessiTransitions = new ThreadSafeCounter("totalTimePessiTransitions", false, measurePT);
  static final ThreadSafeCounter countPessiTransitions = new ThreadSafeCounter("countPessiTransitions", false, measurePT);
  static final ThreadSafeCounter totalTimeFastpath = new ThreadSafeCounter("totalTimeFastpath", false, measureFP);
  static final ThreadSafeCounter countFastpath = new ThreadSafeCounter("countFastpath", false, measureFP);
  static final ThreadSafeCounter totalTimeExplicitProtocol = new ThreadSafeCounter("totalTimeExplicitProtocol", false, measureCT);
  static final ThreadSafeCounter countExplicitProtocol = new ThreadSafeCounter("countExplicitProtocol", false, measureCT);
  static final ThreadSafeCounter totalTimeImplicitProtocol = new ThreadSafeCounter("totalTimeImplicitProtocol", false, measureCT);
  static final ThreadSafeCounter countImplicitProtocol = new ThreadSafeCounter("countImplicitProtocol", false, measureCT);
  static final ThreadSafeCounter totalTimeRdShToWrEx = new ThreadSafeCounter("totalTimeRdShToWrEx", false, measureCT);
  static final ThreadSafeCounter countRdShToWrEx = new ThreadSafeCounter("countRdShToWrEx", false, measureCT);

  static final ThreadSafeCounter readSharedCounterAvoidsSendRequestCall = new ThreadSafeCounter("readSharedCounterAvoidsSendRequestCall", true, stats);
  static final ThreadSafeCounter readSharedCounterAvoidsActualSendRequest = new ThreadSafeCounter("readSharedCounterAvoidsActualSendRequest", true, stats);
  static final ThreadSafeCounter rdExToRdShButOldThreadDied = new ThreadSafeCounter("rdExToRdShButOldThreadDied", true, stats);

  static final PerSiteCounter slowPathsEntered       = new PerSiteCounter("slowPathsEntered", stats);
  static final PerSiteCounter slowPathsExitedEarly   = new PerSiteCounter("slowPathsExitedEarly", stats);
  static final PerSiteCounter fastPathsEntered       = new PerSiteCounter("fastPathsEntered", stats);
  static final PerSiteCounter conflictingTransitions = new PerSiteCounter("conflictingTransitions", stats);

  public static final ThreadSafeCounter Alloc      = new ThreadSafeCounter("Alloc", false, stats);
  static final ThreadSafeCounter Uninit_Init       = new ThreadSafeCounter("Uninit_Init", false, stats);
  static final ThreadSafeCounter WrEx_WrEx_Same    = new ThreadSafeCounter("WrEx_WrEx_Same", false, stats);
  static final ThreadSafeCounter WrEx_WrEx_Diff    = new ThreadSafeCounter("WrEx_WrEx_Diff", false, stats);
  static final ThreadSafeCounter WrEx_RdEx_Diff    = new ThreadSafeCounter("WrEx_RdEx_Diff", false, stats);
  static final ThreadSafeCounter RdEx_WrEx_Same    = new ThreadSafeCounter("RdEx_WrEx_Same", false, stats);
  static final ThreadSafeCounter RdEx_WrEx_Diff    = new ThreadSafeCounter("RdEx_WrEx_Diff", false, stats);
  static final ThreadSafeCounter RdEx_RdEx_Same    = new ThreadSafeCounter("RdEx_RdEx_Same", false, stats);
  static final ThreadSafeCounter RdEx_RdSh         = new ThreadSafeCounter("RdEx_RdSh", false, stats);
  static final ThreadSafeCounter RdSh_WrEx         = new ThreadSafeCounter("RdSh_WrEx", false, stats);
  static final ThreadSafeCounter RdSh_RdSh_NoFence = new ThreadSafeCounter("RdSh_RdSh_NoFence", false, stats);
  static final ThreadSafeCounter RdSh_RdSh_Fence   = new ThreadSafeCounter("RdSh_RdSh_Fence", false, stats);
  // Man: This counter is for pessimistic stats only
  static final ThreadSafeCounter RdEx_RdEx_Diff    = new ThreadSafeCounter("RdEx_RdEx_Diff", false, stats);

  static final ThreadSafeCounter WrEx_PessiWrEx      = new ThreadSafeCounter("WrEx_PessiWrEx", false, stats);
  static final ThreadSafeCounter WrEx_PessiRdEx      = new ThreadSafeCounter("WrEx_PessiRdEx", false, stats);
  static final ThreadSafeCounter RdSh_PessiWrEx      = new ThreadSafeCounter("RdSh_PessiWrEx", false, stats);
  static final ThreadSafeCounter RdEx_PessiWrEx      = new ThreadSafeCounter("RdEx_PessiWrEx", false, stats);
  
  static final ThreadSafeCounter PessiWrEx_WrEx      = new ThreadSafeCounter("PessiWrEx_WrEx", false, stats);
  static final ThreadSafeCounter PessiRdEx_WrEx      = new ThreadSafeCounter("PessiRdEx_WrEx", false, stats);
  static final ThreadSafeCounter PessiRdEx_RdEx      = new ThreadSafeCounter("PessiRdEx_RdEx", false, stats);
  static final ThreadSafeCounter PessiRdSh_RdEx      = new ThreadSafeCounter("PessiRdSh_RdEx", false, stats);
  
  static final ThreadSafeCounter PessiWrEx_PessiWrEx_Same   = new ThreadSafeCounter("PessiWrEx_PessiWrEx_Same", false, stats);
  static final ThreadSafeCounter PessiWrEx_PessiWrEx_Diff   = new ThreadSafeCounter("PessiWrEx_PessiWrEx_Diff", false, stats);
  static final ThreadSafeCounter PessiWrEx_PessiRdEx_Diff   = new ThreadSafeCounter("PessiWrEx_PessiRdEx_Diff", false, stats);
  static final ThreadSafeCounter PessiRdEx_PessiWrEx_Same   = new ThreadSafeCounter("PessiRdEx_PessiWrEx_Same", false, stats);
  static final ThreadSafeCounter PessiRdEx_PessiWrEx_Diff   = new ThreadSafeCounter("PessiRdEx_PessiWrEx_Diff", false, stats);
  static final ThreadSafeCounter PessiRdEx_PessiRdEx_Same   = new ThreadSafeCounter("PessiRdEx_PessiRdEx_Same", false, stats);
  static final ThreadSafeCounter PessiRdEx_PessiRdSh   = new ThreadSafeCounter("PessiRdEx_PessiRdSh", false, stats);
  static final ThreadSafeCounter PessiRdSh_PessiWrEx   = new ThreadSafeCounter("PessiRdSh_PessiWrEx", false, stats);
  static final ThreadSafeCounter PessiRdSh_PessiRdSh_NoFence = new ThreadSafeCounter("PessiRdSh_PessiRdSh_NoFence", false, stats);
  static final ThreadSafeCounter PessiRdSh_PessiRdSh_Fence   = new ThreadSafeCounter("PessiRdSh_PessiRdSh_Fence", false, stats);
  // Man: This counter is only valid if pessimisticNoRdSh() is enabled 
  static final ThreadSafeCounter PessiRdEx_PessiRdEx_Diff   = new ThreadSafeCounter("PessiRdEx_PessiRdEx_Diff", false, stats);

  static final ThreadSafeSumCounter sameState      = new ThreadSafeSumCounter("sameState", stats, Uninit_Init, WrEx_WrEx_Same, RdEx_RdEx_Same, RdSh_RdSh_NoFence);
  static final ThreadSafeSumCounter upgrading      = new ThreadSafeSumCounter("upgrading", stats, RdEx_WrEx_Same, RdEx_RdSh, RdSh_RdSh_Fence);
  static final ThreadSafeSumCounter conflicting    = new ThreadSafeSumCounter("conflicting", stats, WrEx_WrEx_Diff, WrEx_RdEx_Diff, RdEx_WrEx_Diff, RdSh_WrEx, RdEx_RdEx_Diff);
  static final ThreadSafeSumCounter confToPessi    = new ThreadSafeSumCounter("confToPessi", stats, WrEx_PessiWrEx, WrEx_PessiRdEx, RdSh_PessiWrEx, RdEx_PessiWrEx);
  static final ThreadSafeSumCounter pessiToOpti    = new ThreadSafeSumCounter("pessiToOpti", stats, PessiWrEx_WrEx, PessiRdEx_WrEx, PessiRdEx_RdEx, PessiRdSh_RdEx);
  static final ThreadSafeSumCounter pessimistic    = new ThreadSafeSumCounter("pessimistic", stats, PessiWrEx_PessiWrEx_Same, PessiWrEx_PessiWrEx_Diff, PessiWrEx_PessiRdEx_Diff, 
      PessiRdEx_PessiWrEx_Same, PessiRdEx_PessiWrEx_Diff, PessiRdEx_PessiRdEx_Same, PessiRdEx_PessiRdSh, PessiRdSh_PessiWrEx, PessiRdSh_PessiRdSh_NoFence, PessiRdSh_PessiRdSh_Fence,
      PessiRdEx_PessiRdEx_Diff);
  static final ThreadSafeSumCounter total          = new ThreadSafeSumCounter("total", stats, sameState, upgrading, conflicting, confToPessi, pessiToOpti, pessimistic);
  
  // ratio counters
  static final ThreadSafeRatioCounter Alloc_Ratio             = new ThreadSafeRatioCounter("Alloc_Ratio", Alloc, total, stats);
  static final ThreadSafeRatioCounter Uninit_Init_Ratio       = new ThreadSafeRatioCounter("Uninit_Init_Ratio", Uninit_Init, total, stats);
  static final ThreadSafeRatioCounter WrEx_WrEx_Same_Ratio    = new ThreadSafeRatioCounter("WrEx_WrEx_Same_Ratio", WrEx_WrEx_Same, total, stats);
  static final ThreadSafeRatioCounter WrEx_WrEx_Diff_Ratio    = new ThreadSafeRatioCounter("WrEx_WrEx_Diff_Ratio", WrEx_WrEx_Diff, total, stats);
  static final ThreadSafeRatioCounter WrEx_RdEx_Ratio         = new ThreadSafeRatioCounter("WrEx_RdEx_Ratio", WrEx_RdEx_Diff, total, stats);
  static final ThreadSafeRatioCounter RdEx_WrEx_Same_Ratio    = new ThreadSafeRatioCounter("RdEx_WrEx_Same_Ratio", RdEx_WrEx_Same, total, stats);
  static final ThreadSafeRatioCounter RdEx_WrEx_Diff_Ratio    = new ThreadSafeRatioCounter("RdEx_WrEx_Diff_Ratio", RdEx_WrEx_Diff, total, stats);
  static final ThreadSafeRatioCounter RdEx_RdEx_Ratio         = new ThreadSafeRatioCounter("RdEx_RdEx_Ratio", RdEx_RdEx_Same, total, stats);
  static final ThreadSafeRatioCounter RdEx_RdSh_Ratio         = new ThreadSafeRatioCounter("RdEx_RdSh_Ratio", RdEx_RdSh, total, stats);
  static final ThreadSafeRatioCounter RdSh_WrEx_Ratio         = new ThreadSafeRatioCounter("RdSh_WrEx_Ratio", RdSh_WrEx, total, stats);
  static final ThreadSafeRatioCounter RdSh_RdSh_NoFence_Ratio = new ThreadSafeRatioCounter("RdSh_RdSh_NoFence_Ratio", RdSh_RdSh_NoFence, total, stats);
  static final ThreadSafeRatioCounter RdSh_RdSh_Fence_Ratio   = new ThreadSafeRatioCounter("RdSh_RdSh_Fence_Ratio", RdSh_RdSh_Fence, total, stats);

  static final ThreadSafeRatioCounter sameState_Ratio      = new ThreadSafeRatioCounter("sameState_Ratio", sameState, total, stats);
  static final ThreadSafeRatioCounter upgrading_Ratio      = new ThreadSafeRatioCounter("upgrading_Ratio", upgrading, total, stats);
  static final ThreadSafeRatioCounter conflicting_Ratio    = new ThreadSafeRatioCounter("conflicting_Ratio", conflicting, total, stats);
  static final ThreadSafeRatioCounter confToPessi_Ratio    = new ThreadSafeRatioCounter("confToPessi_Ratio", confToPessi, total, stats);
  static final ThreadSafeRatioCounter pessiToOpti_Ratio    = new ThreadSafeRatioCounter("pessiToOpti_Ratio", pessiToOpti, total, stats);
  static final ThreadSafeRatioCounter pessimistic_Ratio    = new ThreadSafeRatioCounter("pessimistic_Ratio", pessimistic, total, stats);
  static final ThreadSafeRatioCounter total_Ratio          = new ThreadSafeRatioCounter("total_Ratio", total, total, stats); // should report 1.00

  // Counting threads
  public static final SpecialUnsyncCounter threadsLive = new SpecialUnsyncCounter("threadsLive", stats);
  
  // stats for RBA
  public static final ThreadSafeCounter sharedAccesses = new ThreadSafeCounter("Shared_Accesses", true, stats); 
  public static final ThreadSafeCounter redundantBarriers = new ThreadSafeCounter("Redundant_Barriers", true, stats);
  public static final UnsyncHistogram optimisticRbaStaticLoopSize = new UnsyncHistogram("optimisticRbaStaticLoopSize", true, 1L<<32, stats);
  
  static {
    if (stats) {
      Callbacks.addExitMonitor(new ExitMonitor() {
        //@Override
        public void notifyExit(int value) {

          System.out.println("BEGIN .gv");
          System.out.println("  Null -> WrEx [ style=dotted label = \"" + pct(Alloc.total() + Uninit_Init.total(), total.total()) + "%\" ]");
          System.out.println("  WrEx -> WrEx [ style=dotted label = \"" + pct(WrEx_WrEx_Same.total(), total.total()) + "%\" ]");
          System.out.println("  WrEx -> WrEx [ style=solid  label = \"" + pct(WrEx_WrEx_Diff.total(), total.total()) + "%\" headport=sw ]");
          System.out.println("  WrEx -> RdEx [ style=solid  label = \"" + pct(WrEx_RdEx_Diff.total(), total.total()) + "%\" ]");
          System.out.println("  RdEx -> WrEx [ style=dashed label = \"" + pct(RdEx_WrEx_Same.total(), total.total()) + "%\" ]");
          System.out.println("  RdEx -> WrEx [ style=solid  label = \"" + pct(RdEx_WrEx_Diff.total(), total.total()) + "%\" ]");
          System.out.println("  RdEx -> RdEx [ style=dotted label = \"" + pct(RdEx_RdEx_Same.total(), total.total()) + "%\" ]");
          System.out.println("  RdEx -> RdSh [ style=dashed label = \"" + pct(RdEx_RdSh.total(), total.total()) + "%\" ]");
          System.out.println("  RdSh -> WrEx [ style=solid  label = \"" + pct(RdSh_WrEx.total(), total.total()) + "%\" ]");
          System.out.println("  RdSh -> RdSh [ style=dotted label = \"" + pct(RdSh_RdSh_NoFence.total(), total.total()) + "%\" ]");
          System.out.println("  RdSh -> RdSh [ style=dashed label = \"" + pct(RdSh_RdSh_Fence.total(), total.total()) + "%\" ]");
          System.out.println();
          System.out.println("Null1 -> Null2 [style=dotted label=\"Same state: "  + commas(sameState.total()) + "\" ]");
          System.out.println("Null3 -> Null4 [style=dashed label=\"Upgrading: "   + commas(upgrading.total()) + "\" ]");
          System.out.println("Null5 -> Null6 [style=solid  label=\"Conflicting: " + commas(conflicting.total()) + "\" ]");
          System.out.println("END .gv");

          System.out.println();
          
          System.out.println("BEGIN tabular");
          System.out.println(commas(sameState.total()) + " & " + commas(upgrading.total()) + " & " + commas(conflicting.total()));
          
          System.out.print(pct(Alloc.total() + Uninit_Init.total(), total.total()));
          for (ThreadSafeCounter counter : new ThreadSafeCounter[] { WrEx_WrEx_Same, RdEx_RdEx_Same, RdSh_RdSh_NoFence,
                                                                     RdEx_WrEx_Same, RdEx_RdSh, RdSh_RdSh_Fence,
                                                                     WrEx_WrEx_Diff, WrEx_RdEx_Diff, RdEx_WrEx_Diff, RdSh_WrEx } ) {
            System.out.print(" & " + pct(counter.total(), total.total()));
          }
          System.out.println(" \\");
          System.out.println("END tabular");

        }
      });
    }
  }
  
  @Interruptible
  static String pct(long x, long total) {
    double fraction = (double)x / total;
    double pct = fraction * 100;
    return new DecimalFormat("0.0000000000000").format(pct); 
  }
  
  @Interruptible
  public static String commas(long n) {
    if (n < 0) {
      return "-" + commas(-n);
    } else if (n < 1000) {
      return String.valueOf(n);
    } else {
      return commas(n / 1000) + "," + String.valueOf((n % 1000) + 1000).substring(1);
    }
  }
  
  @Interruptible
  public static String commas(ThreadSafeCounter counter) {
    return commas(counter.total());
  }
  
  // measuring how often and where communication gets blocked:
  // Octet: LATER: consider other ways we might avoid blocking sometimes, in favor of checking (e.g., if code could be in a loop for a while but won't actually block)
  public static final ThreadSafeCounter blockCommEntrypoint = new ThreadSafeCounter("blockCommEntrypoint", false, stats);
  public static final ThreadSafeCounter blockCommReceiveResponses = new ThreadSafeCounter("blockCommReceiveResponses", false, stats);
  public static final ThreadSafeCounter blockCommBeginPairHandshake = new ThreadSafeCounter("blockCommBeginPairHandshake", false, stats);
  public static final ThreadSafeCounter blockCommThreadBlock = new ThreadSafeCounter("blockCommThreadBlock", false, stats);
  public static final ThreadSafeCounter blockCommEnterJNIFromCallIntoNative = new ThreadSafeCounter("blockCommEnterJNIFromCallIntoNative", false, stats);
  public static final ThreadSafeCounter blockCommEnterJNIFromJNIFunctionCall = new ThreadSafeCounter("blockCommEnterJNIFromJNIFunctionCall", false, stats);
  public static final ThreadSafeCounter blockCommEnterNative = new ThreadSafeCounter("blockCommEnterNative", false, stats);
  public static final ThreadSafeCounter blockCommTerminate = new ThreadSafeCounter("blockCommTerminate", false, stats);
  public static final ThreadSafeCounter blockCommYieldpoint = new ThreadSafeCounter("blockCommYieldpoint", false, stats);
  public static final ThreadSafeCounter blockCommHoldsLock = new ThreadSafeCounter("blockCommHoldsLock", false, stats);
  public static final ThreadSafeCounter blockCommLock = new ThreadSafeCounter("blockCommLock", false, stats);
  
  static {
    Callbacks.addExitMonitor(new ExitMonitor() {
      //@Override
      public void notifyExit(int value) {
        for (Stat stat : all) {
          stat.report();
        }
        if (measureTime) {
          // hard-coded computing and reporting of average time
          computeAvgTimeAndReport(totalTimeConflTransitions, countConflTransitions, "avgTimeConflTransitions");
          computeAvgTimeAndReport(totalTimePessiTransitions, countPessiTransitions, "avgTimePessiTransition");
          computeAvgTimeAndReport(totalTimeFastpath, countFastpath, "avgTimeFastpath");
          computeAvgTimeAndReport(totalTimeExplicitProtocol, countExplicitProtocol, "avgTimeExplicitProtocol");
          computeAvgTimeAndReport(totalTimeImplicitProtocol, countImplicitProtocol, "avgTimeImplicitProtocol");
          computeAvgTimeAndReport(totalTimeRdShToWrEx, countRdShToWrEx, "avgTimeRdShToWrEx");
        }
      }
    });
  }
  
  @Uninterruptible
  // FIB: made public to construct my own stats.
  public static abstract class Stat {
    public static final String SEPARATOR = ": ";
    public static final String LINE_PREFIX = "STATS" + SEPARATOR;
    // FIB: made protected
    protected final String name;
    /**
     * Determines if stats that we want to collect with this object is compile
     * time stats or runtime stats.
     */
    protected final boolean staticStat;
    protected final boolean enabled;

    // FIB: made public
    public Stat(String name, boolean runtimeStats, boolean enabled) {
      // if counter names contain spaces in their names, EXP cannot parse the results.
      if (VM.VerifyAssertions) {
        VM._assert(name == null || !name.contains(" "), "Counter name contains space character in it.");
      }
      this.name = name;
      this.staticStat = runtimeStats;
      this.enabled = enabled;
      if (enabled) {
          if (name != null && name.contains("Fib")) { // Fib hack to turn off Octet stat printing for now.
          synchronized (all) {
            all.add(this);
          }
        }
      }
    }
    @Interruptible
    abstract void report();
    
    @Interruptible
    protected String outputLinePrefix() {
      return LINE_PREFIX + this.getClass().getName() + SEPARATOR;
    }
  }
  
  public static final class PerSiteCounter extends Stat {
    final UnsyncCounter[] counters;
    public PerSiteCounter(String name, boolean enabled) {
      super(name, true, enabled);
      if (enabled) {
        counters = new UnsyncCounter[Site.MAX_SITES];
        for (int i= 0; i < Site.MAX_SITES; i++) {
          counters[i] = new UnsyncCounter(null, enabled);
        }
      } else {
        counters = null;
      }
    }
    @Uninterruptible
    @Inline
    public void inc(int siteID) {
      if (enabled) {
        counters[siteID].inc();
      }
    }
    @Interruptible
    @Override
    public void report() {
      Integer[] siteIDs = new Integer[Site.MAX_SITES];
      for (int i = 0; i < Site.MAX_SITES; i++) {
        siteIDs[i] = i;
      }
      Arrays.sort(siteIDs, new Comparator<Integer>() {
        public int compare(Integer siteID1, Integer siteID2) {
          long total1 = counters[siteID1].getValue();
          long total2 = counters[siteID2].getValue();
          if (total1 > total2) {
            return -1;
          } else if (total1 < total2) {
            return 1;
          } else {
            return 0;
          }
        }
      });
      long grandTotal = 0;
      for (UnsyncCounter counter : counters) {
        grandTotal += counter.getValue();
      }
      System.out.print(outputLinePrefix());
      System.out.println(name + " = " + grandTotal);
      for (int i = 0; i < 25; i++) {
        int siteID = siteIDs[i];
        System.out.print(outputLinePrefix());
        System.out.println("  " + Site.lookupSite(siteID) + " = " + counters[siteID].getValue());
      }
    }
  }
  
  @Uninterruptible
  public static class UnsyncCounter extends Stat {
    protected long value;
    // FIB: made public
    public UnsyncCounter(String name, boolean enabled) {
      super(name, true, enabled);
    }
    @Inline
    public void inc() {
      if (enabled) {
        value++;
      }
    }
    @Interruptible
    @Override
    // FIB: made protected
    protected void report() {
      System.out.print(outputLinePrefix());
      System.out.println(name + " = " + value);
    }
    final long getValue() {
      return value;
    }
  }
  
  @Uninterruptible
  public static class SpecialUnsyncCounter extends UnsyncCounter {
    private long max;
    private long numIncs;
    private long numDecs;
    SpecialUnsyncCounter(String name, boolean enabled) {
      super(name, enabled);
    }
    @Override
    @Inline
    public void inc() {
      if (enabled) {
        super.inc();
        numIncs++;
        max = value > max ? value : max;
      }
    }
    @Inline
    public void dec() {
      if (enabled) {
        value--;
        numDecs++;
      }
    }
    @Interruptible
    @Override
    // FIB: made protected
    protected final void report() {
      System.out.print(outputLinePrefix());
      System.out.println(name + " = " + value + " (max " + max + ", numIncs " + numIncs + ", numDecs " + numDecs + ")");
    }
  }

  @Uninterruptible
  public static abstract class CounterWithTotal extends Stat {
    // FIB: made public
    public CounterWithTotal(String name, boolean staticStat, boolean enabled) {
      super(name, staticStat, enabled);
    }
    
    // FIB: made protected
    protected abstract long total();
    
    @Interruptible
    @Override
    final void report() {
      System.out.print(outputLinePrefix());
      System.out.println(name + " = " + total());
    }
  }
  
  /** 
   * This is a cache-friendly version of the old ThreadSafeCounter. It avoids the false-sharing issue,
   * where different threads could increment their own slots of the same value array inside the same counter.
   * It saves the values in a lazily allocated matrix, where each row contains all the counters for one single threadSlot, indexed by statID;
   * The summation of one single column is the actual value for the counter of that statID.
   * Rows are only allocated when a RVMThread is created, and old row get reused if the threadSlot is reused.
   * 
   * Instance of this counter must be created as a static field of Stats class. All methods are final in this class.
   * If you are trying to create other instances outside Stats class, move the call to setNumOfIDs() to a new appropriate location.
   * 
   * @author Man
   * 
   * */
  @Uninterruptible
  public static final class ThreadSafeCounter extends CounterWithTotal {
    private static int nextID = 0;
    
    private static final int numOfIDs = 256;
    
    private static final long[][] values; // values[threadSlot][statID]
    private static final boolean[] valuesAllocated; // valuesInitialized[threadSlot]
    
    private static final long[] totals; // totals[statID], this should actually be "final".
    private static boolean totalsComputed = false;
    
    private final int statID;
    
    static {
      if (needThreadSafeCounter) {
        values = new long[RVMThread.MAX_THREADS][];
        valuesAllocated = new boolean[RVMThread.MAX_THREADS];
        for (int i = 1; i < valuesAllocated.length; i++) {
          valuesAllocated[i] = false;
        }
        values[0] = new long[numOfIDs];
        valuesAllocated[0] = true;
        totals = new long[numOfIDs];
      } else {
        values = null;
        valuesAllocated = null;
        totals = null;
      }
    }
    
    public ThreadSafeCounter(String name, boolean staticStats, boolean enabled) {
      super(name, staticStats, enabled);
      if (enabled) {
        this.statID = nextID;
        if (nextID >= numOfIDs) {
          VM.sysFail("Cannot create new counter, because total number of ThreadSafeCounter objects has reached maximum (256)!");
        }
        nextID++;
      } else {
        this.statID = -1;
      }
    }
    
    @Inline
    @Interruptible
    public static void allocateForThreadSlot(int slot) {
      if (needThreadSafeCounter) {
        if (!valuesAllocated[slot]) {
          values[slot] = new long[numOfIDs];
          valuesAllocated[slot] = true;
        }
      }
    }
    
    @Inline
    public void inc() {
      this.inc(1);
    }
    
    @Inline
    public void inc(long n) {
      if (enabled) {
        if (VM.runningVM) {
          // do not increment dynamic stats if we are not in Harness
          if (staticStat || (!staticStat && MemoryManager.inHarness())) {
            int slot = RVMThread.getCurrentThreadSlot();
            if (VM.VerifyAssertions) {
              VM._assert(valuesAllocated[slot]);
              VM._assert(this.statID >= 0 && this.statID < numOfIDs);
            }
            values[slot][this.statID] += n;
          }
        } else if (staticStat){
          incDuringBootImageBuild(n);
        }
      }
    }
    @NoInline
    @UninterruptibleNoWarn // Needed because of synchronization, but it's okay because we know the VM won't be running when it calls this method.
    private void incDuringBootImageBuild(long n) {
      if (VM.VerifyAssertions) { VM._assert(!VM.runningVM); }
      if (VM.VerifyAssertions) {
        VM._assert(valuesAllocated[0]);
        VM._assert(this.statID >= 0 && this.statID < numOfIDs);
      }
      // Synchronize in case multiple compiler threads are building the boot image (I think that happens).
      synchronized (this) {
        // Note that thread slot 0 isn't actually used by any thread,
        // so we can just use this slot to represent all increments during the boot image build.
        values[0][this.statID] += n;
      }
    }

    @Uninterruptible
    @Override
    // FIB: made protected
    protected long total() {
      // if not computed yet, compute it
      if (!totalsComputed) {
        updateTotal();
      }
      return totals[this.statID];
    }

    @Uninterruptible
    private static void updateTotal() {
      // Man: we should update the total for every counter at once, for performance reasons.
      // This should be done only once, at the end of program execution. 
      for (int j = 0; j < values.length; j++) { // j is threadSlot
        if (valuesAllocated[j]) {
          for (int i = 0; i < nextID; i++){ // i is statID
            totals[i] += values[j][i];
          }
        }
      }
      totalsComputed = true;
    }
  }
  
  /**
   * 
   * This counter can be used for collecting stats that can be computed by
   * adding several other stats For example number of total accesses can be
   * computed by adding total number of reads with total number of writes.
   * If we already have a counter for reads and another counter for writes,
   * then we can use them to construct a new counter using this class.
   * 
   * @author Meisam
   * @author Man
   * 
   */
  @Uninterruptible
  public static final class ThreadSafeSumCounter extends CounterWithTotal {

    private final CounterWithTotal[] counters;
    private long total = -1;

    // FIB: made public
    public ThreadSafeSumCounter(String name, boolean enabled, CounterWithTotal ... counters) {
      super(name, false, enabled); // A sum counter is not really a runtime counter
      this.counters = counters;
    }

    @Uninterruptible
    private void updateTotal() {
      total = 0;
      for (int i = 0; i < counters.length; i++) {
        total += counters[i].total();
      }
    }
    
    @Uninterruptible
    @Override
    // FIB: made protected
    protected long total() {
      // if not computed yet, compute it
      if (total == -1) {
        updateTotal();
      }
      return total;
    }
  }
  /**
   * 
   * This counter can be used for collecting ratio of two stats, 
   * for example the ratio of reads to writes.
   * number of total accesses can be
   * computed by adding total number of reads with total number of writes. If we
   * already have a counter for reads and another counter for writes, then we
   * can use them to construct a new counter using this class.
   * 
   * @author Meisam
   * 
   */
  @Uninterruptible
  public static final class ThreadSafeRatioCounter extends Stat {

    private CounterWithTotal numeratorCounter;
    private CounterWithTotal denumeratorCounter;

    // FIB: made public
    public ThreadSafeRatioCounter(String name, CounterWithTotal numerator, CounterWithTotal denumerator, boolean enabled) {
      super(name, true, enabled);
      this.numeratorCounter = numerator;
      this.denumeratorCounter = denumerator;
    }

    @Override
    @Interruptible
    // FIB: made protected
    protected void report() {
      System.out.print(outputLinePrefix());
      System.out.println(name + " = " + ratio());
    }

    @Uninterruptible
    private double ratio() {
      return (100.0 * numeratorCounter.total()) / denumeratorCounter.total();
    }

  }

  @Uninterruptible
  public static final class UnsyncHistogram extends Stat {
    final boolean log2;
    private final int[] data;
    // FIB: made public
    public UnsyncHistogram(String name, boolean log2, long maxX, boolean enabled) {
      super(name, true, enabled);
      this.log2 = log2;
      this.data = enabled ? new int[log2 ? log2(maxX) + 1 : (int)maxX + 1] : null;
    }
    @Inline
    public final void incBin(long x) {
      if (enabled) {
        if (log2) {
          data[log2(x) + 1]++;
        } else {
          data[(int)x]++;
        }
      }
    }
    @Inline
    public final void reBin(long x, long y) {
      if (enabled) {
        int lx = log2(x);
        int ly = log2(y);
        if (lx != ly) {
          if (log2) {
            data[lx + 1]--;
            data[lx + 1]++;
          } else {
            data[(int)x]--;
            data[(int)y]++;
          }
        }
      }
    }
    @Inline
    public final void incTime(long startTime) {
      if (enabled) {
        incBin(nsElapsed(startTime));
      }
    }
    @Inline
    public final long total() {
      int i;
      long sum = 0;
      for (i = 0; i < data.length; i++) {
        sum += data[i];
      }
      return sum;
    }
    @Inline
    public final double arithmeticMean() { //Weighted arithmetic mean. Overestimated.
      int lastNonzeroIndex;
      for (lastNonzeroIndex = data.length - 1; lastNonzeroIndex >= 0 && data[lastNonzeroIndex] == 0; lastNonzeroIndex--) { }
      double sum = 0;
      int weight = 0;
      if (lastNonzeroIndex > 0) {
        for (int x = 0; x <= lastNonzeroIndex; x++) {
          sum += data[x] * ((1 << (x + 1)) -1);
          weight += ((1 << (x + 1)) -1);
        }
        return sum / weight;
      }
      return 0;
    }
    @Interruptible
    @Override
    // FIB: made protected
    protected final void report() {
      int lastNonzeroIndex; // don't print any tail of all 0s
      for (lastNonzeroIndex = data.length - 1; lastNonzeroIndex >= 1 && data[lastNonzeroIndex] == 0; lastNonzeroIndex--) { }
      
      for (int x = 0; x <= lastNonzeroIndex; x++) {
        System.out.print(outputLinePrefix());
        System.out.print(name + "[" + (log2 ? x - 1 : x) + "] = " + data[x]);
        if (log2) {
          // System.out.print(" (" + (data[x] << x) + ")");
          // Man: I think data[x] is the actual value of the counter, even if it is log-based.
          // data[x]<<x doesn't make sense.
          if (x == 0) {
            System.out.printf(" (range: (-inf, 0])");
          } else {
            System.out.printf(" (range: [%d, %d))", 1 << (x - 1), 1<<(x));
          }
        }
        System.out.println();
      }
    }
    static final int log2(long n) {
      if (n <= 0) {
        return -1;
      } else if (n == 1) {
        return 0;
      } else {
        return 1 + log2(n / 2);
      }
    }
  }

  /**
   * Currently this histogram is only used for per object/field counters.
   * A histogram slot only increments when the parameter is power of 2,
   * and decrements the preceding slot when a slot increments.
   * 
   * @author Man
  */
  @Uninterruptible
  public static final class ThreadSafeLog2Histogram extends Stat{
    private final int[][] data;
    protected final int [] total;

    ThreadSafeLog2Histogram(String name, boolean staticStats, int maxX, boolean enabled) {
      super(name, staticStats, enabled);
      int log = binlog(maxX);
      data = enabled ? new int[RVMThread.MAX_THREADS][] : null;
      total = enabled ? new int[log] : null;
      if (enabled) {
        for (int i = 0; i < data.length; i++) {
          data[i] = new int[log];
        }
      }
    }

    @Inline
    public void incBin(int x) {
      if (enabled) {
        if (!isPowerOfTwo(x)) {
          return;
        }
        int log = binlog(x);
        if (VM.runningVM) {
          // do not increment dynamic stats if we are not in Harness
          if (staticStat || (!staticStat && MemoryManager.inHarness())) {
            int slot = RVMThread.getCurrentThreadSlot();
            data[slot][log]++;
            if (log>0) {
              data[slot][log-1]--;
            }
          }
        } else if (staticStat){
          incDuringBootImageBuild(log);
        }
      }
    }
    @NoInline
    @UninterruptibleNoWarn // Needed because of synchronization, but it's okay because we know the VM won't be running when it calls this method.
    private void incDuringBootImageBuild(int log) {
      if (VM.VerifyAssertions) { VM._assert(!VM.runningVM); }
      // Synchronize in case multiple compiler threads are building the boot image (I think that happens).
      synchronized (this) {
        // Note that thread slot 0 isn't actually used by any thread,
        // so we can just use this slot to represent all increments during the boot image build.
        data[0][log]++;
        if (log>0) {
          data[0][log-1]--;
        }
      }
    }
    @Interruptible
    @Override
    final void report() {
      updateTotal();
      int sum=0;
      int lastNonzeroIndex; // don't print any tail of all 0s
      for (lastNonzeroIndex = total.length - 1; lastNonzeroIndex >= 1 && total[lastNonzeroIndex] == 0; lastNonzeroIndex--) {}
      for (int x = 0; x <= lastNonzeroIndex; x++) {
        System.out.print(outputLinePrefix());
        System.out.print(name + "[" + x + "] = " + total[x]);
        System.out.printf(" (range: [%d, %d))", 1 << x, 1<<(x+1));
        System.out.println();
        sum += total[x];
      }
      System.out.print(outputLinePrefix());
      System.out.println(name + " = " + sum);
    }
    
    @Uninterruptible
    protected void updateTotal() {
      for (int[] value : data) {
        for (int i = 0; i<total.length; i++){
          total[i] += value[i];
        }
      }
    }
  }
  
  static final long nsElapsed(long startTime) {
    return (long)(Time.nanoTime() - startTime);
  }

  @UninterruptibleNoWarn
  public static final void tryToPrintStack(Address framePointer) {
    try {
      RVMThread.dumpStack(framePointer);
    } catch (Exception ex) { /* do nothing */ }
  }
  
  @Inline
  public static final boolean isPowerOfTwo(long x){
      return (x & (x - 1)) == 0; // actually it should be (x != 0) && ((x & (x - 1)) == 0), to consider the case of zero
  }
  
  // http://stackoverflow.com/questions/3305059/how-do-you-calculate-log-base-2-in-java-for-integers
  // it actually treats bits as an unsigned int
  public static final int binlog(int bits){ // returns 0 for bits=0
      int log = 0;
      if( ( bits & 0xffff0000 ) != 0 ) { bits >>>= 16; log = 16; }
      if( bits >= 256 ) { bits >>>= 8; log += 8; }
      if( bits >= 16  ) { bits >>>= 4; log += 4; }
      if( bits >= 4   ) { bits >>>= 2; log += 2; }
      return log + ( bits >>> 1 );
  }

  @Interruptible
  public static final long computeAvgTimeAndReport(ThreadSafeCounter time, ThreadSafeCounter count, String name){
    long avg = 0;
    long totalCount = count.total();
    long totalCycle = time.total();
    if (totalCount > 0) {
      avg = totalCycle/totalCount;
      System.out.print(totalTimeConflTransitions.outputLinePrefix());
      System.out.println(name + " = " + avg);
    }
    return avg;
  }
}
