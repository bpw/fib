package org.jikesrvm.octet;

import static org.jikesrvm.runtime.SysCall.sysCall;

import org.jikesrvm.VM;
import org.jikesrvm.runtime.Entrypoints;
import org.jikesrvm.runtime.Magic;
import org.jikesrvm.runtime.Time;
import org.jikesrvm.scheduler.RVMThread;
import org.vmmagic.pragma.Entrypoint;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.NoCheckStore;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.ObjectReference;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;

/** Octet: send, receive, and respond to communication requests */
@Uninterruptible
public final class Communication extends OctetState {

  // Several methods to check for communication requests or block communication requests:
  
  @Entrypoint
  static final void blockCommunicationRequests() {
    Stats.blockCommEntrypoint.inc();
    blockCommunicationRequests(true);
  }

  @Inline
  public static final void blockCommunicationRequests(boolean mayBeRecursive) {
    if (RVMThread.getCurrentThread().isOctetThread()) {
      
      int depth = RVMThread.getCurrentThread().octetBlockedCommunicationRequestDepth;
      // Octet: LATER: this assertion isn't always true, at least for one client (RR):
      // a read/write barrier will execute when RR logs are being recorded,
      // at which point the thread is already "permanently blocked" because it is dying
      //if (VM.VerifyAssertions) { VM._assert(mayBeRecursive || depth == 0); }
      RVMThread.getCurrentThread().octetBlockedCommunicationRequestDepth = depth + 1;
      if (depth == 0) {
        checkForCommunicationRequests(true);
      }
    }
  }
  
  public static final void blockCommunicationRequestsAndMakeDead() {
    RVMThread myThread = RVMThread.getCurrentThread();
    if (VM.VerifyAssertions) {
      VM._assert(myThread.isOctetThread());
      VM._assert(RVMThread.getCurrentThread().octetBlockedCommunicationRequestDepth == 0);
    }
    RVMThread.getCurrentThread().octetBlockedCommunicationRequestDepth = 1;
    checkForCommunicationRequests(true, true);
  }
  
  @Entrypoint
  public static final void unblockCommunicationRequests() {
    RVMThread myThread = RVMThread.getCurrentThread();
    if (myThread.isOctetThread()) {
      int depth = myThread.octetBlockedCommunicationRequestDepth;
      if (VM.VerifyAssertions) { VM._assert(depth > 0); }
      myThread.octetBlockedCommunicationRequestDepth = depth - 1;
      if (depth == 1) {
        do {
          Word oldRequests = ObjectReference.fromObject(myThread).toAddress().prepareWord(Entrypoints.octetRequestsField.getOffset());
          if (VM.VerifyAssertions) { VM._assert(CommunicationQueue.isBlockedAssertNotDead(oldRequests)); }
          // If the thread is held by at least one thread, yield since we may have to wait for a while.
          if (Octet.getClientAnalysis().useHoldingState() && CommunicationQueue.getHoldCount(oldRequests) > 0) {
            RVMThread.yieldNoHandshake();
          // Otherwise, let's try to unblock.
          } else {
            Word newRequests = CommunicationQueue.unblock(oldRequests);
            boolean result = ObjectReference.fromObject(myThread).toAddress().attempt(oldRequests, newRequests, Entrypoints.octetRequestsField.getOffset());
            if (result) {
              // Let the client analysis handle leaving the blocked state.
              if (Octet.getClientAnalysis().shouldExecuteSlowPathHooks(false)) {
                Octet.getClientAnalysis().handleEventsAfterUnblockCommunication();
              }
              // Octet: TODO: Mike guesses that we do not need both hooks -- probably just the handleEventsAfterUnblockIfRequestsReceived() hook
              int newRequestCounter = CommunicationQueue.getCounter(newRequests);
              if (requestSeen(myThread.octetResponses, newRequestCounter)) {
              //if (newRequestCounter > myThread.octetResponses) {
                // Let the client analysis handle leaving the blocked state.
                if (Octet.getClientAnalysis().shouldExecuteSlowPathHooks(false)) {
                  Octet.getClientAnalysis().handleEventsAfterUnblockIfRequestsReceivedWhileBlocked(newRequestCounter);
                }
                myThread.octetResponses = newRequestCounter;
              }
              break;
            } else {
              // The unblock should only be able to fail if the holding state is enabled.
              if (VM.VerifyAssertions) { Octet.getClientAnalysis().useHoldingState(); }
              Magic.pause();
            }
          }
        } while (true);
      }
    }
  }

