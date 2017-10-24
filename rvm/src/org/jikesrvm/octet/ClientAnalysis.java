package org.jikesrvm.octet;

import org.jikesrvm.VM;
import org.jikesrvm.classloader.NormalMethod;
import org.jikesrvm.compilers.baseline.ia32.OctetBaselineInstr;
import org.jikesrvm.compilers.opt.OctetOptInstr;
import org.jikesrvm.compilers.opt.OctetOptSelection;
import org.jikesrvm.compilers.opt.RedundantBarrierRemover;
import org.jikesrvm.compilers.opt.ir.Instruction;
import org.jikesrvm.esc.Esc;
import org.jikesrvm.mm.mminterface.MemoryManager;
import org.jikesrvm.scheduler.RVMThread;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;

/** Octet: abstract class for integrating with client analyses */
@Uninterruptible
public abstract class ClientAnalysis {

  /** Allows the client analysis to do something when the VM is booting. */
  @Interruptible
  protected void boot() {
    // some analyses won't want to do anything, so that's the default behavior
  }
  
  // Handlers for transitions.
  
  /** Callback for RdEx->RdSh upgrading transition, called from the thread moving the object to RdSh state. */
  @Inline
  protected void handleRdExToRdShUpgradingTransition(RVMThread oldThread, Word newMetadata, int siteID) {
    // Many analyses won't need to do anything.
  }
  
  /** Callback for fenced reads in RdSh state. */
  @Inline
  protected void handleRdShFenceTransition(Word metadata, int siteID) {
    // Many analyses won't need to do anything.
  }
  
  /** Called by the responding thread for each element on its request queue. Only called if request queues are used. */
  @Inline
  protected void handleRequestOnRespondingThread(RVMThread requestingThread) {
    // most analyses probably don't need to do anything here, so that's the default behavior
  }
  
  /** Called by the responding thread once, after it responds to all the requesting threads. */
  @Inline
  protected void handleResponsesUpdated(int oldRequestsCounter, int newResponsesCounter) {
    // most analyses probably don't need to do anything here, so that's the default behavior
  }
  
  /** Callback for upgrading transition from RdEx -> WrEx, called from the requester thread */
  @Inline
  protected void handleRdExToWrExUpgradingTransition(Word newMetadata, int siteID) {
    // most analyses probably don't need to do anything for this transition, so that's the default behavior
  }
  
  /** Callback from fairly early in thread termination, before JikesRVMSupport.threadDied() is called,
      which apparently makes I/O no longer possible. */
  @Interruptible
  public void handleThreadTerminationEarly() {
    // Default behavior is nothing.
    // This used to be where libraryCalledByVM was set to true permanently.
  }
  
  /** Callback from fairly late in thread termination.  Client analyses that override this will almost
      certainly want to include a call to super.handleThreadTermination()! */
  public void handleThreadTerminationLate() {
    // Octet: handle thread termination
    if (Octet.getConfig().doCommunication()) {
      Stats.blockCommTerminate.inc();
      // Set the thread to blocked and *dead*.
      Communication.blockCommunicationRequestsAndMakeDead();
    }
    // A potentially useful assertion
    if (VM.VerifyAssertions) { VM._assert(!MemoryManager.isAllocatingInUninterruptibleCode()); }
  }

  /** Let the client analysis to provide support for conflict detection on the requesting thread.
      Note that the remote thread might be dead.
      This hook is called whether or not the holding state is used.  If the holding state is used, the
      responding thread will be in the holding state when this hook is called. */
  @Inline
  public void handleConflictForBlockedThread(RVMThread remoteThread, Address baseAddr, Offset offset, Word oldMetadata, Word newState, 
      boolean remoteThreadIsDead, int newRemoteRequests, int fieldOffset, int siteID) {
    // Do nothing since some analyses won't need it.
  }

  /** Called by the requesting thread after it sends a request to an unblocked thread. */
  @Inline
  public void handleRequestSentToUnblockedThread(RVMThread remoteThread, int newRemoteRequests, int siteID) {
    // Do nothing since some analyses won't need it.
  }

  /** Callback for a roundtrip communication receiving a response. */
  // Octet: TODO: Can there just be one call for all received responses?  Might be simpler -- and even more efficient in the general case.
  public void handleReceivedResponse(RVMThread respondingThread, Word oldMetadata, Word newState) {
    // Do nothing since most analyses won't need it.
  }

