package org.jikesrvm.dr.fib;

//import static org.jikesrvm.runtime.SysCall.sysCall;

import org.jikesrvm.SizeConstants;
import org.jikesrvm.VM;
import org.jikesrvm.compilers.common.CompiledMethods;
import org.jikesrvm.dr.Dr;
import org.jikesrvm.dr.DrDebug;
import org.jikesrvm.dr.DrRuntime;
import org.jikesrvm.dr.DrStats;
import org.jikesrvm.dr.fasttrack.FastTrack;
import org.jikesrvm.dr.metadata.AccessHistory;
import org.jikesrvm.dr.metadata.Epoch;
import org.jikesrvm.dr.metadata.VC;
import org.jikesrvm.mm.mminterface.MemoryManager;
import org.jikesrvm.objectmodel.ObjectModel;
import org.jikesrvm.runtime.Entrypoints;
import org.jikesrvm.runtime.Magic;
import org.jikesrvm.scheduler.RVMThread;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.NoInline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.pragma.UninterruptibleNoWarn;
import org.vmmagic.pragma.Unpreemptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.ObjectReference;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;
import org.vmmagic.unboxed.WordArray;

/**
 * This class includes logic for thread-to-thread communication.
 * Each RVMThread carries its own unique FibRequestManager, which holds:
 * - info about this thread's outstanding requests to other threads
 * - info about other thread's outstanding requests to this thread
 * 
 * 
 * TODO All per-thread data here should eventually move into RVMThread
 * to save the indirection, improve locality, etc. (transferRequests should
 * probably be in the same cache line as takeYieldPoint like octetRequests).
 * 
 * TODO do some layout engineering to get thread-local stuff together
 * on a single (or few) cache line, with requests and responses separate.
 * 
 */
@Uninterruptible
public final class FibComm implements SizeConstants {
  
  static {
    if (VM.VerifyAssertions) {
      VM._assert(Epoch.MAX_THREADS <= 32,
          "We use 32-bit vectors for requests and for ack responses, supporting up to 32 threads.");
    }
  }
  
  /**
   * Do sanity checking of spin counts?
   */
  protected static final boolean CHECK_SPIN = VM.VerifyAssertions;
  
  /**
   * Managing requests from and to this thread.
   */
  public final RVMThread thread;
  
  public final WordArray vc;
  
  /**
   * Owner's bit for use in bit vectors.
   */
  protected final Word BIT;
  
  /**
   * Bit vector of threads that have made a request to this.owner.
   */
  private volatile Word requests;
  
  /**
   * Bit vector of threads whose requests to this thread are just for acks.
   */
  private volatile Word ackRequests = Word.zero();
  private static final Word LOCKED = Word.zero();
  private final Word EMPTY, BLOCKED;

  /**
   * Linked list links used during request processing.
   */
  private FibComm next = null, conflict = null;
  /**
   * Temporary storage of reader set responses during
   * request processing.
   */
  private WordArray responseRef = null;
  

  
  // REQUESTS
  
  /**
   * Is the current request a write?
   */
  private boolean isWrite = false;
  
  /**
   * The object holding the history that this.owner is requesting, 
   * or null when no request is in effect.
   * 
   * For histories of static fields, this is null.
   */
  private Object object = null;
  
  /**
   * The offset of the history in this.object that this.owner is requesting,
   * or Offset.zero() if no request is in effect.
   * 
   * When a request is in effect, this offset is always with respect to the
   * address of this.object, even for static fields, when this.object is null.
   * For histories of static fields, this.offset.toWord().toAddress() is the
   * absolute address of this history.  (i.e., it is not an offset from the
   * TOC pointer)
   */
  private Offset offset = Offset.zero();
  
  private Word targetEpoch = Epoch.NONE;
  
  /**
   * For debugging/printing purposes only.
   * True if the request in effect requires only ACK and not transfer.
   * False if the request in effect requires transfer or if no request is in effect.
   */
  private boolean deflating = false;

  /**
   * Remote thread(s) place(s) response(s) to this.owner's requests here.
   */
  private volatile Word response = NO_RESPONSE;
  
//  private static final WordArray responses = MemoryManager.newNonMovingWordArray(Epoch.MAX_THREADS * Epoch.MAX_THREADS);
//  
//  private static Address responseSlot(int requester, int responder) {
//    return ObjectReference.fromObject(responses).toAddress().plus(
//        responder * Epoch.MAX_THREADS * BYTES_IN_WORD
//        + requester * BYTES_IN_WORD
//    );
//  }
//  private static Address responseSlot(FibComm requester, FibComm responder) {
//    return responseSlot(requester.thread.getDrID(), responder.thread.getDrID());
//  }
  
  private static final Word NO_RESPONSE = Word.zero();
  private static final Word EXCL_RESPONSE = Word.one();
  private static final Word RACE_RESPONSE = Word.one().lsh(1);
  private static final Word SHARED_RESPONSE = EXCL_RESPONSE.not();
  // private static final Word RETRY_RESPONSE = RACE_RESPONSE.not();
  
//  /**
//   * Enable heavy waiting for slow responses?
//   */
//  private static final boolean HEAVY_WAITS = Dr.config().fibHeavyTransfers();
//  
//  /**
//   * Is this thread currently heavy waiting?
//   */
//  private volatile boolean heavyWaiting = false;
  
  /**
   * How deep is reentrancy of blocking?
   */
  private int blockDepth = 0;
  /**
   * How deep is reentrancy of blocking?
   */
  public int getBlockDepth() {
    return blockDepth;
  }
  /**
   * Debugging: where did blocking start?
   */
  private int blockSourceMethodID, blockSourceCallerMethodID, blockSourceCallerCallerMethodID;

  
  /**
   * How many times should a spin loop spin before calling RVMThread.yield*?
   */
  @Inline
  public static final int spinsBeforeYield(){
    return VM.octetWaitSpinCount;
  }
  
  public FibComm(final RVMThread thread, int tid, WordArray vc) {
    if (VM.VerifyAssertions) VM._assert(thread.isDrThread());
    this.thread = thread;
    this.BIT = Word.one().lsh(tid);
    this.EMPTY = this.BIT;
    this.BLOCKED = this.EMPTY.not();
    this.requests = this.EMPTY;
    this.vc = vc;
    if (PRINT) {
      DrDebug.lock();
      DrDebug.twrite(thread); VM.sysWriteln(" has BIT = ", BIT);
      DrDebug.unlock();
    }
  }
  
  

  // CASes etc. for communication fields.
  
  @Inline
  private boolean attemptRequests(final Word old, final Word noo) {
    if (ObjectReference.fromObject(this).toAddress().attempt(old, noo,
        Entrypoints.drFibCommRequestsField.getOffset())) {
      if (PRINT_DETAIL) printAttemptRequests(old, noo);
      Magic.readCeiling();
      return true;
    }
    Magic.readCeiling();
    return false;
  }
  private void printAttemptRequests(final Word old, final Word noo) {
    if (PRINT_DETAIL) {
      DrDebug.lock();
      DrDebug.twriteln("attemptReqs succeeded");
      VM.sysWriteln("  owner thread = T", thread.getDrID(), " [", thread.getThreadSlot(), "]");
      if (old == LOCKED) {
        VM.sysWriteln("  old = LOCKED ", old);
      } else if (old == this.EMPTY) {
        VM.sysWriteln("  old = EMPTY ", old);
      } else if (old == this.BLOCKED) {
        VM.sysWriteln("  old = BLOCKED ", old);
      } else {
        VM.sysWriteln("  old = ", old);
      }
      if (noo == LOCKED) {
        VM.sysWriteln("  new = LOCKED ", noo);
      } else if (noo == this.EMPTY) {
        VM.sysWriteln("  new = EMPTY ", noo);
      } else if (noo == this.BLOCKED) {
        VM.sysWriteln("  new = BLOCKED ", noo);
      } else {
        VM.sysWriteln("  new = ", noo);
      }
      DrDebug.unlock();
    }
  }
  
  @Inline
  private Word prepareRequests() {
    return ObjectReference.fromObject(this).toAddress().prepareWord(Entrypoints.drFibCommRequestsField.getOffset());
  }
  
  @Inline
  private void setRequests(final Word x) {
    if (PRINT_DETAIL) printSetRequests(x);
    Magic.writeFloor();
    requests = x;
  }
  private void printSetRequests(final Word x) {
    if (PRINT_DETAIL) {
      DrDebug.lock();
      DrDebug.twrite();
      if (x == LOCKED) {
        VM.sysWrite("setReqs LOCKED ", x, " for ");
      } else if (x == this.EMPTY) {
        VM.sysWrite("setReqs EMPTY ", x, " for ");
      } else if (x == this.BLOCKED) {
        VM.sysWrite("setReqs BLOCKED ", x, " for ");
      } else {
        VM.sysWrite("setReqs ", x, " for ");
      }
      DrDebug.twrite(this.thread);
      VM.sysWriteln();
      DrDebug.unlock();
    }
  }
  
  @Inline
  private boolean attemptResponse(Word old, Word noo) {
    boolean x = ObjectReference.fromObject(this).toAddress().attempt(old, noo, Entrypoints.drFibCommResponseField.getOffset());
    Magic.readCeiling();
    return x;
  }
  
  @Inline
  private Word prepareResponse() {
    return ObjectReference.fromObject(this).toAddress().prepareWord(Entrypoints.drFibCommResponseField.getOffset());
  }
  

  
  // Error-checking/debugging
  
  /**
   * Enable printing of events?
   */
  protected static final boolean PRINT = VM.VerifyAssertions && false;
  @SuppressWarnings("unused")
  private static final boolean PRINT_DETAIL = PRINT && false;
  /**
   * Number of spins at which "something is wrong."
   */
  protected static final int MAX_WAIT_SPINS = 100000000;