  static final void checkForCommunicationRequests(boolean blockRequests) {
    checkForCommunicationRequests(blockRequests, false);
  }
  
  @Inline
  private static final void checkForCommunicationRequests(boolean blockRequests, boolean makeBlockedAndDead) {
    if (VM.VerifyAssertions) { VM._assert(!makeBlockedAndDead || blockRequests); }
    RVMThread myThread = RVMThread.getCurrentThread();
    Word oldRequests;
    while (true) {    
      oldRequests = ObjectReference.fromObject(myThread).toAddress().prepareWord(Entrypoints.octetRequestsField.getOffset());
      // There's a race here, and this thread will only see the queue element if it wins the race.
      // If it loses the race, it'll see the queue element on some later call to checkForCommunicationRequests, which is fine.
      if (blockRequests ||
          requestSeen(myThread.octetResponses, CommunicationQueue.getCounter(oldRequests))){
          //CommunicationQueue.getCounter(oldRequests) > myThread.octetResponses) {
        Word newRequests;
        if (blockRequests) {
          if (makeBlockedAndDead) {
            newRequests = CommunicationQueue.makeDead();
          } else {
            newRequests = CommunicationQueue.block(oldRequests);
          }
        } else {
          if (Octet.getClientAnalysis().needsCommunicationQueue()) {
            newRequests = CommunicationQueue.clearRequests(oldRequests);
          } else {
            // If the client analysis doesn't use the queues, there's no need to change the value.
            if (VM.VerifyAssertions) { VM._assert(CommunicationQueue.getThreadID(oldRequests) == 0); }
            break;
          }
        }

        // Before responding, atomically put the request queue in a local variable
        if (VM.VerifyAssertions) { VM._assert(oldRequests.NE(newRequests)); }
        boolean result = ObjectReference.fromObject(myThread).toAddress().attempt(oldRequests, newRequests, Entrypoints.octetRequestsField.getOffset());
        if (result) {
          // Recursively handle requests, unless currently (or always) disabled.
          if (Octet.getClientAnalysis().responseShouldCheckCommunicationQueue()) {
            int firstThreadID = CommunicationQueue.getThreadID(oldRequests);
            if (firstThreadID != 0) {
              handleRequests(firstThreadID);
            }
          }
          break;
        }
        Magic.pause();
      } else {
        break;
      }
    }
    
    // Octet: LATER: if we don't always do the monitor enter-broadcast-exit, then we'll need fences of some sort so the store doesn't move above the load
    //Magic.sfence(); // don't let the store move earlier -- probably needs to be an mfence since we also don't want the load above to move down

    // Octet: LATER: Can we avoid always broadcasting on a monitor?
    // This thread would need some way of knowing that the requesting thread has started waiting on this thread's monitor.

    // Respond to all requests (if any) in one step
    // Octet: TODO: As Minjia pointed out, it might be better in some cases
    // to respond after handling each queue element.
    if (requestSeen(myThread.octetResponses, CommunicationQueue.getCounter(oldRequests))) {
    //if (CommunicationQueue.getCounter(oldRequests) > myThread.octetResponses) {
      sysCall.sysMonitorEnter(myThread.octetMonitor);
      Magic.writeFloor(); // so everything before the store below will be visible to other threads (that use an lfence, of course)
      int oldOctetResponses = myThread.octetResponses;
      myThread.octetResponses = CommunicationQueue.getCounter(oldRequests);
      sysCall.sysMonitorBroadcast(myThread.octetMonitor);
      sysCall.sysMonitorExit(myThread.octetMonitor);
      // Let the client analysis handle the response.
      if (Octet.getClientAnalysis().shouldExecuteSlowPathHooks(false)) {
        Octet.getClientAnalysis().handleResponsesUpdated(oldOctetResponses, myThread.octetResponses);
      }
    }
  }