  /** Callback for handling events after unblocking. */
  @Inline
  public void handleEventsAfterUnblockCommunication() {
    // Do nothing since most analyses won't need it.
  }

  /** Callback for handling events after unblocking.  Only called if requests were received while blocked. */
  @Inline
  public void handleEventsAfterUnblockIfRequestsReceivedWhileBlocked(int newRequestCounter) {
    // Do nothing since most analyses won't need it.
  }
  
  /** handle events before starting communication*/
  @Inline
  public void handleEventBeforeCommunication(Address baseAddr, Offset offset, Word oldMetadata, Word newState, int fieldOffset, int siteID) {
    // Do nothing since most analyses won't need it.
  }

  /** handle events after finishing communication*/
  @Inline
  public void handleEventAfterCommunication(Address baseAddr, Offset offset, Word oldMetadata, int siteID) {
    // Do nothing since most analyses won't need it.
  }

  /** Callback for handling events when a thread is looping in the slow path */
  @Inline
  public boolean handleEventsInSlowPathLoop(boolean isRead, Address baseAddr, Offset octetOffset, Word oldMetadata, int fieldOrIndexInfo, int siteID) {
    // Most analyses won't need to do anything of end the slow path loop.
    return false;
  }
  
  /** Callback before the global read share counter is updated. */
  @Inline
  public void handleBeforeUpdatingGlobalRdShCounter() {
    // Do nothing, most analyses do not require it
  }
  
  /** Let the client analysis decide whether it will use the communication queue. */
  @Inline
  public boolean needsCommunicationQueue() {
    // Basic Octet doesn't need the communication queue at all, but it can be enabled as a build option.
    return Octet.getConfig().forceUseCommunicationQueue();
  }
 
  /** Let the client analysis decide whether the current responding thread should ignore elements on the communication queue.
      Some analyses (e.g., STM) may only want to check the queue sometimes. */
  @Inline
  public boolean responseShouldCheckCommunicationQueue() {
    // STM will want to return something like currentThread.inTransaction, right?
    return needsCommunicationQueue();
  }
  
  /** Let the client analysis to specify whether using holding state */
  @Inline
  protected boolean useHoldingState() {
    return Octet.getConfig().forceUseHoldState();
  }
  
  /** Should the Octet barriers pass any field or array index info? */
  @Inline
  protected boolean needsFieldInfo() {
    // at least some analyses won't need this, so let's make that the default
    return false;
  }
  
  /** Should the Octet barrier pass the field ID (value = false) or the field offset (value = true)?
      Only relevant if needsFieldInfo() == true */
  @Inline
  protected boolean useFieldOffset() {
    // most analyses probably either don't care about fields or will be okay with the field ID
    return false;
  }
  
  @Inline
  public boolean needsSites() {
    // most analyses probably don't need sites, so the default behavior is false, unless stats is enabled
    return Octet.getConfig().stats() || Esc.getConfig().escapePassSite();
  }
  
  @Inline
  protected boolean needsUniqueSites() {
    // most analyses probably don't need unique sites, so the default behavior is false, unless stats is enabled
    return Octet.getConfig().stats();
  }
  
  @Inline
  protected void handleNewSite(Site site) {
    // most analysis probably don't need to do anything here, so that's the default behavior
  }

  // Octet: LATER: We used to have the ability to override what gets instrumented, before static cloning was added and changed everything.
  // Not sure what's the best way to support this now. Depends on what client analyses need.

  /*
  @Inline
  @Pure
  public boolean shouldInstrumentMethod(RVMMethod method) {
    // the default behavior: ???
  }

  @Inline
  @Pure
  public boolean shouldAddMetadataForStaticField(FieldReference fieldRef) {
    // the default behavior: ???
  }
  */

  /** Let the client analysis specify alternate slow path behavior.  Returning true means the regular slow path does not execute. */
  @Inline
  protected boolean alternateSlowPath(boolean isRead, Address baseAddr, Offset octetOffset, Word oldMetadata, int fieldOrIndexInfo, int siteID) {
    // the default behavior is to do the regular slow path
    return false;
  }
  
  /** Let the client analysis specify alternate read slow path behavior.  Returning true means the regular slow path does not execute. */
  @Inline
  protected boolean alternateReadSlowPath(Address baseAddr, Offset octetOffset, Word oldMetadata, int fieldOrIndexInfo, int siteID) {
    // the default behavior is to do the regular slow path
    return false;
  }
  
