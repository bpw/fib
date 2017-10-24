package org.jikesrvm.config;

import org.jikesrvm.Constants;
import org.jikesrvm.VM;
import org.jikesrvm.compilers.opt.RedundantBarrierRemover;
import org.jikesrvm.esc.EscapeClient;
import org.jikesrvm.octet.ClientAnalysis;
import org.jikesrvm.octet.NullAnalysis;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;

@Uninterruptible
public class BaseConfig {

  public BaseConfig() {
    // Consistency check: Pessimistic barriers should disable IR-based inlining
    if (VM.VerifyAssertions) {
      if (usePessimisticBarriers()) {
        VM._assert(forceUseJikesInliner() || !inlineBarriers());
      }
    }
  }

  /** Construct the client analysis to use. By default, "null" analysis. */
  @Interruptible
  public ClientAnalysis constructClientAnalysis() { return new NullAnalysis(); }
  
  // Enable Octet components
  
  /** Do objects have extra headers words (and do static fields each have a metadata word)? */
  @Pure public boolean addHeaderWord() { return false; }

  /** Should we instrument allocation sites?
      This might just mean initializing the extra header word as part of allocation.
      It also includes initializing the static field metadata. */
  @Pure public boolean instrumentAllocation() { return false; }

  /** Insert read and write barriers? */
  @Pure public boolean insertBarriers() { return false; }

  /** Perform roundtrip communication, i.e., the request-respond protocol? */
  @Pure public boolean doCommunication() { return false; }
  
  // Configuration
  
  /** Insert the Java libraries in addition to the application? */
  @Pure public boolean instrumentLibraries() { return true; }

  // Stats
  
  /** Collect and report statistics? */
  @Pure public boolean stats() { return false; }

  // Optimizations or lack thereof

  /** Use Jikes' built-in escape analysis to identify definitely non-escaping objects? */
  @Pure public boolean useEscapeAnalysis() {
    // Octet: TODO: Jikes escape analysis seems to very broken
    return false;
    //return true;
  }

  /** Inline Octet instrumentation in the optimizing compiler?  Note that stats() should be sure to disable inlining -- or at least IR-based barriers. */
  @Pure public boolean inlineBarriers() { return true; }

  // Debugging and tuning

  /** Make the instrumentation slow path be an empty method? */
  @Pure public boolean noSlowPath() { return false; }

  /** Don't actually wait when doing communication?  Provides incorrect behavior but is useful for teasing apart performance. */
  // Octet: LATER: won't work correctly for some cases, e.g., the queue-based implementation?
  @Pure public boolean noWaitForCommunication() { return false; }

  /** Don't actually wait when trying to move an object to the intermediate state?  Provides incorrect behavior but is useful for teasing apart performance. */
  @Pure public boolean noWaitOnIntermediateState() { return false; }

  @Pure public boolean instrumentBaselineCompiler() { return true; }

  @Pure public boolean instrumentOptimizingCompiler() { return true; }

  /** Force the opt compiler to perform Octet instrumentation early instead of late? */
  @Pure public boolean forceEarlyInstrumentation() { return false; }

  /** Force the opt compiler to use the Jikes inliner (instead of inserting IR-based barriers)? */
  @Pure public boolean forceUseJikesInliner() { return false; }

  /** Force IR-based barriers to check for WrEx and RdEx, not just RdEx, as part of the fast path. */
  @Pure public boolean forceCheckBothExclStateIRBasedBarriers() { return false; }
  
  /** Force use of the communication queue, even for client analyses that don't need it (for testing purposes). */
  @Pure public boolean forceUseCommunicationQueue() { return false; }

  /** Force use of the hold state, even for client analyses that don't need it (for testing purposes). */
  @Pure public boolean forceUseHoldState() { return false; }
  
  /** The level of redundant barrier analysis.
      Client analyses can override this choice by overriding ClientAnalysis.newRedundantBarriersAnalysis(). */
  @Interruptible // since enum accesses apparently call interruptible methods
  @Pure public RedundantBarrierRemover.AnalysisLevel overrideDefaultRedundantBarrierAnalysisLevel() { return RedundantBarrierRemover.AnalysisLevel.DEFAULT_SAFE;}

  /** Use pessimistic barriers, which always use a CAS or fence, instead of Octet's optimistic barriers. */
  @Pure public boolean usePessimisticBarriers() { return false; }

  /** Defines which type of pessimistic read to use.  It can't be @Pure because it has side effects! */
  public void pessimisticRead(Address addr) { VM._assert(Constants.NOT_REACHED); }
  
  /** Defines which type of pessimistic write to use.  It can't be @Pure because it has side effects! */
  public void pessimisticWrite(Address addr) { VM._assert(Constants.NOT_REACHED); }
  
  /** Whether the current analysis is field sensitive. */
  @Pure public boolean isFieldSensitiveAnalysis() { return false; }
  
  /** enable static race detection based instrumentation filtering */
  @Pure public boolean enableStaticRaceDetection() { return false; }
  
  // Custom options that help with quickly testing new ideas
  
  @Pure public boolean custom1() { return false; }

  @Pure public boolean custom2() { return false; }

  /** Whether to enable dynamic escape analysis (DynEscape) */
  @Pure public boolean escapeAddHeaderWord() { return false; }
  
  @Pure public boolean escapeInstrumentAllocation() { return false; }

  @Pure public boolean escapeInsertBarriers() { return false; }
  
  @Pure public boolean escapeUseJikesInliner() { return false; }
  
  /** Passes field or site information to the barriers? */
  @Pure public boolean escapePassFieldInfo() { return false; }
  
  @Pure public boolean escapeUseFieldOffset() { return false; }
  
  @Pure public boolean escapePassSite() { return false; }
  
  /** Whether to pass field offset for unresolved field and array index. */
  // escapePassFieldInfo must also be enabled for array index
  @Pure public boolean escapePassFieldOffset() { return false; }
  
  @Pure public boolean escapeStats() { return false; }

  /** Compute transitive closure using RVMType.referenceOffsets? */
  @Pure public boolean escapeTraceOutEdgesGCStyle() { return true; }

  /** For access o.f = p, should DynEscape also check assertion on p's metadata?*/
  @Pure public boolean escapeCheckRhsMetadata() { return false; }
  
  @Pure public boolean escapeInstrumentVM() { return false; }
  
  /** Collect stats about what objects fail the assertion? */
  @Pure public boolean escapeCollectAssertFailure() { return false; }
  
  /** Mark objects allocated in VM context as ESCAPED? */
  @Pure public boolean escapeVMContextEscape() { return false; }
  
  /** Only use 1 bit from the GC header? */
  @Pure public boolean escapeUseOneBitInGCHeader() { return false; }
  
  /** Hooks to call on escape. */
  @Interruptible
  @Pure public EscapeClient constructEscapeClient() { return new EscapeClient(); }

}