  static final void handleRequests(int threadID) {
    if (VM.VerifyAssertions) { VM._assert(Octet.getClientAnalysis().needsCommunicationQueue() && Octet.getClientAnalysis().responseShouldCheckCommunicationQueue()); }
    // First do recursion, so the queue, which is really a stack, is processed in reverse order.
    RVMThread myThread = RVMThread.getCurrentThread();
    RVMThread requestingThread = RVMThread.threadBySlot[threadID];
    int nextThreadID = requestingThread.nextRequestQueueThread[myThread.threadSlot];
    if (nextThreadID != 0) {
      handleRequests(nextThreadID);
    }
    // Now let the client analysis handle the response.
    if (Octet.getClientAnalysis().shouldExecuteSlowPathHooks(false)) {
      Octet.getClientAnalysis().handleRequestOnRespondingThread(requestingThread);
    }
  }
  
  /** Communicate with other thread(s) so we can be sure they'll
      see we put the object in the intermediate state */
  static final void communicate(Address baseAddr, Offset offset, Word oldMetadata, Word newState, int fieldOffset, int siteID) {
    
    // Octet: TODO: need to add a call to a hook somewhere for client analyses to set information to send to the other thread (like the conflicting object)? 

    // first send communication to threads (or identify that a thread is blocked and doesn't need to stop and communicate)
    RVMThread myThread = RVMThread.getCurrentThread();
    int numThreadsSentRequest = 0;
    if (isExclState(oldMetadata)) {
      RVMThread remoteThread = getThreadFromExclusive(oldMetadata);
      if (VM.VerifyAssertions) { VM._assert(remoteThread != myThread); }
      if (isValidOtherThread(remoteThread, myThread)) {
        numThreadsSentRequest = sendRequest(baseAddr, offset, remoteThread, numThreadsSentRequest, oldMetadata, newState, fieldOffset, siteID);
      }
    } else {
      if (VM.VerifyAssertions) { VM._assert(getState(oldMetadata).EQ(READ_SHARED)); }
      if (VM.VerifyAssertions) { VM._assert(isReadSharedPossiblyUnfenced(oldMetadata)); }
      // Octet: TODO: wrong way to loop; might miss a thread that gets a new number
      // other than that issue, i don't think we need synchronization on this way of grabbing all the threads because we don't care about
      // newly created threads (because they're guaranteed to see the intermediate state) or threads that have recently died (because they go into the blocked state permanently)
      for (int i = 0; i < RVMThread.numThreads; i++) {
        RVMThread remoteThread = RVMThread.threads[i];
        if (isValidOtherThread(remoteThread, myThread) &&
            // Octet: TODO: this condition seems necessary in case a new thread has been created but
            // can't get started (e.g., if a GC gets triggered)
            remoteThread.getExecStatus() != RVMThread.NEW) {
          // check the read-shared counter to avoid unnecessary communication
          // Octet: LATER: is there a dangerous data race here? -- i think so, so i'm moving the sendRequest() call outside of the conditional
          // We could make this safe with more synchronization (all accesses to octetReadSharedCounter have to CAS)
          int oldNumThreads = numThreadsSentRequest;
          numThreadsSentRequest = sendRequest(baseAddr, offset, remoteThread, numThreadsSentRequest, oldMetadata, newState, fieldOffset, siteID);
          if (remoteThread.octetReadSharedCounter.LE(oldMetadata)) {
            // moved sendRequest() above because of likely dangerous race
          } else {
            Stats.readSharedCounterAvoidsSendRequestCall.inc();
            if (oldNumThreads != numThreadsSentRequest) {
              Stats.readSharedCounterAvoidsActualSendRequest.inc();
            }
          }
        }
      }
    }
    Stats.threadsWaitedFor.incBin(numThreadsSentRequest);    
    
    receiveResponses(numThreadsSentRequest, oldMetadata, newState);
    
    /*
    // Now receive responses (only if at least one thread sent a request)
    if (numThreadsSentRequest > 0 && !Octet.noCommunuicationWait()) {
      long startTime = (Octet.stats() || VM.VerifyAssertions) ? Time.nanoTime() : 0;
      int iter;
      boolean stillWaiting;
      for (iter = 0; (stillWaiting = myThread.octetThreadsWaitedFor > 0) && iter < Octet.waitSpinCount(); iter++) {
        Magic.pause();
      }
      if (stillWaiting) {
        int stopIter = iter + Octet.waitYieldCount();
        for (; (stillWaiting = myThread.octetThreadsWaitedFor > 0) && iter < stopIter; iter++) {
          if (Octet.doYields()) {
            RVMThread.yield();
          }
        }
        if (stillWaiting) {
          // now we'll try waiting via pthreads; to make sure this happens atomically, let's switch to using negative numbers
          sysCall.sysMonitorEnter(myThread.octetMonitor);
          boolean result;
          do {
            int threadsWaitedFor = myThread.octetThreadsWaitedFor;
            if (VM.VerifyAssertions) { VM._assert(threadsWaitedFor >= 0); }
            if (threadsWaitedFor == 0) {
              stillWaiting = false;
              break;
            }
            result = Synchronization.tryCompareAndSwap(myThread, Entrypoints.octetThreadsWaitedForField.getOffset(), threadsWaitedFor, -threadsWaitedFor);
          } while (!result);

          if (stillWaiting) {
            do {
              iter++;
              if (VM.VerifyAssertions) {
                // wait for 100 milliseconds, then check for timeout
                sysCall.sysMonitorTimedWaitAbsolute(myThread.octetMonitor, Time.nanoTime() + 100*1000*1000);
                checkForResponseTimeout(threadsSentRequest, theRemoteThread, startTime, 90*1000*1000);
              } else {
                sysCall.sysMonitorWait(myThread.octetMonitor);
              }
            } while (myThread.octetThreadsWaitedFor < 0);
          }
          if (VM.VerifyAssertions) { VM._assert(myThread.octetThreadsWaitedFor == 0); }
          sysCall.sysMonitorExit(myThread.octetMonitor);
        }
      }
      Stats.logTimeCommunicateRequests.incTime(startTime);
      Stats.waitIter.incBin(iter);
    }
    */
  }