  /**
   * Check for spinning too long.
   * @param tries
   * @param bound
   * @param message
   */
  @NoInline
  protected static void checkSpin(final int tries, final int bound, final String message) {
    checkSpin(tries, bound, Word.zero(), message);
  }
  protected static void checkSpin(final int tries, final int bound, final Word expected, final String message) {
    if (tries > bound) {
      //          RVMThread.dumpVirtualMachine();
      DrDebug.lock();
      DrDebug.twrite();
      if (expected.isZero()) {
        VM.sysWriteln(message, " is spinning too long.");
      } else {
        VM.sysWrite(message);
        VM.sysWriteln(" is spinning too long, expecting ", expected);
      }
//      walk(RVMThread.getCurrentThread().drFibComm);
      DrDebug.unlock();
      VM.sysFail("Too many spins.");
    }
  }

//  /**
//   * Do dependency-walking/cycle detection for debugging the communication mechanism.
//   * TODO: Currently does not understand ack dependences.
//   * @param frm - thread where walk should begin.
//   */
//  @SuppressWarnings("unused")
//  @NoInline
//  private static void walk(FibComm frm) {
//    if (true) {
//      DrDebug.twriteln(">>>> Begin thread statuses.");
//      for (int i = 0; i < DrRuntime.maxLiveDrThreads(); i++) {
//        DrRuntime.getDrThread(i).drFibComm.printStatus();
//      }
//      DrDebug.twriteln("<<<< End   thread statuses.");      
//      return;
//    }
//    
//    DrDebug.twrite();
//    VM.sysWrite("Initiating (unsafe) cycle detection from origin thread ");
//    DrDebug.twrite(frm.thread);
//    VM.sysWriteln();
//    VM.sysWriteln("---------------");
//
//    // Collect a set of threads in a potential cycle.
//    Word bv = Word.zero();
//    // Until a thread appears twice, search dependences.
//    while (bv.and(frm.BIT).isZero()) {
//      // Report on the cursor thread.
//      frm.printStatus();
//      VM.sysWriteln("---------------");
//      // If the cursor thread has no pending request, done.
//      final Word nextTarget = frm.targetEpoch;
//
//      // If the cursor thread has a pending request, continue.
//
//      // Get the current effective read word of the cursor's request.
//      final Word lr = AccessHistory.loadReadWord(frm.object, frm.offset);
//      if (Epoch.isEpoch(lr)) {
//        final RVMThread t = DrRuntime.getDrThread(Epoch.tid(lr));
//        VM.sysWriteln("", AccessHistory.address(frm.object, frm.offset), " appears exclusive to T", t.getDrID());
//        if (!Epoch.sameTid(nextTarget, lr)) {
//          VM.sysWriteln("        -- Which differs from the target.");
//        }
//        //        t.fibRequestManager.walk();
//        // frm = t.fibRequestManager;
//      } else if (Epoch.isNone(lr)) {
//        VM.sysWriteln("", AccessHistory.address(frm.object, frm.offset), " appears unaccessed");
//      } else if (Epoch.isReserved(lr)){
//        VM.sysWriteln("", AccessHistory.address(frm.object, frm.offset), " appears reserved");
//      } else {
//        VM.sysWriteln("", AccessHistory.address(frm.object, frm.offset), " appears shared");
//      }
//
//      if (frm.response != NO_RESPONSE) {
//        DrDebug.twrite(frm.thread); VM.sysWriteln("has a response.  Terminating cycle detection.");
//        DrDebug.twriteln("---> Did not find conclusive cycle <----");
//        return;
//      } else if (Epoch.isEpoch(nextTarget)) {
//        bv = bv.or(frm.BIT);
//        final FibComm next = getTarget(frm.object, frm.offset, nextTarget);
//        if (next == frm) {
//          DrDebug.twriteln("---> Self edge <----");
//          return;
//        } else if (next == null) {
//          DrDebug.twriteln("---> Did not find conclusive cycle <----");
//          return;
//        } else {
//          frm = next;
//        }
//      } else {
//        DrDebug.twrite(frm.thread); VM.sysWriteln("has no target.  Terminating cycle detection.");
//        DrDebug.twriteln("---> Did not find conclusive cycle <----");
//        return;
//      }
//    }
//    DrDebug.twriteln("---> CYCLE <---");
//  }
  
  /**
   * Print lots of information about the communication status of this.owner.
   */
  @NoInline
  private void printStatus() {
    VM.sysWrite("Status of "); DrDebug.twrite(this.thread);  VM.sysWriteln();
    VM.sysWriteln("  isAlive()   = ", thread.isAlive());
    VM.sysWriteln("  isBlocked() = ", thread.isBlocked());
    VM.sysWriteln("  isInJava()  = ", thread.isInJava());
    VM.sysWriteln("  FIB blocked = depth ", blockDepth);
    VM.sysWriteln("  request     = ", AccessHistory.address(object, offset));
    VM.sysWriteln("    object    = ", ObjectReference.fromObject(object).toAddress());
    VM.sysWriteln("    offset    = ", offset);
    VM.sysWrite  ("    target    = ");  Epoch.print(targetEpoch); VM.sysWriteln();
    // VM.sysWriteln("    del       = ",   ObjectReference.fromObject(del).toAddress());
    VM.sysWriteln("  deflating   = ", VM.VerifyAssertions ? (deflating ? "true" : "false") : "??? [Enable assertions]");
    if (response.EQ(NO_RESPONSE)) {
      VM.sysWriteln("  response    = NO_RESPONSE");
    } else if (response.EQ(RACE_RESPONSE)) {
      VM.sysWriteln("  response    = RACE_RESPONSE");
    } else if (response.EQ(SHARED_RESPONSE)) {
      VM.sysWriteln("  response    = SHARED_RESPONSE");
    } else if (response.EQ(EXCL_RESPONSE)) {
      VM.sysWriteln("  response    = EXCL_RESPONSE");
    } else {
      final Word r = response;
      VM.sysWrite("  response    = ", r);
      if (Epoch.isEpoch(r)) {
        VM.sysWriteln(" [epoch T", Epoch.tid(r), ":", Epoch.clock(r), " ]");
      }
    }
    VM.sysWriteln("  responseRef = ", ObjectReference.fromObject(responseRef).toAddress());
    final Word reqs = requests;
    if (reqs.EQ(this.EMPTY)) {
      VM.sysWriteln("  requests    = EMPTY");
    } else if (reqs.EQ(this.BLOCKED)) {
      VM.sysWriteln("  requests    = BLOCKED");      
    } else if (reqs.EQ(LOCKED)) {
      VM.sysWriteln("  requests    = LOCKED");      
    } else {
      VM.sysWriteln("  requests    = ");
      for (int tid = 0; tid < Epoch.MAX_THREADS; tid++) {
        if (!reqs.and(Word.one().lsh(tid)).isZero() && tid != thread.getDrID()) {
          final FibComm frm = DrRuntime.getDrThread(tid).drFibComm;
          VM.sysWriteln("    + T", frm.thread.getDrID(), "      waiting for ", AccessHistory.address(frm.object, frm.offset));
        }
      }
    }
  }
  
