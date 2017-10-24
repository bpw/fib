package org.jikesrvm.dr;

import org.jikesrvm.config.dr.Base;
import org.jikesrvm.dr.fasttrack.FastTrack;
import org.jikesrvm.dr.metadata.maps.EpochMapper;
import org.jikesrvm.octet.Octet;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public final class Dr {
  
  /**
   * Kill switch.
   */
  public static final boolean ON = Octet.getConfig() instanceof org.jikesrvm.config.dr.Base;
  
  private static final Base config = ON ? (Base)Octet.getConfig() : new Base() {};
  @Inline
  @Pure
  public static final Base config() {
    return config;
  }

  /**
   * Choose a race checker.
   */
  private static final FastTrack fasttrack = config.newFastTrack();
  @Inline
  @Pure
  public static final FastTrack fasttrack() {
    return fasttrack;
  }
  /**
   * Choose a race checker for static fields.
   */
  private static final FastTrack staticFasttrack = config.newStaticFastTrack();
  @Inline
  @Pure
  public static final FastTrack fasttrackStatic() {
    return staticFasttrack;
  }
  
  /**
   * Choose a last readers implementation.
   */
  private static final EpochMapper readers = config.newEpochMapper();
  @Inline
  @Pure
  public static final EpochMapper readers() {
    return readers;
  }
  
  /**
   * Do synchronization instrumentation?
   */
  public static final boolean SYNC = ON && config.syncTracking();
  /**
   * Do thread sync instrumentation?
   */
  public static final boolean THREADS = SYNC;
  /**
   * Do lock sync instrumentation?
   */
  public static final boolean LOCKS = SYNC;
  /**
   * Do volatile sync instrumentation?
   */
  public static final boolean VOLATILES = SYNC;
  /**
   * Do race checks?
   */
  public static final boolean CHECKS = ON && Octet.getConfig().insertBarriers();
  public static final boolean CHECK_STATICS = CHECKS;

  /**
   * Use header storage?
   */
  public static final boolean HEADER = CHECKS || SYNC;
  /**
   * How many header words are needed?
   */
  public static final int HEADER_WORDS = HEADER ? (config.drExtraHeaderWord() ? 2 : 1) : 0;
  
  /**
   * Needs GC of header pointer AND body epochOrRef pointers.
   * (Volatile metadata gets handled by normal Jikes GC.)
   */
  public static final boolean FULL_SCAN = CHECKS;
  
  /**
   * Needs GC of header pointer.
   */
  public static final boolean SCAN = FULL_SCAN || HEADER;
  public static final boolean SCAN_STATICS = SCAN;
  
  public static final boolean ARRAY_SHADOWS = CHECKS;
  
  /**
   * Hooks for blocking and yielding?
   */
  public static final boolean COMMUNICATION = ON && config.fibCommunication();
  
  /**
   * Use buffering?
   */
  public static final boolean BUFFER = COMMUNICATION && config.fibBuffer();
  
  /**
   * Collect extra stats?
   */
  public static final boolean STATS = ON && config.stats();
  
  /**
   * Display race reports?
   */
  public static final boolean REPORTS = false;
}