  @NoCheckStore // due to storing to RVMThread.octetRespondingThreads[i]
  static final void receiveResponses(int numThreadsSentRequest, Word oldMetadata, Word newState) {
    if (numThreadsSentRequest > 0 && !Octet.getConfig().noWaitForCommunication()) {
      RVMThread myThread = RVMThread.getCurrentThread();
      RVMThread[] respondingThreads = myThread.octetRespondingThreads;
      int[] respondingThreadCounters = myThread.octetRespondingThreadCounters;
      long startTime = (Octet.getConfig().stats() || VM.VerifyAssertions) ? Time.nanoTime() : 0;
      int iter;
      for (iter = 0; ; iter++) {
        // check all threads
        boolean stillWaiting = false;
        for (int i = 0; i < numThreadsSentRequest; i++) {
          RVMThread respondingThread = respondingThreads[i];
          if (respondingThread != null) {
            int respondingThreadCounter = respondingThreadCounters[i];
            if (responseReceived(respondingThread.octetResponses, respondingThreadCounter)) { 
            //if (respondingThread.octetResponses >= respondingThreadCounter) {
              // We received a response
              respondingThreads[i] = null;

              // Let the client analysis handle receiving the response.
              Magic.readCeiling(); // Use a load fence to ensure happens-before edge from other thread's write to octetResponse to this point.
              if (Octet.getClientAnalysis().shouldExecuteSlowPathHooks(true)) {
                Octet.getClientAnalysis().handleReceivedResponse(respondingThread, oldMetadata, newState);
              }
            } else {
              stillWaiting = true;
              break;
            }
          }
        }
        if (!stillWaiting) {
          break;
        }
        if (VM.VerifyAssertions) {
          checkForResponseTimeout(numThreadsSentRequest, null /* it would be nice to send the remote thread instead */, startTime, 90*1000*1000);
        }
        if (iter >= Octet.waitSpinCount()) {
          if (iter >= Octet.waitSpinCount() + Octet.waitYieldCount()) {
            for (int i = 0 ; i < numThreadsSentRequest; i++) {
              // Double-checked locking should be okay here
              RVMThread respondingThread = respondingThreads[i];
              if (respondingThread != null) {
                int respondingThreadCounter = respondingThreadCounters[i];
                if (!responseReceived(respondingThread.octetResponses, respondingThreadCounter)) {
                //if (respondingThread.octetResponses < respondingThreadCounter) {
                  sysCall.sysMonitorEnter(respondingThread.octetMonitor);
                  while (!responseReceived(respondingThread.octetResponses, respondingThreadCounter)) {
                  //while (respondingThread.octetResponses < respondingThreadCounter) {
                    sysCall.sysMonitorWait(respondingThread.octetMonitor);
                  }
                  sysCall.sysMonitorExit(respondingThread.octetMonitor);
                }
                // Setting it to null isn't absolutely necessary since loop exits below, but it'll help threads get GC'd.
                // Note that we had previously discussed making RVMThread.octetRespondingThreads @Untraced, but that wouldn't
                // work because we want only the array elements to be untraced, not the array itself!
                respondingThreads[i] = null;
                
                // Let the client analysis handle receiving the response.
                // Doing a load fence here to ensure a happens-before from the write of octetResponse to this point.
                // Note that the monitor stuff above may not execute, and even if it does, monitorExit might not include lfence behavior (just sfence behavior?).
                Magic.readCeiling();
                if (Octet.getClientAnalysis().shouldExecuteSlowPathHooks(true)) {
                  Octet.getClientAnalysis().handleReceivedResponse(respondingThread, oldMetadata, newState);
                }
              }
            }
            break; // exit the outer loop
          } else {
            RVMThread.yieldNoHandshake();
          }
        } else {
          Magic.pause();
        }
      }
      // Ensure happens-before between loads of other threads' response counter and later loads.
      // (Not currently needed since there's an lfence before each handleReceivedResponse() above.
      //Magic.lfence();
      
      Stats.logTimeCommunicateRequests.incTime(startTime);
      Stats.logWaitIter.incBin(iter);
    }

  }
  