  // Called by REQUESTER
  
  
  
  
  /**
   * Request a race check and ownership state transition for the given access.
   * NOTE: call on the requester's FRM.
   * 
   * @param md - target object
   * @param historyOffset - offset of access history within md
   * @param isWrite - true iff the access is a write
   * @return true iff the access is DRF.
   */
  @NoInline
  @Unpreemptible
  protected boolean requestTransition(final Object md, final Offset historyOffset,
      Word readWord, final boolean isWrite) {
    if (VM.VerifyAssertions) {
      VM._assert(this.thread == RVMThread.getCurrentThread());
      VM._assert(Epoch.isEpoch(readWord) || Epoch.isReserved(readWord));
      VM._assert(0 == this.getBlockDepth(), "Requests shoud be made only when unblocked.");
      VM._assert(!Epoch.isAlt(readWord));
    }
    if (Epoch.sameTid(readWord, RVMThread.getCurrentThread().getDrEpoch())) {
      VM.sysFail("Requesting from self.");
    }


    // RESERVED is used only during writes.  Observing it implies a race.
    if (Epoch.isReserved(readWord) || FastTrack.isFrozen(readWord)) {
      if (Dr.STATS) (isWrite ? DrStats.writeReserved : DrStats.readReserved).inc();
//      DrDebug.lock();
//      DrDebug.twriteln("request observed RESERVED status: must race.");
//      DrDebug.unlock();
      return false;
    }

    if (Dr.STATS) {
      requestsSentInCurrentEpoch++;
      DrStats.requestExcl.inc();
      recordDest(readWord);
    }

    // Accounting of how involved the chase becomes.
    // How many spins?
    int tries = 0;
    // How many times did the location change ownership before
    // a request was successfully accomplished?
    int hops = 0;

    // Target thread of request.
    FibComm remote = getTarget(md, historyOffset, readWord);
    if (PRINT) {
      DrDebug.lock();
      DrDebug.twrite();
      VM.sysWrite(" trying to request ", AccessHistory.address(md, historyOffset), " from ");
      DrDebug.twrite(remote.thread);
      VM.sysWriteln();
      DrDebug.unlock();
    }
    if (VM.VerifyAssertions) {
      VM._assert(remote != this, "Requested but already owned.");
    }
    final boolean result;
    // Until resolved, keep trying:
    while (true) {
      if (CHECK_SPIN) targetEpoch = readWord;
      final Word newReadWord;
      // Load the head of the remote request queue.
      final Word remoteReqs = remote.prepareRequests();
      
      if (remoteReqs.EQ(remote.BLOCKED)) {
        
        // Remote queue is BLOCKED: attempt self-service.
        if (remote.attemptRequests(remote.BLOCKED, LOCKED)) {
          // Attempt SELF_SERVICE
          Magic.readCeiling();
          if (VM.VerifyAssertions) DrRuntime.stashGcCount();
          newReadWord = AccessHistory.loadReadWord(md, historyOffset);
          if (Dr.config().fibAdaptiveCas() && Epoch.isAlt(newReadWord)) {
            result = (isWrite
                ? FibFastTrack.writeCas(md, historyOffset, newReadWord, RVMThread.getCurrentThread().getDrEpoch())
                    : FibFastTrack.readCas(md, historyOffset, newReadWord, RVMThread.getCurrentThread().getDrEpoch()));
            break;
          }
          else if (Epoch.sameTid(newReadWord, readWord)) { // implies Epoch.isEpoch(lrw)
            if (VM.VerifyAssertions) {
              checkSaneRequest(md, historyOffset, remote, newReadWord, isWrite);
            }
            // ATOMIC REQUEST
            // If owned by the same remote thread, self-serve the request.
            // Self-serve the request.  INCLUDES unlocking remote queue.
            result = selfServiceCheckExcl(RVMThread.getCurrentThread().getDrEpoch(), md, historyOffset, isWrite, newReadWord, remote, true);
//            FibDebug.lock();
//            FibDebug.twriteln("SELF CHECK EXCL read");
//            FibDebug.unlock();
            break;
          } else {
            // CONCURRENT REQUEST
            // Ownership has changed since the request was initiated.
            // Unlock the remote thread's queue.
            remote.setRequests(remoteReqs);
          }
        } else {
          if (VM.VerifyAssertions) DrRuntime.stashGcCount();
          // Self-service FAILED.  Try again.
          newReadWord = AccessHistory.loadReadWord(md, historyOffset);
        }
        
      } else if (remoteReqs.EQ(LOCKED)) {
        
        if (VM.VerifyAssertions) DrRuntime.stashGcCount();
        // Remote queue is LOCKED.  Try again.
        newReadWord = AccessHistory.loadReadWord(md, historyOffset);
        
      } else {
        
        // Remote queue is OPEN: Attempt to enqueue a request.
        if (remote.attemptRequests(remoteReqs, LOCKED)) {
          // Locked the remote queue.
          if (VM.VerifyAssertions) DrRuntime.stashGcCount();
          newReadWord = AccessHistory.loadReadWord(md, historyOffset);
          if (Dr.config().fibAdaptiveCas() && Epoch.isAlt(newReadWord)) {
            result = (isWrite
                ? FibFastTrack.writeCas(md, historyOffset, newReadWord, RVMThread.getCurrentThread().getDrEpoch())
                    : FibFastTrack.readCas(md, historyOffset, newReadWord, RVMThread.getCurrentThread().getDrEpoch()));
            break;
          }
          else if (Epoch.sameTid(newReadWord, readWord)) { // also implies Epoch.isEpoch(lrw)
            // Requested access history is currently OWNED DIRECTLY by the same thread that
            // owned it when the request initiated.
            if (VM.VerifyAssertions) {
              checkSaneRequest(md, historyOffset, remote, newReadWord, isWrite);
            }
            // If the owner has not changed, enqueue the request.
            // Record the requested check/transition.
            this.isWrite = isWrite;
            this.object = md;
            this.offset = historyOffset;
            this.targetEpoch = newReadWord;
            reportRequestEnqueue(md, historyOffset, isWrite, remote);
            Magic.writeFloor();
            // Enqueue the request (this thread's FRM) in the remote queue,
            // simultaneously unlocking the remote queue.
            remote.setRequests(remoteReqs.or(this.BIT));
            Magic.writeFloor();
            // Tell the remote thread to take its next yieldpoint.
            remote.thread.takeYieldpoint = 1;
            Magic.writeFloor();
            // Wait for the remote thread to respond.
            Word resp = awaitResponse();
            result = resp.NE(RACE_RESPONSE);
//            FibDebug.lock();
//            FibDebug.twriteln("RESPONSE read");
//            FibDebug.unlock();
            break;
          } else { 
            // CONCURRENT REQUEST
            // Ownership has changed since the request was initiated.
            // Unlock the remote thread's queue.
            remote.setRequests(remoteReqs);
          }
        } else {
          if (VM.VerifyAssertions) DrRuntime.stashGcCount();
          // Enqueueing FAILED.  Try again.
          newReadWord = AccessHistory.loadReadWord(md, historyOffset);
        }
        
      }
      
      // Reaching here implies the request was not resolved and will be retried.
      if (FastTrack.isFrozen(newReadWord)) {
        return false;
      }
      if (!Epoch.sameTid(readWord, newReadWord)) {
        // The owner changed while requesting.
        if (Epoch.sameTid(newReadWord, RVMThread.getCurrentThread().getDrEpoch())) {
          VM.sysFail("Requesting from self due to read word changing to become owned.");
        }
        
        if (!isWrite && Epoch.isMapRef(newReadWord)) {
          // -> SHARED: do a read-shared check.
          if (VM.VerifyAssertions) DrRuntime.checkGcCount();
          reportRequestConcurrentShare(md, historyOffset, remote);
          result = FibFastTrack.readShared(md, historyOffset,
              Epoch.asMapRef(newReadWord), RVMThread.getCurrentThread().getDrEpoch());
          if (VM.VerifyAssertions) DrRuntime.checkGcCount();
//          FibDebug.lock();
//          FibDebug.twriteln("CONCURRENT SHARE read");
//          FibDebug.unlock();
          break;
        }
        
        if (isWrite || Epoch.isReserved(newReadWord)
            || (Epoch.isEpoch(newReadWord)
                && newReadWord.EQ(AccessHistory.loadWriteWord(md, historyOffset)))) {
          // If either request was a WRITE: RACE.
          reportRequestRace(md, historyOffset, remote);
          result = false;
//          FibDebug.lock();
//          FibDebug.twriteln("CONCURRENT WRITE read");
//          FibDebug.unlock();
          break;
        }

        if (VM.VerifyAssertions) VM._assert(Epoch.isEpoch(newReadWord));
        // Prepare for request to new owner.
        remote = getTarget(md, historyOffset, newReadWord);
        if (VM.VerifyAssertions) {
          VM._assert(remote != this, "Requested but already owned.");
        }
        ++hops;
        reportRequestHop(md, historyOffset, remote);
      }
      readWord = newReadWord;

      if (Dr.STATS && tries == spinsBeforeYield()) DrStats.slowRequests.inc();
      if (++tries > spinsBeforeYield()) {
        if (Dr.STATS) DrStats.yieldsWhileRetryingRequest.inc();
        respond();
        RVMThread.yieldNoHandshake();
      } else {
        Magic.pause();
      }
      if (CHECK_SPIN) checkSpin(tries, MAX_WAIT_SPINS, "requestTransition");
      
    } // end loop
    
    if (Dr.STATS) {
      DrStats.requestRetryIters.incBin(tries, result);
      DrStats.requestHops.incBin(hops, result);
    }
    if (CHECK_SPIN) targetEpoch = Epoch.NONE;
    return result;
  }
  
  
  /**
   * 
   * @param md
   * @param historyOffset
   * @param readWord
   * @return current owner
   */
  private static FibComm getTarget(Object md, Offset historyOffset, Word readWord) {
    if (VM.VerifyAssertions) VM._assert(Epoch.isEpoch(readWord));
    return DrRuntime.getDrThread(Epoch.tid(readWord)).drFibComm;
  }


  private void checkSaneRequest(final Object md, final Offset historyOffset,
      FibComm remote, final Word newReadWord, boolean isWrite) {
    // Requesting from a thread that's requesting it from us and has not received a response?
    if (md != null && remote.object == md && remote.offset == historyOffset && remote.response.EQ(NO_RESPONSE)) {
        DrDebug.lock();
        DrDebug.twrite(); VM.sysWrite("and "); DrDebug.twrite(remote.thread);
        VM.sysWrite("both request ", AccessHistory.address(md, historyOffset), " from each other, with current status ");
        Epoch.print(newReadWord);
        VM.sysWriteln();
        this.object = md;
        this.offset = historyOffset;
        this.targetEpoch = newReadWord;
        this.isWrite = isWrite;
//        walk(this);
        DrDebug.unlock();
        VM._assert(false);
    }
  }
  
  private static void printTransition(Word oldReadWord, Word newReadWord) {
    Epoch.print(oldReadWord);
    VM.sysWrite(" -> ");
    Epoch.print(newReadWord);
  }

  private static void reportRequestEnqueue(final Object md,
      final Offset historyOffset, final boolean isWrite,
      FibComm remote) {
    if (PRINT) {
      DrDebug.lock();
      DrDebug.twrite();
      VM.sysWriteln(isWrite ? "enqueued request to WRITE " : "enqueued request to READ ",
          AccessHistory.address(md, historyOffset), " with T", remote.thread.getDrID());
      // VM.sysWriteln("requests: ", remoteReqs, " -> ", remoteReqs.or(this.BIT));
      DrDebug.unlock();
    }
    if (Dr.STATS) {
      DrStats.requestExcl.inc();
    }
  }

  private void reportRequestHop(Object md, Offset historyOffset, FibComm remote) {
    if (PRINT) {
      DrDebug.lock();
      DrDebug.twrite();
      VM.sysWrite(" retrying request of ", AccessHistory.address(md, historyOffset), " now with ");
      DrDebug.twrite(remote.thread);
      VM.sysWriteln();
      DrDebug.unlock();
    }
    if (VM.VerifyAssertions) {
      VM._assert(remote != this, "Self-request is illegal.");
    }
    if (Dr.STATS) {
      DrStats.requestExclSelfServiceFail.inc();
    }
  }

  private static void reportRequestConcurrentShare(final Object md,
      final Offset historyOffset, FibComm remote) {
    if (Dr.STATS) {
      DrStats.requestExclBecameShared.inc();
    }
    if (PRINT) {
      DrDebug.lock();
      DrDebug.twrite();
      VM.sysWriteln("while trying READ request with ");
      DrDebug.twrite(remote.thread);
      VM.sysWriteln(", ", AccessHistory.address(md, historyOffset), " became SHARED, resolving this request.");
      DrDebug.unlock();
    }
  }

  private static void reportRequestRace(final Object md, final Offset historyOffset,
      FibComm remote) {
    if (PRINT) {
      DrDebug.lock();
      DrDebug.twrite();
      VM.sysWrite("while trying request with ");
      DrDebug.twrite(remote.thread);
      VM.sysWriteln(", ", AccessHistory.address(md, historyOffset), " was involved in a transition indicating this access races.");
      DrDebug.unlock();
    }
  }