  /** Let the client analysis specify alternate write slow path behavior.  Returning true means the regular slow path does not execute. */
  @Inline
  protected boolean alternateWriteSlowPath(Address baseAddr, Offset octetOffset, Word oldMetadata, int fieldOrIndexInfo, int siteID) {
    // the default behavior is to do the regular slow path
    return false;
  }
  
  /** Let the client analysis specify the chooseBarrier. */
  public NormalMethod chooseBarrier(NormalMethod method, boolean isRead, boolean isField, boolean isResolved, boolean isStatic, boolean hasRedundantBarrier, boolean isSpecialized) {
    if (VM.VerifyAssertions) VM._assert(Octet.getConfig().insertBarriers(), "chooseBarrier called when insertBarriers() = false");
    return InstrDecisions.chooseBarrier(isRead, isField, isResolved, isStatic);
  }
  
  /** Support overriding/customizing barrier insertion in the baseline compiler */
  @Interruptible
  public OctetBaselineInstr newBaselineInstr() {
    return new OctetBaselineInstr();
  }
  
  /** Support overriding/customizing the choice of which instructions the opt compiler should instrument */
  @Interruptible
  public OctetOptSelection newOptSelect() {
    return new OctetOptSelection();
  }
  
  /** Analyses that want to always override the redundant barrier analysis level should override this method,
      rather than overriding BaseConfig.overrideDefaultRedundantBarrierAnalysisLevel() it in their derived configs. */
  @Interruptible
  public RedundantBarrierRemover newRedundantBarriersAnalysis() {
    return new RedundantBarrierRemover(Octet.getConfig().overrideDefaultRedundantBarrierAnalysisLevel());
  }

  /** Support overriding/customizing barrier insertion in the opt compiler */
  @Interruptible
  public OctetOptInstr newOptInstr(boolean lateInstr, RedundantBarrierRemover redundantBarrierRemover) {
    return new OctetOptInstr(lateInstr, redundantBarrierRemover);
  }
  
  /** Let client analyses throw exceptions from barriers. */
  @Inline
  public boolean barriersCanThrowExceptions() {
    return false;
  }

  /** Does the client analysis provide support for inserting individual IR instructions as barriers?
      Your analysis should return *false* unless you've added functionality in the opt compiler to insert IR instructions for barriers! */
  public abstract boolean supportsIrBasedBarriers();
  
  /** Does the client analysis increment the request counter when using implicit communication protocol?
      Your analysis should enable this option if it needs precise HB relationship when the responding thread is blocked.
      Currently only Roctet enables this option.*/
  @Pure
  public abstract boolean incRequestCounterForImplicitProtocol();
  
  /** Should the client analysis insert barriers during the LIR phase (instead of during HIR->LIR)? */
  @Inline
  public boolean useLateOptInstrumentation() {
    // By default, it makes sense to do late instrumentation if the compiler can insert IR instructions for barriers (since inlining won't work).
    // Octet: TODO: if inlining is disabled, should we also use late instrumentation?  Maybe we need another option to specify what to do in that case.
    return supportsIrBasedBarriers();
  }
  
  /**
   * Decide whether to execute hooks in the slow path. By default, we believe all client analyses will want to avoid invoking 
   * hooks/processing when the slow path is entered due to library calls made on behalf of the VM from the requesting context, 
   * but we want to execute the hooks from the responding thread's context. 
   * @return
   */
  public boolean shouldExecuteSlowPathHooks(boolean notInRespondingContext) {
    if (VM.VerifyAssertions) { VM._assert(RVMThread.getCurrentThread().isOctetThread()); }
    return true;
  }

  /**
   * On a read access, should the IR-based barrier implementation check for both WrEx and RdEx
   * (instead of checking only WrEx) before checking for the RdSh state?
   * @author Aritra 
   */
  public boolean checkBothExclStateIRBasedBarriers() {
    return Octet.getConfig().forceCheckBothExclStateIRBasedBarriers();
  }
  
  /** Decide whether we should instrument instructions having redundant barriers. Field sensitive analysis might need to turn this on to instrument accesses that have redundant barriers.*/
  public boolean instrInstructionHasRedundantBarrier(Instruction inst) { 
    return false; 
  }

  @Inline
  public boolean isSpecializedMethod(NormalMethod method) {
    return false;
  }
  
  /** Call this when the client analysis only want to instrument a set of selected methods. By default, it returns true, which means all methods will be considered as candidates for instrumentation.*/
  @Inline
  public boolean isSelectedInstrMethod(NormalMethod method) {
    return true;
  }
}