  /** Helper method that sends a request to a single thread */
  @NoCheckStore // due to setting the RVMThread.octetRespondingThreads[i]
  static final int sendRequest(Address baseAddr, Offset offset, RVMThread remoteThread, int numThreadsSentRequest, Word oldMetadata, Word newState, int fieldOffset, int siteID) {
    RVMThread myThread = RVMThread.getCurrentThread();
    while (true) {
      Word oldRequests = ObjectReference.fromObject(remoteThread).toAddress().prepareWord(Entrypoints.octetRequestsField.getOffset());
//      boolean isDead = Magic.attemptObject(remoteThread, Entrypoints.octetRequestQueueHeadField.getOffset(), OctetRequestElement.octetDeathState, OctetRequestElement.octetDeathState);
//      if (isDead) {
//        return numThreadsSentRequest;
//      }
      
      // Check that if the thread's request word isn't marked dead, it really isn't dead.
      // But we need to double-check that the request word is marked dead, to deal with race conditions.
      // Note that this is just an assertion, so it's just best-effort checking.
      // For the lfences below to be completely effective, there needs to be sfence behavior on the remote thread (which there might be because of the CAS on the blocked state).
      if (VM.VerifyAssertions) {
        if (!CommunicationQueue.isDead(oldRequests)) {
          Magic.readCeiling();
          if (remoteThread.getIsAboutToTerminate() ||
              remoteThread.getExecStatus() == RVMThread.TERMINATED ||
              RVMThread.threadBySlot[remoteThread.threadSlot] != remoteThread) {
            Magic.readCeiling();
            VM._assert(!CommunicationQueue.isDead(oldRequests)); 
          }
        }
      }
      
      // ABA might happen here, but that's okay.
      // see http://en.wikipedia.org/wiki/ABA_problem
      if (CommunicationQueue.isBlockedOrDead(oldRequests)) {
        boolean isDead = CommunicationQueue.isDead(oldRequests);
        boolean result;
        Word newRequests = Word.zero();
        if (isDead) {
          // We don't need to CAS a on dead thread, but let's be sure we "see" everything it did before dying.
          Magic.readCeiling();
          result = true;
        } else {
          if (Octet.getClientAnalysis().useHoldingState()) {
            newRequests = Octet.getClientAnalysis().incRequestCounterForImplicitProtocol() ?
                          CommunicationQueue.addHoldAndIncrement(oldRequests) :
                          CommunicationQueue.addHold(oldRequests);
          } else {
            // This CAS is needed to create the correct HB relationship.
            // Otherwise there's no guarantee the remote thread will "see" the object in the intermediate state.
            // With the CAS, when the request queue unblocks, it'll be guaranteed to see that the object is in the Int state or the new state.
            // The increment will help some analyses, like Roctet.
            if (Octet.getClientAnalysis().incRequestCounterForImplicitProtocol()) {
              newRequests = CommunicationQueue.inc(oldRequests);
            } else {
              newRequests = oldRequests;
            }
          }
          result = ObjectReference.fromObject(remoteThread).toAddress().attempt(oldRequests, newRequests, Entrypoints.octetRequestsField.getOffset());
        }
        if (result) {
          // Call the hook, then unhold the thread if the holding state is being used.  The thread might be dead.
          if (Octet.getClientAnalysis().shouldExecuteSlowPathHooks(true)) {
            Octet.getClientAnalysis().handleConflictForBlockedThread(remoteThread, baseAddr, offset, oldMetadata, newState, isDead,
                                                                     CommunicationQueue.getCounter(newRequests), fieldOffset, siteID);
          }
          // Remove the hold state, if applicable.
          if (!isDead && Octet.getClientAnalysis().useHoldingState()) {
            oldRequests = newRequests; // we now want to decrement the held count by one
            while (true) {
              newRequests = CommunicationQueue.removeHold(oldRequests);
              result = ObjectReference.fromObject(remoteThread).toAddress().attempt(oldRequests, newRequests, Entrypoints.octetRequestsField.getOffset());
              if (result) {
                break;
              }
              oldRequests = ObjectReference.fromObject(remoteThread).toAddress().prepareWord(Entrypoints.octetRequestsField.getOffset());
            }
          }

          if (VM.VerifyAssertions) {
            if (Octet.getClientAnalysis().incRequestCounterForImplicitProtocol() && !isDead) {
              //The first argument is true, because the remote thread could have already responded the requests,
              //making the response counter greater than newRequests.
              //So we just check the absolute difference between the two counters.
              checkCounterRange(true, remoteThread.octetResponses, CommunicationQueue.getCounter(newRequests));
            }
          }
          return numThreadsSentRequest;
        }
      } else {
        // oldRequests is not blocked or dead, we should use explicit protocol
        Word newRequests;
        if (Octet.getClientAnalysis().needsCommunicationQueue()) {
          int oldHeadThreadID = CommunicationQueue.getThreadID(oldRequests);
          myThread.nextRequestQueueThread[remoteThread.threadSlot] = oldHeadThreadID; // important even if oldHeadThreadID==0
          newRequests = CommunicationQueue.push(oldRequests, myThread.threadSlot);
        } else {
          newRequests = CommunicationQueue.inc(oldRequests);
          if (VM.VerifyAssertions) { VM._assert(newRequests.EQ(CommunicationQueue.push(oldRequests, 0))); }
        }
        
        boolean result = ObjectReference.fromObject(remoteThread).toAddress().attempt(oldRequests, newRequests, Entrypoints.octetRequestsField.getOffset());
        if (result) {
          // Set the yield point flag, so the remote thread will stop as soon as possible.
          remoteThread.takeYieldpoint = 1;
          // Call the appropriate handler.
          if (Octet.getClientAnalysis().shouldExecuteSlowPathHooks(true)) {
            Octet.getClientAnalysis().handleRequestSentToUnblockedThread(remoteThread, CommunicationQueue.getCounter(newRequests), siteID);
          }
          // Keep track of the communicated-with threads and the request counters that we set.
          myThread.octetRespondingThreads[numThreadsSentRequest] = remoteThread;
          myThread.octetRespondingThreadCounters[numThreadsSentRequest] = CommunicationQueue.getCounter(newRequests);
          
          if (VM.VerifyAssertions) {
            checkCounterRange(true, remoteThread.octetResponses, CommunicationQueue.getCounter(newRequests));
          }
          return numThreadsSentRequest + 1;
        }
      }
    }
  }