  /**
   * Do the race checks required for an EXCL -> EXCL transition
   * by self service.
   * 
   * NOTE: Since self-service is assumed to be relatively cheap,
   * this does NOT count the conflicting transition.
   * 
   * @param now - current epoch
   * @param md - object
   * @param historyOffset - metadata offset within object
   * @param isWrite - write or read
   * @param lre - last read epoch
   * @return true iff DRF
   */
  @Unpreemptible
  private static boolean selfServiceCheckExcl(final Word now,
      final Object md, final Offset historyOffset,
      final boolean isWrite, final Word lre, FibComm remote, boolean releaseRemote) {
    if (Dr.STATS) {
      DrStats.requestExclSelfService.inc();
    }
    if (PRINT) {
      DrDebug.lock();
      DrDebug.twrite();
      VM.sysWrite(isWrite ? "self-serving WRITE request of " : "self-serving READ request of ",
          AccessHistory.address(md, historyOffset), " for blocked thread ");
      DrDebug.twrite(remote.thread); VM.sysWriteln();
      DrDebug.unlock();
    }
    if (Epoch.isAlt(lre)) VM.sysFail("should not be alt");
    final boolean shouldCas = Dr.config().fibAdaptiveCas() && FibCas.threshold(md, historyOffset);

//    final Word epoch = RVMThread.getCurrentThread().getFibEpoch();
    final WordArray threadVC = RVMThread.getCurrentThread().drThreadVC;
    if (VC.epochHB(lre, threadVC)) {
      // Make the transition.
      if (isWrite) {
        if (PRINT) {
          DrDebug.lock();
          DrDebug.twrite();
          VM.sysWrite("self-served WRITE request of ",
              AccessHistory.address(md, historyOffset), " for blocked thread ");
          DrDebug.twrite(remote.thread); VM.sysWrite(" EXCL: "); printTransition(lre, now); VM.sysWriteln();
          DrDebug.unlock();
        }
        AccessHistory.storeWriteWord(md, historyOffset, now);
        Magic.writeFloor();
        AccessHistory.storeReadWord(md, historyOffset, shouldCas ? Epoch.asAlt(now) : now);
        Magic.writeFloor();
        // Unlock the remote thread's queue.
        if (releaseRemote) {
          remote.setRequests(remote.BLOCKED);
        }
      } else if ((Dr.config().fibPreemptiveReadShare() || (Dr.config().fibStaticReadShare() && md == null))
          && AccessHistory.loadWriteWord(md, historyOffset).NE(lre)) {
        // If last read is in a different thread than this read
        // and in a different epoch than the last write, then
        // preemptively move to read-shared even if reads are ordered.
        readShare(md, historyOffset, lre, now, false, remote);
        // Unlock the remote thread's queue.
        Magic.writeFloor();
        remote.setRequests(remote.BLOCKED);
        if (Dr.STATS) DrStats.preemptiveReadShare.inc();
        if (Dr.STATS) DrStats.preemptiveReadShareBlocked.inc();
      } else {
        if (PRINT) {
          DrDebug.lock();
          DrDebug.twrite();
          VM.sysWrite("self-served READ request of ",
              AccessHistory.address(md, historyOffset), " for blocked thread ");
          DrDebug.twrite(remote.thread); VM.sysWrite(" EXCL: "); printTransition(lre, now); VM.sysWriteln();
          DrDebug.unlock();
        }
        AccessHistory.storeReadWord(md, historyOffset, shouldCas ? Epoch.asAlt(now) : now);
        // Unlock the remote thread's queue.
        Magic.writeFloor();
        remote.setRequests(remote.BLOCKED);
      }
      return true;
    } else if (!isWrite && VC.epochHB(AccessHistory.loadWriteWord(md, historyOffset), threadVC)) {
      readShare(md, historyOffset, lre, now, true, remote);
      // Unlock the remote thread's queue.
      Magic.writeFloor();
      remote.setRequests(remote.BLOCKED);
      return true;
    } else {
      if (PRINT) {
        DrDebug.lock();
        DrDebug.twrite();
        VM.sysWrite(isWrite ? "abroted self-serve WRITE request of " : "aborted self-serve READ request of ",
            AccessHistory.address(md, historyOffset), " for blocked thread ");
        DrDebug.twrite(remote.thread); VM.sysWrite(" RACE.");
        DrDebug.unlock();
      }
      // Overwrite old metadata in case of race.
      if (Dr.config().drFirstRacePerLocation()) {
        FastTrack.freeze(md, historyOffset);
      } else {
        if (isWrite) AccessHistory.storeWriteWord(md, historyOffset, now);
        Magic.writeFloor();
        AccessHistory.storeReadWord(md, historyOffset, shouldCas ? Epoch.asAlt(now) : now);
        // Unlock the remote thread's queue.
        Magic.writeFloor();
      }
      remote.setRequests(remote.BLOCKED);
      // race.
      return false;
    }
  }

  /**
   * Make transition to a shared read map.
   * 
   * @param md - object
   * @param historyOffset - metadata offset within object
   * @param lre - last read epoch
   * @param epoch - current epoch
   * @param keepLastRead - place old last read (lre) in new read map?
   */
  private static void readShare(final Object md, final Offset historyOffset,
      final Word lre, final Word epoch, boolean keepLastRead, FibComm remote) {
    if (Dr.STATS) DrStats.readShare.inc();
    
    if (Dr.config().fibAdaptiveCas()) {
      FibCas.resetCount(md, historyOffset);
    }

    final WordArray readers = Dr.readers().create();
    if (keepLastRead) {
      Dr.readers().set(readers, lre);
    }
    Dr.readers().set(readers, epoch);
    Magic.writeFloor();
    if (PRINT) {
      DrDebug.lock();
      DrDebug.twrite();
      VM.sysWrite("self-served READ request of ",
          AccessHistory.address(md, historyOffset), " for blocked thread ");
      DrDebug.twrite(remote.thread); VM.sysWrite(" SHARE: "); printTransition(lre, ObjectReference.fromObject(readers).toAddress().toWord()); VM.sysWriteln();
      DrDebug.unlock();
    }
    Dr.readers().install(md, historyOffset, readers);
  }


  /**
   * Await any response to the owner.  (Called by owner thread after
   * requesting from another thread.)
   * 
   * @return true iff DRF
   * 
   * TODO: Stats indicate that a majority of calls spin 32-512 times
   * before a response.  Is this enough to suggest that it makes sense
   * to start with a queue-processing or yield?
   *   - A fast yield is probably faster than this many spins.
   *   - A slow yield is probably not, except a simple case with one request.
   *   - Inserting these yields may also improve the response time seen
   *     by other threads.
   */
  @Unpreemptible
  private Word awaitResponse() {
    if (VM.VerifyAssertions) VM._assert(this.thread == RVMThread.getCurrentThread());

    final int maxSpins = spinsBeforeYield();
    int tries = 0;
    // Wait for response.
    Magic.fence();
    Word w = response;
    while (w.EQ(NO_RESPONSE)) {
      // Spin until a response appears.
      if (Dr.STATS && tries == maxSpins) DrStats.slowResponses.inc();
      if (++tries > maxSpins) {
        // If spinning a long time, start responding while waiting.
        if (Dr.STATS) DrStats.yieldsWhileAwaitingResponse.inc();
//        if (HEAVY_WAITS && tries > maxSpins + VM.octetWaitYieldCount) {
//          // If spinning too long, revert to a heavy monitor wait.
//          awaitResponseHeavy();
//          if (VM.VerifyAssertions) VM._assert(response.NE(NO_RESPONSE));
//        } else {
          // Otherwise, respond to clear queue, then yield.
          if (CHECK_SPIN) checkSpin(tries, MAX_WAIT_SPINS, "awaitResponse, yield-spin");
          respond();
          RVMThread.yieldNoHandshake();
//        }
      } else {
        // If spinning a relatively short time, just pause.
        if (CHECK_SPIN) checkSpin(tries, MAX_WAIT_SPINS, "awaitResponse, pause-spin");
        Magic.pause();
      }
      Magic.readCeiling();
      w = response;
    }
    
    reportTransition(object, offset, targetEpoch, maxSpins, tries, w);

    // Read and clear the response.
    Magic.readCeiling();
    response = NO_RESPONSE;
    object = null;
    offset = Offset.zero();
    isWrite = false;
    targetEpoch = Epoch.NONE;
    // del = null;
    
    return w;
  }
//  @SuppressWarnings("unused")
//  private Word awaitResponse(FibComm responder) {
//    if (VM.VerifyAssertions) VM._assert(this.thread == RVMThread.getCurrentThread());
//
//    final int maxSpins = spinsBeforeYield();
//    int tries = 0;
//    // Wait for response.
//    Magic.fence();
//    final Address slot = responseSlot(this, responder);
//    Word w = slot.prepareWord();
//    while (w.EQ(NO_RESPONSE)) {
//      // Spin until a response appears.
//      if (Dr.STATS && tries == maxSpins) DrStats.slowResponses.inc();
//      if (++tries > maxSpins) {
//        // If spinning a long time, start responding while waiting.
//        if (Dr.STATS) DrStats.yieldsWhileAwaitingResponse.inc();
//        if (HEAVY_WAITS && tries > maxSpins + VM.octetWaitYieldCount) {
//          // If spinning too long, revert to a heavy monitor wait.
//          awaitResponseHeavy();
//          if (VM.VerifyAssertions) VM._assert(slot.prepareWord().NE(NO_RESPONSE));
//        } else {
//          // Otherwise, respond to clear queue, then yield.
//          if (CHECK_SPIN) checkSpin(tries, MAX_WAIT_SPINS, "awaitResponse, yield-spin");
//          respond();
//          RVMThread.yieldNoHandshake();
//        }
//      } else {
//        // If spinning a relatively short time, just pause.
//        if (CHECK_SPIN) checkSpin(tries, MAX_WAIT_SPINS, "awaitResponse, pause-spin");
//        Magic.pause();
//      }
//      Magic.readCeiling();
//      w = slot.prepareWord();
//    }
//    
//    reportTransition(object, offset, targetEpoch, maxSpins, tries, w);
//
//    // Read and clear the response.
//    Magic.readCeiling();
//    slot.store(NO_RESPONSE);
//    object = null;
//    offset = Offset.zero();
//    isWrite = false;
//    targetEpoch = Epoch.NONE;
//    // del = null;
//    
//    return w;
//  }

//  /**
//   * Use heavy monitor to block awaiting response.
//   */
//  private void awaitResponseHeavy() {
//    if (response.EQ(NO_RESPONSE)) {
//      // Heavy wait.
//      if (Dr.STATS) DrStats.heavyResponses.inc();
//      this.heavyWaiting = true;
//      block();
//      if (response.EQ(NO_RESPONSE)) {
//        Magic.fence();
//        Magic.readCeiling();
//        sysCall.sysMonitorEnter(this.thread.octetMonitor);
//        while (response.EQ(NO_RESPONSE)) {
//          if (Dr.STATS) DrStats.heavyWaitsWhileAwaitingResponse.inc();
//          sysCall.sysMonitorWait(this.thread.octetMonitor);
//        }
//      }
//      this.heavyWaiting = false;
//      unblock();
//      sysCall.sysMonitorExit(this.thread.octetMonitor);
//    }
//  }


