package org.jikesrvm.config.dr;

import org.jikesrvm.compilers.opt.RedundantBarrierRemover;
import org.jikesrvm.dr.fasttrack.EmptyFastTrack;
import org.jikesrvm.dr.fasttrack.FastTrack;
import org.jikesrvm.dr.instrument.EmptyAnalysis;
import org.jikesrvm.dr.metadata.maps.EmptyEpochMapper;
import org.jikesrvm.dr.metadata.maps.EpochMapper;
import org.jikesrvm.octet.ClientAnalysis;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public abstract class Base extends org.jikesrvm.config.BaseConfig {
  
  /**
   * FastTrack access-checking implementation for instance fields and array elements.
   * @return
   */
  @Interruptible
  @Pure
  public FastTrack newFastTrack() {
    return new EmptyFastTrack();
  }
  
  /**
   * FastTrack access-checking implementation for static fields.
   * @return
   */
  @Interruptible
  @Pure
  public FastTrack newStaticFastTrack() {
    return this.newFastTrack();
  }
  
  /**
   * Last Readers implementation.
   * @return
   */
  @Interruptible
  @Pure
  public EpochMapper newEpochMapper() {
    return new EmptyEpochMapper();
  }
  
  /**
   * Is synchronization tracking enabled?
   * @return
   */
  @Pure
  public boolean syncTracking() {
    return false;
  }
  
  /**
   * Should "unused" epochs be recycled?
   * @return
   */
  @Pure
  public boolean collapseEpochs() {
    return false;
  }
  
  /**
   * How many epoch bits should be used as a tag?  Must be 1 or 2 (unless shared implementation is changed).
   * @return
   */
  @Pure
  public int epochTagBits() {
    return 1;
  }
  
  /**
   * How many epoch bits should be used for thread ID? 0 < epochTidBits() < BITS_IN_WORD - epochTagBits()
   * @return
   */
  @Pure
  public int epochTidBits() {
    return 5;
  }
  
  @Pure
  public boolean strideMapCompaction() {
    return true;
  }

  /**
   * Are FIB communication blocking/unblocking hooks needed?
   * @return
   */
  @Pure
  public boolean fibCommunication() {
    return false;
  }
  
//  /**
//   * Should the race detector try to "patch up" metadata to keep it well-formed after a race?
//   * 
//   * OBSOLETED by new "freeze on first race" policy.
//   * @return
//   */
//  @Pure
//  public boolean fibPatchRaces() {
//    return true;
//  }
  
  public static enum InlineLevel {
    NONE, THIN, THICK
  }
  /**
   * Inline race-check barriers?
   */
  @Pure
  public InlineLevel drInlineRaceChecks() {
    return InlineLevel.THICK;
  }
  
  /**
   * Use dynamic escape analysis as a filter?
   */
  @Pure
  public boolean fibCheckEscape() {
    return escapeInsertBarriers();
  }
  
  /**
   * Check for escape early (before any FastTrack metadata)?
   * Array instrumentation always checks early (if escape enabled)
   * regardless of this setting (to simplify lazy array shadow creation, etc.)
   */
  @Pure
  public boolean fibCheckEscapeEarly() {
    return true;
  }
  
  
  /**
   * Filter synchronization instrumentation with escape as well?
   * 
   * When true, synchronization instrumentation will only be
   * executed if the object where the synchronization device lives
   * (i.e. the object locked or the volatile field) is escaped.
   * 
   * This is sound and complete through the first race.  A filtered
   * synchronization may cause false races to appear (since we report
   * less sync than actually occurred), but only AFTER an earlier race
   * on publication.  (Proof by roughly the same reasoning as for how
   * escape preserves soundness and completeness up to first race when
   * used as a filter for access checks.)
   * 
   * Thread fork and join are always instrumented!
   */
  @Pure
  public boolean fibCheckEscapeForSync() {
    return fibCheckEscape();
  }
  
  /**
   * What is the policy for initial post-escape ownership?
   */
  @Pure
  public int fibEscapePolicy() {
    return EscapePolicy.CAS;
  }
  
  public static class EscapePolicy {
    /** First post-escape access must CAS to take ownership. */
    public static final int CAS = 0;
    /** Owner epoch is published in header upon escape. */
    public static final int HEADER = 1;
    /** Owner epoch is published in all read words upon escape. */
    public static final int READ_WORD = 2;
  }
  
  @Pure
  public boolean fibBuffer() {
    return false;
  }
  
  @Pure
  public int fibThresholdBits() {
    return 3;
  }
  
  @Pure
  public int drExtraWordsInHistory() {
    return 0;
  }
  
  @Pure
  public boolean drExtraHeaderWord() {
    return false;
  }
  
  @Pure
  public boolean fibHeavyTransfers() {
    return false;
  }
  
  @Pure
  public boolean fibPreemptiveReadShare() {
    return false;
  }
  
  @Pure
  public boolean fibStickyReadShare() {
    return false;
  }
  
  @Pure
  public boolean fibStaticReadShare() {
    return false;
  }
  
  @Pure
  public boolean fibAdaptiveCas() {
    return false;
  }
  
  @Pure
  public boolean drFirstRacePerLocation() {
    return true;
  }
  
  @Pure
  public boolean drCasFineGrained() {
    return true;
  }
  
  /**
   * What should be done when a race is detected?
  public static enum RaceBehavior {
    HALT,       // Exit the JVM.
    EXCEPTION,  // Throw an exception in the detecting thread.
    DENIAL,     // Continue (including metadata updates) as if there was no race.
                // Breaks accuracy, likely to break FIB protocol, cause deadlocks,
                // crashes, etc.
    PATCH,      // Try to patch up FIB protocol to avoid deadlocks, crashes.
                // Breaks accuracy.
    SKIP_ALL,   // Disable all race detection for rest of execution.
                // Breaks accuracy.
    SKIP_LOC,   // Disable race detection for this location for rest of execution.
                // Breaks accuracy.
    SKIP_ACC,   // Do not record this access.
                // Breaks accuracy, may break FIB protocol.
  }
  
  @Pure
  public RaceBehavior raceBehavior() {
    return RaceBehavior.DENIAL;
  }
  */
  
  
  /** Insert read and write barriers? */
  @Override
  @Pure public boolean insertBarriers() { return false; }
  
  /** Insert the Java libraries in addition to the application? */
  @Override
  @Pure public boolean instrumentLibraries() { return true; }
  
  /** Collect and report statistics? */
  @Override
  @Pure public boolean stats() { return false; }
  /** Required because stats are being used. */
  @Override
  @Pure
  public boolean forceUseJikesInliner() {
    return stats();
  }
  @Override
  @Pure
  public boolean inlineBarriers() {
    return !stats();
  }

  /** Whether the current analysis is field sensitive. */
  @Override
  @Pure public final boolean isFieldSensitiveAnalysis() { return true; }

  @Override
  @Interruptible
  public ClientAnalysis constructClientAnalysis() {
    return new EmptyAnalysis();
  }
  
  /**
   * Disable RBA by default.
   */
  @Override
  @Interruptible // see note in BaseConfig
  @Pure public RedundantBarrierRemover.AnalysisLevel overrideDefaultRedundantBarrierAnalysisLevel() {
    return RedundantBarrierRemover.AnalysisLevel.NONE;
  }

}