  /** Helper method to check whether we care about a thread*/
  @Inline
  public static final boolean isValidOtherThread(RVMThread remoteThread, RVMThread myThread) {
    return remoteThread != null &&
           remoteThread.isOctetThread() &&
           remoteThread != myThread;
  }
  
  /** For debugging purposes */
  static final void checkForResponseTimeout(int numThreadsSentRequest, RVMThread theRemoteThread, long startTime, long timeout) {
    RVMThread myThread = RVMThread.getCurrentThread();
    if (/*RVMThread.getCurrentThread().octetThreadsWaitedFor > 0 ||*/ Stats.nsElapsed(startTime) > timeout) {
      //sysCall.sysMonitorExit(myThread.octetMonitor);
      RVMThread.debugLock.lockNoHandshake();
      VM.sysWriteln("Thread ", myThread.threadSlot, " waited for a while but no response, microSecElapsed = ", Stats.nsElapsed(startTime));
      VM.sysWriteln("numThreadsSentRequest = ", numThreadsSentRequest);
      if (theRemoteThread != null) {
        VM.sysWriteln("theRemoteThread slot = ", theRemoteThread.threadSlot);
      }
      for (RVMThread otherThread : RVMThread.threadBySlot) {
        
        if (isValidOtherThread(otherThread, myThread)) {
          /*
          Request request = myThread.octetRequests[remoteThread.threadSlot];
          if (request.clock == newClock) {
            VM.sysWriteln("Sent a request to thread ", remoteThread.threadSlot);
            if (remoteThread.octetResponses[myThread.threadSlot] != newClock) {
              VM.sysWriteln("No response from thread ", remoteThread.threadSlot);
            }
          } else */ {
            VM.sysWriteln("Other thread ", otherThread.threadSlot);
          }
          Stats.tryToPrintStack(otherThread.framePointer);
        }
      }
      RVMThread.debugLock.unlock();
      //sysCall.sysMonitorEnter(myThread.octetMonitor);
      VM.sysFail("Got stuck");
    }
  }