  private void reportTransition(Object md, Offset historyOffset, Word lrw,
      final int maxSpins, int tries, Word resp) {
    if (Dr.STATS) {
      if (tries <= maxSpins && requests.NE(EMPTY)) {
        DrStats.missedYieldOpportunityWhileAwaitingResponse.incBin(tries);
      }
      DrStats.triesAwaitingResponse.incBin(tries, resp != RACE_RESPONSE);
      if (resp.EQ(EXCL_RESPONSE)) {
        if (PRINT) {
          DrDebug.lock();
          DrDebug.twriteln("received EXCL transfer response");
          DrDebug.unlock();
        }
        //      if (VM.VerifyAssertions) VM._assert(Epoch.isReserved(ObjectShadow.getEffectiveReadWord(md, historyOffset)));
        //      AccessHistory.storeReadWord(md, historyOffset, RVMThread.getCurrentThread().getFibEpoch());

        if (Dr.STATS) {
          exclusiveResponsesReceivedInCurrentEpoch++;
        }
        //      return true;
      } else if (resp.EQ(SHARED_RESPONSE)) {
        if (PRINT) {
          DrDebug.lock();
          DrDebug.twriteln("received SHARED transfer response");
          DrDebug.unlock();
        }
        if (Dr.STATS) {
          sharedResponsesReceivedInCurrentEpoch++;
        }
        //      return true;
      } else {
        if (VM.VerifyAssertions) VM._assert(resp.EQ(RACE_RESPONSE));
        if (PRINT) {
          DrDebug.lock();
          DrDebug.twriteln("received RACE response");
          DrDebug.unlock();
        }
        //      return false;
      }
    }
  }

  /**
   * Send requests for ACKs to all threads in the read map.
   * NOTE: Call on requester's FRM.
   * 
   * @param md
   * @param historyOffset
   * @return bit vector of entries where acks were requested
   */
  protected Word requestAcks(Object md, Offset historyOffset, WordArray readers) {
    if (VM.VerifyAssertions) {
      VM._assert(this.thread == RVMThread.getCurrentThread());
      this.deflating = true;
      VM._assert(0 == this.getBlockDepth(), "Requests shoud be made only when unblocked.");
    }
    if (Dr.STATS) {
      DrStats.requestAck.inc();
    }
//    this.object = md;
//    this.offset = historyOffset;
    Magic.writeFloor();

    final int localTid = RVMThread.getCurrentThread().getDrID();
    Word awaitEntries = Word.zero();
    for (int remoteTid = 0; remoteTid < Epoch.MAX_THREADS; remoteTid++) {
      if (remoteTid != localTid) {
        final Word e = Dr.readers().get(readers, remoteTid);
        if (Epoch.isEpoch(e)) {
          awaitEntries = awaitEntries.or(requestAck(DrRuntime.getDrThread(remoteTid).drFibComm));
        } else if (Epoch.isMapRef(e)) {
          DrDebug.lock();
          DrDebug.twrite(); VM.sysWriteln("Found map ref ", e.toAddress(), " as entry in map ", ObjectReference.fromObject(readers).toAddress());
          DrDebug.twrite(); VM.sysWriteln("Outer map has type ", ObjectModel.getObjectType(ObjectReference.fromObject(readers).toObject()).getDescriptor());
          DrDebug.twrite(); VM.sysWriteln("Inner map has type ", ObjectModel.getObjectType(e.toAddress().toObjectReference().toObject()).getDescriptor());
          DrDebug.unlock();
          VM.sysFail("Found map as entry in epoch map.");
        } else if (!Epoch.isNone(e)) {
          DrDebug.lock();
          DrDebug.twriteln("Found non-epoch in readers when requesting acks.");
          Epoch.print(e);
          DrDebug.unlock();
        }
      }
    }
    return awaitEntries;
  }

  protected Word requestAck(final int remoteTid) {
    return requestAck(DrRuntime.getDrThread(remoteTid).drFibComm);
  }
  protected Word requestAck(final FibComm remote) {
    if (VM.VerifyAssertions) {
      VM._assert(0 == this.getBlockDepth(), "Requests shoud be made only when unblocked.");
    }
    if (PRINT) {
      DrDebug.lock();
      DrDebug.twrite();
      VM.sysWriteln("    ?ack @T", remote.thread.getDrID(), "     -> T", this.thread.getDrID());
      DrDebug.unlock();
    }

    int tries = 0;
    Word remoteReqs;
    // Lock the remote thread's queue.
    while (true) {
      if (CHECK_SPIN) checkSpin(++tries, MAX_WAIT_SPINS, "request ACK");
      remoteReqs = remote.prepareRequests();
      if (remoteReqs.EQ(remote.BLOCKED)) {
        // self-serve
        if (PRINT) {
          DrDebug.lock();
          DrDebug.twrite();
          VM.sysWriteln("    !ack @T", remote.thread.getDrID(), " [B] -> T", this.thread.getDrID());
          DrDebug.unlock();
        }
//        Word bv;
//        do {
//          bv = prepareResponse();
//          if (CHECK_SPIN) checkSpin(++tries, MAX_WAIT_SPINS, "Self-serve ACK");
//        } while (!attemptResponse(bv, bv.or(remote.BIT)));
        if (Dr.STATS) DrStats.requestAckSelfService.inc();
        return Word.zero();
      } else if (remoteReqs.EQ(LOCKED)) {
        // wait
      } else if (remote.attemptRequests(remoteReqs, LOCKED)){
//        if (PRINT) {
//          DrDebug.lock();
//          DrDebug.twrite(); VM.sysWriteln("!ack ", )
////          VM.sysWriteln("requested ACK from T", remote.thread.getDrID());
////          VM.sysWriteln("      remote.ackRequests ", remote.ackRequests, " -> ", remote.ackRequests.or(this.BIT));
////          VM.sysWriteln("      remoteReqs ", remoteReqs, " -> ", remoteReqs.or(this.BIT));
//          DrDebug.unlock();
//        }
        remote.ackRequests = remote.ackRequests.or(this.BIT);
        Magic.writeFloor();
        remote.setRequests(remoteReqs.or(this.BIT));
        // Tell remote thread to yield.
        remote.thread.takeYieldpoint = 1;
        return remote.BIT;
      }
      Magic.pause();
    }
  }
  
  /**
   * Wait until response = awaitedValue.
   * Used to await acks from all threads given by the bit set bv.
   * (In other words, wait for response == bv.)
   * 
   * @param awaitedValue - bit vector of threads that should ack.
   */
  @Unpreemptible
  protected void awaitResponse(Word awaitedValue) {
    if (VM.VerifyAssertions) {
      VM._assert(this.thread == RVMThread.getCurrentThread());
      VM._assert(0 == this.getBlockDepth(), "Requests shoud be made only when unblocked.");
    }
    if (!awaitedValue.and(this.BIT).isZero()) VM.sysFail("Awaiting yourself!");
    
    if (PRINT) {
      DrDebug.lock();
      DrDebug.twrite(); VM.sysWrite("  +await ");
      for (int tid = 0; tid < Epoch.MAX_THREADS; tid++) {
        if (!awaitedValue.and(Word.one().lsh(tid)).isZero()) {
          VM.sysWrite(" T", tid);
        }
      }
      VM.sysWriteln();
      DrDebug.unlock();
    }
    
    final int maxSpins = spinsBeforeYield();
    int tries = 0;    
    // Wait for all threads to turn on their bits in response.
    while (response.NE(awaitedValue)) {
      // Spin until a response appears.
      if (++tries > maxSpins) {
//        if (HEAVY_WAITS && tries > maxSpins + VM.octetWaitYieldCount) {
//            // Heavy wait.
//            awaitResponseHeavy(awaitedValue);
//            if (VM.VerifyAssertions) VM._assert(response.EQ(awaitedValue));
//        } else {
          if (CHECK_SPIN) checkSpin(tries, MAX_WAIT_SPINS, awaitedValue, "awaitAcks, yield-spin");
          respond();
          RVMThread.yieldNoHandshake();
//        }
      } else {
        if (CHECK_SPIN) checkSpin(tries, MAX_WAIT_SPINS, awaitedValue, "awaitAcks, pause-spin");
        Magic.pause();
      }
    }

    Magic.readCeiling();
    if (PRINT) {
      DrDebug.lock();
      DrDebug.twriteln("  =await");
      DrDebug.unlock();
    }
    if (VM.VerifyAssertions) {
      this.deflating = false;
    }
    this.response = NO_RESPONSE;
    this.responseRef = null;
    this.object = null;
    this.offset = Offset.zero();
    // this.del = null;
  }
//  protected void awaitAcks(Word awaitedAcks) {
//    if (VM.VerifyAssertions) VM._assert(this.thread == RVMThread.getCurrentThread());
//    
//    int tid = this.thread.getDrID();
//    int nthreads = DrRuntime.maxLiveDrThreads();
//    // Wait for all threads to turn on their bits in response.
//    for (int rtid = 0; rtid < nthreads; rtid++) {
//      Word rbit = Word.one().lsh(rtid);
//      if (rbit.and(awaitedAcks).isZero()) continue;
//      awaitAck(tid, rtid);
//    }
//
//    Magic.readCeiling();
//    if (PRINT) {
//      DrDebug.lock();
//      DrDebug.twriteln("received acks");
//      DrDebug.unlock();
//    }
//    if (VM.VerifyAssertions) {
//      this.deflating = false;
//    }
//  }
//  protected void awaitAck(int requesterTid, int responderTid) {
//    Address slot = responseSlot(requesterTid, responderTid);
//    final int maxSpins = spinsBeforeYield();
//    int tries = 0;
//    while (slot.prepareWord().NE(NO_RESPONSE)) {
//      // Spin until a response appears.
//      if (++tries > maxSpins) {
//        if (CHECK_SPIN) checkSpin(tries, MAX_WAIT_SPINS, Word.one(), "awaitAcks, yield-spin");
//        respond();
//        RVMThread.yieldNoHandshake();
//      } else {
//        if (CHECK_SPIN) checkSpin(tries, MAX_WAIT_SPINS, Word.one(), "awaitAcks, pause-spin");
//        Magic.pause();
//      }
//    } 
//    slot.store(NO_RESPONSE);
//  }

//  /**
//   * Use a heavy monitor to await the given response value.
//   * @param awaitedValue
//   */
//  private void awaitResponseHeavy(Word awaitedValue) {
//    if (Dr.STATS) DrStats.heavyResponses.inc();
//    this.heavyWaiting = true;
//    block();
//    if (response.NE(awaitedValue)) {
//      Magic.fence();
//      Magic.readCeiling();
//      sysCall.sysMonitorEnter(this.thread.octetMonitor);
//      while (response.NE(awaitedValue)) {
//        if (Dr.STATS) DrStats.heavyWaitsWhileAwaitingResponse.inc();
//        sysCall.sysMonitorWait(this.thread.octetMonitor);
//      }
//    }
//    this.heavyWaiting = false;
//    unblock();
//    sysCall.sysMonitorExit(this.thread.octetMonitor);
//  }
//  
  
  // HOOKS called by OWNER
  

  /**
   * Prepare for owner thread to block (including just before yield points).
   * Handle and block incoming request queue.
   */
  @SuppressWarnings("unused")
  @UninterruptibleNoWarn("May allocate read map.")
  @Inline
  public void block() {
    if (VM.VerifyAssertions) {
      VM._assert(Dr.COMMUNICATION);
      VM._assert(this.thread == RVMThread.getCurrentThread());
    }
    if (++blockDepth != 1) {
      if (Dr.STATS) DrStats.recursiveBlock.inc();
      return;
    }
    
    if (PRINT && DrRuntime.maxLiveDrThreads() > 2) {
      DrDebug.lock();
      DrDebug.twriteln("blocking");
      DrDebug.unlock();
    }
    if (VM.VerifyAssertions) {
      this.blockSourceMethodID = Magic.getCompiledMethodID(Magic.getFramePointer());
      this.blockSourceCallerMethodID = Magic.getCompiledMethodID(Magic.getCallerFramePointer(Magic.getFramePointer()));
      this.blockSourceCallerCallerMethodID = Magic.getCompiledMethodID(Magic.getCallerFramePointer(Magic.getCallerFramePointer(Magic.getFramePointer())));
    }

    if (handleQueue(true)) {
      if (Dr.STATS) DrStats.slowBlock.inc();
    } else {
      if (Dr.STATS) DrStats.fastBlock.inc();      
    }
  }
  
  /**
   * Handle incoming request queue.
   * @param blocking - block queue iff true
   * @return
   */
  @SuppressWarnings("unused")
  @Unpreemptible
  private boolean handleQueue(final boolean blocking) {
    if (VM.VerifyAssertions) {
      VM._assert(this.thread == RVMThread.getCurrentThread());
      VM._assert(blocking || this.getBlockDepth() == 0, "Handling queue when already blocked!");
    }
    
    int tries = 0;
    Word reqs;
    // Lock the queue.
    do {
      reqs = prepareRequests();
      while (reqs.EQ(LOCKED)) {
        if (VM.VerifyAssertions) {
          VM._assert(reqs.NE(this.BLOCKED));
          if (CHECK_SPIN) checkSpin(++tries, MAX_WAIT_SPINS, "lock to block wait");
        }
        Magic.pause();
        reqs = prepareRequests();
      }
      if (!blocking && reqs.EQ(this.EMPTY)) {
        return false;
      }
      if (VM.VerifyAssertions) {
        VM._assert(reqs.NE(this.BLOCKED));
        if (CHECK_SPIN) checkSpin(++tries, MAX_WAIT_SPINS, blocking ? "lock to block" : "lock to respond");
      }
    } while (!attemptRequests(reqs, (blocking && reqs.EQ(this.EMPTY)) ? this.BLOCKED : LOCKED));
    
    if (blocking && reqs.EQ(this.EMPTY)) {
      if (PRINT && DrRuntime.maxLiveDrThreads() > 2) {
        DrDebug.lock();
        DrDebug.twriteln("blocked (fast)");
        DrDebug.unlock();
      }
      return false;
    } else {
      processRequests(reqs.and(this.EMPTY.not()));

      Magic.writeFloor();
      if (PRINT && DrRuntime.maxLiveDrThreads() > 2) {
        DrDebug.lock();
        DrDebug.twriteln("blocked (slow)");
        DrDebug.unlock();
      }
      setRequests(blocking ? this.BLOCKED : this.EMPTY);

      return true;
    }

  }

  /**
   * Handle incoming request queue without blocking.
   */
  @Inline
  @Unpreemptible
  public void respond() {
    if (VM.VerifyAssertions) {
      VM._assert(Dr.COMMUNICATION);
      VM._assert(this.thread == RVMThread.getCurrentThread());
      assertNotBlocked();
    }

    if (handleQueue(false)) {
      if (Dr.STATS) DrStats.slowProtocolYield.inc();
    } else {
      if (Dr.STATS) DrStats.fastProtocolYield.inc();
    }

  }

  /**
   * Process requests from threads given by the bit vector reqs.
   * @param reqs
   */
  @Unpreemptible
  private void processRequests(final Word reqs) {
    if (VM.VerifyAssertions) {
      VM._assert(this.thread == RVMThread.getCurrentThread());
      VM._assert(this.requests == LOCKED);
      VM._assert(!reqs.isZero());
      VM._assert(reqs.and(this.EMPTY).isZero());
    }
    if (Dr.STATS) {
      int entries = 0;
      for (int i = 0; i < Epoch.MAX_THREADS; i++) {
        if (!reqs.and(Word.fromIntZeroExtend(1<<i)).isZero()) {
          entries++;
        }
      }
      DrStats.requestQueueSize.incBin(entries);
    }
    
    final Word ackReqs = ackRequests;
    if (ackReqs.isZero()) {
      // Part A: process intended EXCL->EXCL transitions.
      processTransitionRequests(reqs, false);
    } else {
      // Part A: process intended EXCL->EXCL transitions.
      processTransitionRequests(reqs.and(ackReqs.not()), false);
      // Part B: process intended SHARED->EXCL transitions.
      processAckRequests(ackReqs);
      ackRequests = Word.zero();
    }
  }
  
  /**
   * Prepare to unblock the owner thread (including just after yield points).
   * The queue must be either BLOCKED or LOCKED.
   * Reopens the queue to requests.
   */
  @SuppressWarnings("unused")
  @Inline
  public void unblock(boolean matchBlockSource) {
    if (VM.VerifyAssertions) {
      VM._assert(Dr.COMMUNICATION);
      VM._assert(this.thread == RVMThread.getCurrentThread());
    }

    if (blockDepth > 0) {
      if (--blockDepth > 0) {
        DrStats.recursiveUnblock.inc();
        return;
      } else {
        if (Dr.STATS) DrStats.nonRecursiveUnblock.inc();
        if (VM.VerifyAssertions && matchBlockSource) {
          VM._assert(VM.BuildWithBaseBootImageCompiler || Magic.getCompiledMethodID(Magic.getFramePointer()) == blockSourceMethodID);
          VM._assert(VM.BuildWithBaseBootImageCompiler || Magic.getCompiledMethodID(Magic.getCallerFramePointer(Magic.getFramePointer())) == blockSourceCallerMethodID);
          VM._assert(Magic.getCompiledMethodID(Magic.getCallerFramePointer(Magic.getCallerFramePointer(Magic.getFramePointer()))) == blockSourceCallerCallerMethodID);
        }
      }
    } else {
      VM.tsysWriteln("Warning unblocking at depth zero.");
      VM.sysFail("unblocking at depth zerp");
      return;
    }
    if (PRINT && DrRuntime.maxLiveDrThreads() > 2) {
      DrDebug.lock();
      DrDebug.twriteln("unblocking");
      DrDebug.unlock();
    }
    int tries = 0;
    Word old;
    do {
      old = prepareRequests();
      if (VM.VerifyAssertions) VM._assert(old.EQ(LOCKED) || old.EQ(this.BLOCKED));
      if (CHECK_SPIN) checkSpin(++tries, MAX_WAIT_SPINS, "unblock");
    } while (old.EQ(LOCKED) || !attemptRequests(this.BLOCKED, this.EMPTY));
    // BLOCKED -> EMPTY
    Magic.readCeiling();
    if (PRINT && DrRuntime.maxLiveDrThreads() > 2) {
      DrDebug.lock();
      DrDebug.twriteln("unblocked");
      DrDebug.unlock();
    }

  }
  
  
  
  // Block tracking

  @NoInline
  public static void assertNotBlocked() {
    if (VM.VerifyAssertions && Dr.COMMUNICATION && RVMThread.getCurrentThread().isDrThread()) {
      if (0 < RVMThread.getCurrentThread().drFibComm.getBlockDepth()) {
        DrDebug.lock();
        DrDebug.twrite(); VM.sysWrite("While blocked since ");
        VM.sysWriteln(CompiledMethods.getCompiledMethod(RVMThread.getCurrentThread().drFibComm.blockSourceMethodID).getMethod().getName());
        VM.sysWrite("  in ");
        VM.sysWriteln(CompiledMethods.getCompiledMethod(RVMThread.getCurrentThread().drFibComm.blockSourceCallerMethodID).getMethod().getName());
        VM.sysWrite("  in ");
        VM.sysWriteln(CompiledMethods.getCompiledMethod(RVMThread.getCurrentThread().drFibComm.blockSourceCallerCallerMethodID).getMethod().getName());
        DrDebug.twriteln("reached hook that should not execute while blocked, in:");
        RVMThread.dumpStack();
        DrDebug.unlock();
        VM.sysFail("nonblocked assertion failed");
      }
    }
  }
  
  @NoInline
  public static void assertValidRefs() {
    if (VM.VerifyAssertions && Dr.COMMUNICATION && RVMThread.getCurrentThread().isDrThread()) {
      final FibComm comm = RVMThread.getCurrentThread().drFibComm;
      DrDebug.lock();
      if (!MemoryManager.validRef(ObjectReference.fromObject(comm.next))) {
        DrDebug.twriteln("^^^ Invalid FibComm.next ref.");
      }
      if (!MemoryManager.validRef(ObjectReference.fromObject(comm.conflict))) {
        DrDebug.twriteln("^^^ Invalid FibComm.conflict ref.");
      }
      if (!MemoryManager.validRef(ObjectReference.fromObject(comm.responseRef))) {
        DrDebug.twriteln("^^^ Invalid FibComm.responseRef ref.");
      }
      if (!MemoryManager.validRef(ObjectReference.fromObject(comm.object))) {
        DrDebug.twriteln("^^^ Invalid FibComm.object ref.");
      }
      DrDebug.unlock();
    }
  }
  
  
  // called by RESPONDER  
  
  /**
   * Send a response to the receiver of this method.
   * @param r - response to send
   */
  private void placeResponse(Word r) {
    if (VM.VerifyAssertions) VM._assert(this.thread != RVMThread.getCurrentThread());
    Magic.writeFloor();
    if (Dr.config().drFirstRacePerLocation() && r.EQ(RACE_RESPONSE)) {
      FastTrack.freeze(this.object, this.offset);
    }
    this.response = r;
//    if (HEAVY_WAITS) {
//      Magic.fence();
//      if (this.heavyWaiting) {
//        sysCall.sysMonitorEnter(this.thread.octetMonitor);
//        sysCall.sysMonitorBroadcast(this.thread.octetMonitor);
//        sysCall.sysMonitorExit(this.thread.octetMonitor);
//      }
//    }
  }