  private static final Word HIGHEST_COUNTER_BIT_MASK = Word.one().lsh(CommunicationQueue.COUNTER_BITS - 1);

  private static final int maxDiff = Octet.getClientAnalysis().incRequestCounterForImplicitProtocol()?
      /** In this case we assume the difference between the two counter values
       *  should not exceed one third of the entire range.
       *  The trick of shifting n-1 bits then times 2 is to avoid negative number.*/
      (1 << (CommunicationQueue.COUNTER_BITS - 1)) / 3 * 2 :
      // Actually, this should be the max number of live octet threads that the application can have.
      RVMThread.MAX_THREADS;
                                     
  // This method should only be called by the REQUESTING thread
  @Inline
  private static final boolean responseReceived(int response, int oldRequest) {
    if (VM.VerifyAssertions) {
      checkCounterRange(true, response, oldRequest);
    }
    return respGEreq(response, oldRequest);
  }
  
  // This method should only be called by the RESPONDING thread
  @Inline
  private static final boolean requestSeen(int response, int newRequest) {
    if (VM.VerifyAssertions) {
      // The newRequest counter should not grow too fast.
      checkCounterRange(false, response, newRequest);
    }
    return !respGEreq(response, newRequest);
  }
  
  /** If response>=request, then the response has been received;
   * for handling overflow, the condition is: (response-request)%(2^k) is within [0, 2^(k-1)-1],
   * where k is COUNTER_BITS, and we assume that the amount of increment for the counter should not exceed half of 2^k.
   * It is equivalent to checking if the highest counter bit of (response-request) is zero,
   * the AND op for computing %(2^k) is not even needed for implementation. */
  @Inline
  private static final boolean respGEreq(int response, int request){
    return Word.fromIntSignExtend(response - request).and(HIGHEST_COUNTER_BIT_MASK).isZero();
  }
  