  /**
   * Process transition requests.
   * @param xferReqs
   * @return return val meaningful only if delONLY
   */
  @Unpreemptible
  private Word processTransitionRequests(final Word xferReqs, boolean delOnly) {
    if (Dr.STATS) {
      DrStats.yieldsRespondExcl.inc();
    }
    if (PRINT) {
      DrDebug.lock();
      DrDebug.twrite();  VM.sysWrite("processTransitionRequests ", xferReqs, ": ");
      if (VM.VerifyAssertions) {
        for (int tid = 0; tid < Epoch.MAX_THREADS; tid++) {
          final Word bit = Word.one().lsh(tid);
          if (!xferReqs.and(bit).isZero()) {
            DrDebug.twrite(DrRuntime.getDrThread(tid)); VM.sysWrite("  ");
          }
        }
      }
      VM.sysWriteln();
      DrDebug.unlock();
    }
    
    if (VM.VerifyAssertions) {
      for (int tid = 0; tid < Epoch.MAX_THREADS; tid++) {
        final Word bit = Word.one().lsh(tid);
        if (!xferReqs.and(bit).isZero()) {
          final FibComm req = DrRuntime.getDrThread(tid).drFibComm;
          final Word currentReadWord = AccessHistory.loadReadWord(req.object, req.offset);
          if (!Epoch.sameTid(this.thread.getDrEpoch(), currentReadWord)) {
            DrDebug.lock();
            DrDebug.twrite();
            VM.sysWrite("Ownership of ", AccessHistory.address(req.object, req.offset), " changed since request enqueued by ");
            DrDebug.twrite(req.thread);  VM.sysWrite(":");  printTransition(req.targetEpoch, currentReadWord);
            VM.sysWriteln();
            RVMThread.dumpStack();
            DrDebug.unlock();
            VM._assert(false, "ownership changed unexpectedly.");
          }
        }
      }
    }
    
    Word ret = xferReqs;
    // Stage 1.
    // Resolve any easy requests, such as concurrent write requests (obvious races),
    // stale requests (owner has changed since request issued), or concurrent read requests
    // that will inflate to read-shared.
    // The remaining unprocessed queue contains all those requests that are true
    // EXCL->EXCL transitions on locations exclusive to (and not in deflation by) this.owner.
    FibComm pending = null;
    for (int tid = 0; tid < Epoch.MAX_THREADS; tid++) {
      final Word bit = Word.one().lsh(tid);
      if (!xferReqs.and(bit).isZero()) {
        final FibComm x = DrRuntime.getDrThread(tid).drFibComm;
        if (Epoch.isAlt(AccessHistory.loadReadWord(x.object, x.offset))) {
          VM.sysFail("should not be alt");
        }
        pending = checkAndAccumulate(x, pending);
        ret = ret.and(bit.not());
        if (Dr.STATS) requestsReceivedInCurrentEpoch++;
      }
    }
    
    // Stage 2.
    // Walk through remaining transition requests in received order.
    int tries = 0;
    while (pending != null) {
      FibComm nextPending = pending.next;
      pending.next = null;
      if (CHECK_SPIN) checkSpin(++tries, Epoch.MAX_THREADS, "processRequests Stage 2");
      if (VM.VerifyAssertions) VM._assert(!pending.isWrite);
      if (pending.responseRef == null) {
        // If not inflated, send an EXCL response.
        // OLD: AccessHistory.storeReadReserved(list.object, list.offset);
        reportRespondExclRead(pending);
        
        if (Dr.config().fibAdaptiveCas()
            && FibCas.threshold(pending.object, pending.offset)) {

          if (FibCas.PRINT) {
            DrDebug.lock();
            DrDebug.twrite();
            VM.sysWriteln("  !cas _ ", AccessHistory.address(pending.object, pending.offset), " T", pending.thread.getDrID());
            DrDebug.unlock();
          }

         // Needs to be switched to CAS
          Magic.writeFloor();
          AccessHistory.storeReadWord(pending.object, pending.offset, Epoch.asAlt(pending.thread.getDrEpoch()));
        } else {
          // Normal response
          Magic.writeFloor();
          AccessHistory.storeReadWord(pending.object, pending.offset, pending.thread.getDrEpoch());
        }
        Magic.writeFloor();
        pending.placeResponse(EXCL_RESPONSE);
      } else {
        // If inflated, send a SHARED response.
        final WordArray readers = pending.responseRef;
        pending.responseRef = null;
        final Object obj = pending.object;
        final Offset off = pending.offset;
        for (FibComm reqConflict = null, req = pending; req != null; req = reqConflict) {
          reqConflict = req.conflict;
          Dr.readers().set(readers, req.thread.getDrEpoch());
        }
        Magic.writeFloor();
        
        // Release lock on obj/off by publishing readers.
        Dr.readers().install(obj, off, readers);
        Magic.writeFloor();
        
        // Respond to each new reader.
        for (FibComm reqConflict = null, req = pending; req != null; req = reqConflict) {
          reportRespondShare(pending, req, obj, off);
          reqConflict = req.conflict;
          req.conflict = null;
          req.placeResponse(SHARED_RESPONSE);
        }
      }

      pending = nextPending;
      
    }
    
    if (PRINT) {
      DrDebug.lock();
      DrDebug.twriteln("processTransitionRequests done");
      DrDebug.unlock();
    }

    return ret;
  }

  private void reportRespondShare(FibComm pending, FibComm req, Object obj, Offset off) {
    if (VM.VerifyAssertions) VM._assert(obj == req.object && off == req.offset);
    if (PRINT) {
      DrDebug.lock();
      DrDebug.twrite();
      VM.sysWriteln("responded ", AccessHistory.address(pending.object, pending.offset), " shared to T", req.thread.getDrID());
      DrDebug.unlock();
    }
    if (Dr.STATS) {
      sharedResponsesSentInCurrentEpoch++;
      DrStats.respondShare.inc();
    }
  }

  private void reportRespondExclRead(FibComm pending) {
    if (VM.VerifyAssertions) VM._assert(pending.conflict == null);
    if (PRINT) {
      DrDebug.lock();
      DrDebug.twrite();
      VM.sysWriteln("responded ", AccessHistory.address(pending.object, pending.offset), " transferred to T", pending.thread.getDrID());
      DrDebug.unlock();
    }
    if (Dr.STATS) {
      this.exclusiveResponsesSentInCurrentEpoch++;
      DrStats.respondExclRead.inc();
    }
  }

  // FIXME put stats counters in.
  // Note: this method's running time is quadratic in queue size.
  /**
   * Do checks for request x and either respond immediately
   * or accumulate it on the list of pending responses.
   * @param req
   * @param pendingResponses - Secondary queue of deferred read requests to reprocess
   * after handling all writes. NO WRITE REQUESTS should be added to this queue.
   * @return - new list of pending responses
   */
  @SuppressWarnings("unused")
  @Unpreemptible
  private FibComm checkAndAccumulate(final FibComm req, final FibComm pendingResponses) {
    if (VM.VerifyAssertions) {
      VM._assert(this.thread == RVMThread.getCurrentThread());
    }
    
    final Object reqObject = req.object;
    final Offset reqOffset = req.offset;
    final Word reqEpoch = req.thread.getDrEpoch();
    final WordArray reqVC = req.thread.drThreadVC;

    int tries = 0;
    FibComm pending = pendingResponses;
    while (pending != null) {
      if (CHECK_SPIN) checkSpin(++tries, Epoch.MAX_THREADS, "checkAndAccumulate");
      if (VM.VerifyAssertions) {
        // Note: if y is in the list, then y.history() is exclusive to this.owner.
        final Word lrw = AccessHistory.loadReadWord(pending.object, pending.offset);

        VM._assert(Epoch.sameTid(this.thread.getDrEpoch(), lrw));
        VM._assert(!pending.isWrite);
      }
      if (reqObject == pending.object && reqOffset == pending.offset) {
        if (req.isWrite) {
          if (PRINT) {
            DrDebug.lock();
            DrDebug.twrite();
            VM.sysWriteln("responded WRITE ",
                AccessHistory.address(reqObject, reqOffset), " queue conflict RACE T", req.thread.getDrID());
            DrDebug.unlock();
          }
          req.placeResponse(RACE_RESPONSE);

          if (Dr.STATS) {
            DrStats.respondRace.inc();
          }
          return pendingResponses;
        } else if (pending.responseRef != null) { // WAS THIS SET?  Yes, when actually responding, it is cleared.
          // Another concurrent request in this batch has moved it to shared state.
          if (FibFastTrack.readShared(reqObject, reqOffset, pending.responseRef, reqEpoch)) {
            // If safe:
            // Pool this request with the pending request that placed it in shared state.
            req.conflict = pending.conflict;
            pending.conflict = req;

            if (PRINT) {
              DrDebug.lock();
              DrDebug.twrite();
              VM.sysWriteln("queued conflict READ ",
                  AccessHistory.address(reqObject, reqOffset), " already shared OK T", req.thread.getDrID());
              DrDebug.unlock();
            }
            if (Dr.STATS) {
              DrStats.respondShared.inc();
            }
            return pendingResponses;
          } else {
            if (PRINT) {
              DrDebug.lock();
              DrDebug.twrite();
              VM.sysWriteln("responded READ RACE ",
                  AccessHistory.address(reqObject, reqOffset), " queue conflict alread sharedT", req.thread.getDrID());
              DrDebug.unlock();
            }
            req.placeResponse(RACE_RESPONSE);
            if (Dr.STATS) {
              DrStats.respondRace.inc();
            }
            return pendingResponses;
          }
        } else if (VC.epochHB(AccessHistory.loadWriteWord(reqObject, reqOffset), reqVC)) {
          // Still exclusive, but this request is a concurrent read.
          if (PRINT) {
            DrDebug.lock();
            DrDebug.twrite();
            VM.sysWriteln("queue conflict READ ",
                AccessHistory.address(reqObject, reqOffset), " inflating OK T", req.thread.getDrID());
            DrDebug.unlock();
          }
          final WordArray readers = Dr.readers().create();
          Dr.readers().set(readers, pending.thread.getDrEpoch());
          Dr.readers().set(readers, Epoch.asDefault(reqEpoch));
          pending.responseRef = readers;
          pending.conflict = req;
          
          if (PRINT) {
            DrDebug.lock();
            DrDebug.twrite();
            VM.sysWrite("queue conflict READ ", AccessHistory.address(reqObject, reqOffset), " inflated OK T", req.thread.getDrID());
            VM.sysWriteln(" and T", pending.thread.getDrID());
            DrDebug.unlock();
          }
          if (Dr.STATS) {
            DrStats.respondShared.inc();
          }

          return pendingResponses;
        } else {
          if (PRINT) {
            DrDebug.lock();
            DrDebug.twrite();
            VM.sysWriteln("responded READ RACE exclusive ",
                AccessHistory.address(reqObject, reqOffset), " T", req.thread.getDrID());
            DrDebug.unlock();
          }
          req.placeResponse(RACE_RESPONSE);
          if (Dr.STATS) {
            DrStats.respondRace.inc();
          }
          return pendingResponses;
        }
      }

      // Check next item.
      pending = pending.next;
    }
    // Reaching here implies this request does not conflict with any others in the queue.
    // Do a normal race check.
    final Word currentReadWord = AccessHistory.loadReadWord(reqObject, reqOffset);
    if (Epoch.sameTid(this.thread.getDrEpoch(), currentReadWord)) {
      // If ownership still belongs to the responding thread...
      if (VC.epochHB(currentReadWord, reqVC)) {
        // If totally ordered...
        if (req.isWrite) {
          // Respond immediately to writes.
          if (PRINT) {
            DrDebug.lock();
            DrDebug.twrite();
            VM.sysWriteln("responded WRITE exclusive OK ",
                AccessHistory.address(reqObject, reqOffset), " T", req.thread.getDrID());
            DrDebug.unlock();
          }
          if (Dr.config().drFirstRacePerLocation()) {
            FastTrack.freeze(reqObject, reqOffset);
          } else {
            AccessHistory.storeWriteWord(reqObject, reqOffset, reqEpoch);
            // AccessHistory.storeReadReserved(xObject, xOffset);
            boolean shouldCas = Dr.config().fibAdaptiveCas() && FibCas.threshold(reqObject, reqOffset);
            AccessHistory.storeReadWord(reqObject, reqOffset, shouldCas ? Epoch.asAlt(reqEpoch) : reqEpoch);
          }
          
          if (FibCas.PRINT && Dr.config().fibAdaptiveCas() && FibCas.threshold(reqObject, reqOffset)) {
            DrDebug.lock();
            DrDebug.twrite();
            VM.sysWriteln("  !cas W ", AccessHistory.address(reqObject, reqOffset), " T", req.thread.getDrID());
            DrDebug.unlock();
          }

          Magic.writeFloor();
          req.placeResponse(EXCL_RESPONSE);

          if (Dr.STATS) {
            exclusiveResponsesSentInCurrentEpoch++;
            DrStats.respondExclWrite.inc();
          }
          return pendingResponses;
        } else { // !req.isWrite
          // Defer read responses...
          if ((Dr.config().fibPreemptiveReadShare() || (Dr.config().fibStaticReadShare() && reqObject == null))
              && Epoch.sameEpoch(AccessHistory.loadWriteWord(reqObject, reqOffset), currentReadWord)) {
            // If last read is in a different thread than this read
            // and in a different epoch than the last write, then
            // preemptively move to read-shared even if reads are ordered.
            // TODO: Since they are ordered, only publish this one?
            respondReadShare(req, reqEpoch);
            if (Dr.STATS) DrStats.preemptiveReadShare.inc();
            if (Dr.STATS) DrStats.preemptiveReadShareRemote.inc();

          }
          req.next = pendingResponses;
          return req;
        }
      } else if (!req.isWrite && VC.epochHB(AccessHistory.loadWriteWord(reqObject, reqOffset), reqVC)) {
        // If newly concurrent read, inflate to shared read map, but defer response.
        respondReadShare(req, currentReadWord, reqEpoch);
        req.next = pendingResponses;
        return req;
      } else {
        // Otherwise, this is an unordered write or a read that is unordered with a write.
        if (Dr.STATS) {
          DrStats.respondRace.inc();
        }
        if (PRINT) {
          DrDebug.lock();
          DrDebug.twrite();
          VM.sysWriteln("responding RACE via alg ",
              AccessHistory.address(reqObject, reqOffset), " T", req.thread.getDrID());
          DrDebug.unlock();
        }
        req.placeResponse(RACE_RESPONSE);
        return pendingResponses;
      }
    } else {
      // Race with write that already escaped the list.
      if (Dr.STATS) {
        DrStats.respondRace.inc();
      }
      if (PRINT) {
        DrDebug.lock();
        DrDebug.twrite();
        VM.sysWriteln("responding READ protocol RACE with write that escaped list ",
            AccessHistory.address(reqObject, reqOffset), " T", req.thread.getDrID());
        DrDebug.unlock();
      }
      req.placeResponse(RACE_RESPONSE);
      return pendingResponses;
    }
  }

  /**
   * Create a new read map, inserting the given readEpoch, and save it as the responseRef
   * for the given request.
   * 
   * @param request
   * @param readEpoch
   * @return the read map
   */
  private WordArray respondReadShare(final FibComm request, final Word readEpoch) {
    if (PRINT) {
      DrDebug.lock();
      DrDebug.twrite();
      VM.sysWriteln("processing READ ",
          AccessHistory.address(request.object, request.offset), " inflating from pre-yield shared OK T", request.thread.getDrID());
      DrDebug.unlock();
    }
    if (Dr.STATS) {
      DrStats.respondShare.inc();
    }
    final WordArray readers = Dr.readers().create();
    Dr.readers().set(readers, readEpoch);
    request.responseRef = readers;
    return readers;
  }
  /**
   * Create a new read map, inserting the two given epochs, and save it as the responseRef
   * for the given request.
   * 
   * @param request
   * @param readEpoch1
   * @param readEpoch2
   */
  private void respondReadShare(final FibComm request, final Word readEpoch1, final Word readEpoch2) {
    Dr.readers().set(respondReadShare(request, readEpoch1), readEpoch2);
  }
  
  /**
   * Respond to ack requests.
   * Note it is OK to respond to an ACK while waiting for a request to finish,
   * even if that request involves setting an entry in a read set.
   * 
   * @param ackReqs
   */
  private void processAckRequests(final Word ackReqs) { //, final boolean isRequesting) {
    if (Dr.STATS) {
      DrStats.yieldsRespondAck.inc();
    }
    if (PRINT) {
      DrDebug.lock();
      DrDebug.twrite();  VM.sysWriteln("processAcks ", ackReqs);
      DrDebug.unlock();
    }
    
    for (int tid = 0; tid < Epoch.MAX_THREADS; tid++) {
      final Word bit = Word.one().lsh(tid);
      if (!ackReqs.and(bit).isZero()) {
        final FibComm req = DrRuntime.getDrThread(tid).drFibComm;
        Word bv;
        int tries = 0;
        do {
          bv = req.prepareResponse();
          if (CHECK_SPIN) checkSpin(++tries, MAX_WAIT_SPINS, "ack");
        } while (!req.attemptResponse(bv, bv.or(this.BIT)));
        if (PRINT) {
          DrDebug.lock();
          DrDebug.twrite();
          VM.sysWriteln("    !ack @T", this.thread.getDrID(), " [R] -> T", req.thread.getDrID());
          DrDebug.unlock();
        }
        if (Dr.STATS) {
          DrStats.ack.inc();
        }
      }
    }
  }
  
  private int requestsSentInCurrentEpoch = 0;
  private int requestsReceivedInCurrentEpoch = 0;
  private int exclusiveResponsesSentInCurrentEpoch = 0;
  private int exclusiveResponsesReceivedInCurrentEpoch = 0;
  private int sharedResponsesSentInCurrentEpoch = 0;
  private int sharedResponsesReceivedInCurrentEpoch = 0;
  
  public void reportNewEpoch() {
    if (Dr.STATS) {
      DrStats.percentDistinctEpochRequestsPerEpoch.incBin(requestsSentInCurrentEpoch == 0 ? 0 : (100 * (destEpochCursor + 1)) / requestsSentInCurrentEpoch);

      DrStats.requestsSentPerEpoch.incBin(requestsSentInCurrentEpoch);
      requestsSentInCurrentEpoch = 0;
      DrStats.requestsReceivedPerEpoch.incBin(requestsReceivedInCurrentEpoch);
      requestsReceivedInCurrentEpoch = 0;
      DrStats.exclusiveResponsesSentPerEpoch.incBin(exclusiveResponsesSentInCurrentEpoch);
      exclusiveResponsesSentInCurrentEpoch = 0;
      DrStats.exclusiveResponsesReceivedPerEpoch.incBin(exclusiveResponsesReceivedInCurrentEpoch);
      exclusiveResponsesReceivedInCurrentEpoch = 0;
      DrStats.sharedResponsesSentPerEpoch.incBin(sharedResponsesSentInCurrentEpoch);
      sharedResponsesSentInCurrentEpoch = 0;
      DrStats.sharedResponsesReceivedPerEpoch.incBin(sharedResponsesReceivedInCurrentEpoch);
      sharedResponsesReceivedInCurrentEpoch = 0;
            
      DrStats.distinctEpochsRequestedPerEpoch.incBin(destEpochCursor + 1);
      destEpochCursor = -1;
      if (destEpochOverflow) {
        DrStats.distinctEpochsOverflow.inc();
        destEpochOverflow = false;
      }
      DrStats.distinctThreadsRequestedPerEpoch.incBin(destThreads);
      destThreads = 0;
    }
  }
  
  private static final int MAX_DEST_EPOCHS = 1024;
  private final WordArray destEpochs = Dr.STATS ? WordArray.create(MAX_DEST_EPOCHS) : null;
  private int destEpochCursor = -1;
  private boolean destEpochOverflow = false;
  private int destThreads = 0;
  
  private void recordDest(Word e) {
    boolean foundThread = false;
    for (int i = destEpochCursor; i >= 0; i--) {
      if (destEpochs.get(i).EQ(e)) return;
      else if (Epoch.sameTid(destEpochs.get(i), e)) {
        foundThread = true;
      }
    }
    if (++destEpochCursor < MAX_DEST_EPOCHS) {
      destEpochs.set(destEpochCursor, e);
      if (!foundThread) {
        destThreads++;
      }
    } else {
      destEpochOverflow = true;
    }
  }

}