  private static final void checkCounterRange(boolean isOldRequest, int response, int request) {
    boolean failed = false;
    if (Octet.getClientAnalysis().incRequestCounterForImplicitProtocol()) {
      int diff;
      if (isOldRequest && respGEreq(response, request)) {
        diff = getDiff(response, request);
      } else {
        diff = getDiff(request, response); 
      }
      if (diff > maxDiff) {
        failed = true;
        VM.sysWriteln("diff: ", diff);
        VM.sysWriteln("maxDiff: ", maxDiff);
        VM.sysWriteln("The difference of the two counters is larger than 1/3 of the entire value range!");
      }
    } else {
      if (isOldRequest && respGEreq(response, request)) {
        // no need to check this case
        return;
      }
      int diff = getDiff(request, response);
      if (diff > maxDiff - 1) {
        failed = true;
        if (isOldRequest) {
          // Response counter should not be inside the "no-man's-land zone" of the counter range.
          // If this check fails, our assumption will not hold: "the amount of increment for the counter should not exceed half of 2^k".
          // This means the requesting thread is so slow in checking the responding thread that it missed the response.
          VM.sysWrite("Requesting thread's slot: ", RVMThread.getCurrentThreadSlot());
          VM.sysWriteln(" diff: ", diff);
          VM.sysWriteln("Response counter grows too fast for requesting thread, already exceeds half of counter range!");
        } else {
          // The invariant "the responding thread can receive at most n-1 requests before it responds" is violated.
          VM.sysWrite("Responding thread's slot: ", RVMThread.getCurrentThreadSlot());
          VM.sysWriteln(" diff: ", diff);
          VM.sysWriteln("Too many requests received for responding thread, max # of live octet threads is ", maxDiff);
        }
      }
    }
    
    if (failed) {
      VM.sysWriteln("response: ", response);
      VM.sysWriteln("request: ", request);
      RVMThread.dumpStack();
      VM.sysFail("Request/response counters out of range");
    }
  }
  
  private static final int getDiff(int larger, int smaller) {
    int diff;
    if (larger >= smaller){
      diff = larger - smaller;
    } else {
      // Man: "<<" has lower precedence than "+" or "-"
      diff = (1 << CommunicationQueue.COUNTER_BITS) + larger - smaller;
    }
    return diff;
  }
}