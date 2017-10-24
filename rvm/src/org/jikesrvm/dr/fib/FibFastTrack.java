package org.jikesrvm.dr.fib;

import org.jikesrvm.VM;
import org.jikesrvm.dr.Dr;
import org.jikesrvm.dr.DrDebug;
import org.jikesrvm.dr.DrRuntime;
import org.jikesrvm.dr.DrStats;
import org.jikesrvm.dr.fasttrack.FastTrack;
import org.jikesrvm.dr.metadata.AccessHistory;
import org.jikesrvm.dr.metadata.Epoch;
import org.jikesrvm.dr.metadata.VC;
import org.jikesrvm.runtime.Magic;
import org.jikesrvm.scheduler.RVMThread;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.NoInline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.pragma.Unpreemptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.ObjectReference;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;
import org.vmmagic.unboxed.WordArray;

/**
 * This FastTrack does FIB biasing to ensure integrity of analysis accesses
 * and check-access atomicity.
 * 
 * Other than transient states, we assume the invariant that the last reader(s)
 * in a history always happen after (or are the same as) the last writer.
 * Whenever we update the last writer, we always update the last reader to the same.
 * However, by executing past the first race, we allow this invariant to be
 * broken, since we, for example, only check writes against last readers and
 * not against last writer.
 * 
 * See Octet read sharing count, fences, intermediate states, etc.
 * 
 * TODO make more amenable to inlining.
 * 
 * TODO support epoch collapse opt.
 *
 */
@Uninterruptible
public final class FibFastTrack extends FastTrack {
  
  private static final boolean PRINT = false;

  /**
   * Fast path for writes.  Check is completed without the slow path if
   * no transition is required.
   */
  @Inline
  @Override
  @Unpreemptible
  public void write(final Object md, final Offset historyOffset) {
    if (PRINT) {
      VM.sysWriteln("T", RVMThread.getCurrentThread().getDrID(), " W ", 
          AccessHistory.address(md, historyOffset));
    }
    
    final Word now = RVMThread.getCurrentThread().getDrEpoch();

    final Word lastWriteEpoch = AccessHistory.loadWriteWord(md, historyOffset);

    // 10% - 92%
    if (lastWriteEpoch.EQ(now) || isFrozen(lastWriteEpoch)) {
      // EXCLUSIVE TO THIS THREAD, in same epoch as last write.
      // FASTEST

      // Assuming we halt at the first race:
      // If there exists any read, it is guaranteed to be the same epoch
      // as well, since race-free reads by a different thread would have
      // required synchronization in this thread since the last write in
      // this thread, meaning the epoch would be different.
      // Verify this:
//      if (VM.VerifyAssertions) {
//        final Word lastReadEpoch = AccessHistory.getReadWord(md, historyOffset);
//        // VM._assert(lastReadEpoch.EQ(epoch) || VC.epochHB(lastReadEpoch, thread.fibThreadVC));
//        if (lastReadEpoch.NE(epoch) && !VC.epochHB(lastReadEpoch, RVMThread.getCurrentThread().fibThreadVC)) {
//          VM.sysWriteln("WARNING: T", RVMThread.getCurrentThread().getFibID(),
//              " same write epoch case sanity check fails.  Race report should be nearby in T",
//              Epoch.tid(lastReadEpoch));
//        }
//      }
      if (Dr.STATS) DrStats.writeSameWriteEpoch.inc();
      return;
    }

    // In all other cases (besides same epoch as last write), bias is
    // determined by the read word.
    Word lastReadWord = AccessHistory.loadReadWord(md, historyOffset);

    if (Dr.config().fibAdaptiveCas() && Epoch.isAlt(lastReadWord)) {
      writeCasReport(md, historyOffset, lastReadWord, now);
      return;
    }

    if (lastReadWord.EQ(now)) {
      // EXCLUSIVE, in same epoch as last read.

      // If the last writer is not this thread, this access must still be race-free
      // assuming the last read (which was by this thread in this epoch) did not race
      // with the last write.
      // Set write epoch.
      AccessHistory.storeWriteWord(md, historyOffset, now);
      if (Dr.STATS) DrStats.writeSameReadEpoch.inc();
      return;
    }

    if (Epoch.sameTid(lastReadWord, now)) {
      // EXCLUSIVE

      // If the last writer is not this thread, this access must still be race-free
      // assuming the last read (which was by this thread) did not race
      // with the last write AND that new last writes always also update last read.
      // Set both write and read epoch.
      AccessHistory.storeWriteWord(md, historyOffset, now);
      AccessHistory.storeReadWord(md, historyOffset, Epoch.withDelegateBitFrom(now, lastReadWord));
      if (Dr.STATS) DrStats.writeOwned.inc();
      return;
    }
    
    if (Epoch.isNone(lastReadWord)) {
      // FIRST
      writeFirst(md, historyOffset, now);
      return;
    }

    writeSlowPathReport(md, historyOffset, lastReadWord);
  }    

  private static void writeFirst(final Object md, final Offset historyOffset, final Word epoch) {
    if (AccessHistory.attemptReadWord(md, historyOffset, Epoch.NONE, epoch)) {
      // We take ownership.
      AccessHistory.storeWriteWord(md, historyOffset, epoch);
      if (Dr.STATS) DrStats.writeFirst.inc();
      return;
    } else {
      if (Dr.STATS) DrStats.writeFirstConflict.inc();
      race(true, md, historyOffset);
      return;
    }
  }
  
  @Unpreemptible
  private static void writeSlowPathReport(Object md, Offset historyOffset, Word lastReadWord) {
    if (!writeSlowPath(md, historyOffset, lastReadWord)) {
      FibFastTrack.race(true, md, historyOffset);
    }
  }

  /**
   * Slow path for writes, taken when transition is required.
   * @param thread
   * @param history
   */
  @NoInline
  @Unpreemptible
  protected static boolean writeSlowPath(final Object md, final Offset historyOffset, final Word lastReadWord) {
    final Word now = RVMThread.getCurrentThread().getDrEpoch();

    if (Dr.STATS) DrStats.writeRemote.inc();
    final FibComm manager = RVMThread.getCurrentThread().drFibComm;

    if (Epoch.isEpoch(lastReadWord)) {
      // EXCLUSIVE TO Epoch.tid(lastReadWord).
      return manager.requestTransition(md, historyOffset, lastReadWord, true);
    }
    
    if (VM.VerifyAssertions) DrRuntime.checkGcCount();

    if (Epoch.isMapRef(lastReadWord)) {
      // READ-SHARED.
      if (Dr.STATS) DrStats.writeShared.inc();
      final WordArray threadVC = RVMThread.getCurrentThread().drThreadVC;
      // final Word lwe = AccessHistory.loadWriteWord(md, historyOffset);

      if (!AccessHistory.attemptReadReserved(md, historyOffset, lastReadWord)) {
        // If the CAS fails, this is definitely a race.
        return false;
      }
      // Succeeded in placing Epoch.RESERVED into read word.
      // No other thread can gain access until the next yield point in this thread,
      // which will be while we are blocking on the threads that have entries in the
      // last readers, but we will mark that specially, so we still don't lose
      // control there.

      AccessHistory.storeWriteWord(md, historyOffset, now);
      Magic.fence();

      // Check against last reads.
      final WordArray readers = Epoch.asMapRef(lastReadWord);
      final FibComm frm = RVMThread.getCurrentThread().drFibComm;
      // Handshake with each thread that has a nonzero entry in the reader set.
      final Word entries = frm.requestAcks(md, historyOffset, readers);
      frm.awaitResponse(entries);
      // boolean race = false;
      // For each thread that had an entry to start, check for races against its current entry.
      boolean result = true;
      for (int i = 0; i < Epoch.MAX_THREADS; i++) {
        if (!entries.and(Word.one().lsh(i)).isZero()
            && !VC.epochHB(Dr.readers().get(readers, i), threadVC)) {
          result = false;
          break;
        }
      }

      if (Dr.STATS) {
        int n = 0;
        for (int tid = 0; tid < Epoch.MAX_THREADS; tid++) {
          if (!Epoch.isNone(Dr.readers().get(readers, tid))) n++;
        }
        if (Dr.STATS) DrStats.deflationWidth.incBin(n);
      }

      Magic.writeFloor();
      if (Dr.config().fibStickyReadShare()) {
        Dr.readers().install(md, historyOffset, readers);
      } else {
        AccessHistory.storeReadWord(md, historyOffset, now); // Not delegated.  TODO: Is this a good time to delegate?
      }
      
      if (VM.VerifyAssertions) DrRuntime.checkGcCount();

      return result;
    }
    
    if (!Epoch.isReserved(lastReadWord)) VM.sysFail("unreachable write case in FibFastTrack");
    return false;
  }
  
  protected static void writeCasReport(Object md, Offset historyOffset, Word lastReadWord, Word now) {
    if (!writeCas(md, historyOffset, lastReadWord, now)) {
      race(true, md, historyOffset);
    }
  }
  @Inline
  protected static boolean writeCas(Object md, Offset historyOffset, Word lastReadWord, Word now) {
    if (FibCas.PRINT) {
      DrDebug.lock();
      DrDebug.twrite(); VM.sysWriteln("  +wcas      ", AccessHistory.address(md, historyOffset));
      DrDebug.unlock();
    }
    
    if (VM.VerifyAssertions) VM._assert(Epoch.isEpoch(lastReadWord) && Epoch.isAlt(lastReadWord));

    if (Epoch.sameTid(now, lastReadWord)
        || VC.epochHB(Epoch.asDefault(lastReadWord), RVMThread.getCurrentThread().drThreadVC)) {
      if (AccessHistory.attemptReadReserved(md, historyOffset, lastReadWord)) {
        if (Dr.STATS) DrStats.writeCasSafe.inc();
        AccessHistory.storeWriteWord(md, historyOffset, now);
        Magic.writeFloor();
        AccessHistory.storeReadWord(md, historyOffset, Epoch.asAlt(now));
        
        if (FibCas.PRINT) {
          DrDebug.lock();
          DrDebug.twrite(); VM.sysWriteln("  =wcas fast ", AccessHistory.address(md, historyOffset));
          DrDebug.unlock();
        }

        return true;
      }
      if (Dr.STATS) DrStats.writeCasConcurrentRace.inc();
      return false;
    }

    if (Dr.STATS) DrStats.writeCasOrderRace.inc();
    return false;
  }
  
  
  /**
   * Read fast path.  Checks are completed without the slow path if
   * no transitions are needed.
   */
  @Unpreemptible
  @Override
  @Inline
  public void read(final Object md, final Offset historyOffset) {

    if (PRINT) {
      VM.sysWriteln("T", RVMThread.getCurrentThread().getDrID(), " R ", 
          AccessHistory.address(md, historyOffset));
    }
    
    if (VM.VerifyAssertions) DrRuntime.stashGcCount();

    final Word now = RVMThread.getCurrentThread().getDrEpoch();

    Word lastReadWord = AccessHistory.loadReadWord(md, historyOffset);

    // 41% - 92%
    if (Epoch.sameEpoch(lastReadWord, now) || isFrozen(lastReadWord)) {
      // EXCLUSIVE TO THIS THREAD.
      // Read in same epoch as last read.
      // FASTEST
      if (Dr.STATS) DrStats.readSameReadEpoch.inc();
      return;
    }
    
   if (Dr.config().fibAdaptiveCas() && Epoch.isAlt(lastReadWord)) {
      readCasReport(md, historyOffset, lastReadWord, now);
      return;
    }

    if (Epoch.sameTid(lastReadWord, now)) {
      // EXCLUSIVE.
      AccessHistory.storeReadWord(md, historyOffset, Epoch.withDelegateBitFrom(now, lastReadWord));
      if (Dr.STATS) DrStats.readOwned.inc();
      return;
    }

    if (Epoch.isMapRef(lastReadWord)) {
      // READ-SHARED
      if (!readShared(md, historyOffset, Epoch.asMapRef(lastReadWord), now)) {
        race(false, md, historyOffset);
      }
      return;
    }

    
    readSlowPathReport(md, historyOffset, lastReadWord);
  }

  @NoInline
  @Unpreemptible
  protected static void readSlowPathReport(final Object md, final Offset historyOffset, Word lastReadWord) {
    if (!readSlowPath(md, historyOffset, lastReadWord)) {
      if (Dr.config().drFirstRacePerLocation() && !Epoch.isNone(lastReadWord)) {
        race(false, md, historyOffset);
      }
    }
  }

  @Unpreemptible
  protected static boolean readSlowPath(final Object md, final Offset historyOffset, Word lastReadWord) {

    final Word now = RVMThread.getCurrentThread().getDrEpoch();
    
    if (Epoch.isNone(lastReadWord)) {
      // FIRST ACCESS
      return readFirst(md, historyOffset, now);
    }
    
    // Conflicting transition
    if (Dr.STATS) DrStats.readRemote.inc();
    final FibComm manager = RVMThread.getCurrentThread().drFibComm;
    return manager.requestTransition(md, historyOffset, lastReadWord, false);
  }

  @Unpreemptible
  private static boolean readFirst(final Object md, final Offset historyOffset, final Word now) {
    if (AccessHistory.attemptReadWord(md, historyOffset, Epoch.NONE, now)) {
      // Now we own it.
      if (Dr.STATS) DrStats.readFirst.inc();
      return true;
    } else {
      // Someone else beat us.
      if (Dr.STATS) DrStats.readFirstConflict.inc();
      Magic.readCeiling();
      readSlowPath(md, historyOffset, AccessHistory.loadReadWord(md, historyOffset));
      return false;
    }
  }

  @Uninterruptible
  protected static boolean readShared(final Object md, final Offset historyOffset,
      final WordArray readMap, final Word now) {

    if (VM.VerifyAssertions) DrRuntime.checkGcCount();

    final Word myLastReadEpoch = Dr.readers().get(readMap, Epoch.tid(now));

    if (VM.VerifyAssertions) DrRuntime.checkGcCount();

    if (myLastReadEpoch.EQ(now)) {
      // Already checked.
      if (Dr.STATS) DrStats.readSharedSameEpoch.inc();
      return true;
    }

    Dr.readers().set(readMap, now);

    if (VM.VerifyAssertions) DrRuntime.checkGcCount();

    if (!Epoch.isNone(myLastReadEpoch)) {
      // This thread has read since last write, so potential deflater knows about it.
      // But it has not read in this epoch, so it still needs recording.
      if (Dr.STATS) DrStats.readSharedAgain.inc();
      return true;
    }

    if (Dr.STATS) DrStats.readShared.inc();
    Magic.fence(); // Make sure our update is visible before we do the race check.
    return VC.epochHB(AccessHistory.loadWriteWord(md, historyOffset), RVMThread.getCurrentThread().drThreadVC);
    // If no race (yet).  If this read does race with a pending write, it will DEFINITELY
    // be caught by the write.
  }
  
  @Unpreemptible
  protected static void readCasReport(Object md, Offset historyOffset, Word lastReadWord, Word now) {
    if (!readCas(md, historyOffset, lastReadWord, now)) {
      race(false, md, historyOffset);
    }
  }
  
  @Inline
  @Unpreemptible
  protected static boolean readCas(Object md, Offset historyOffset, Word lastReadWord, Word now) {
    if (FibCas.PRINT) {
      DrDebug.lock();
      DrDebug.twrite(); VM.sysWriteln("  +rcas      ", AccessHistory.address(md, historyOffset));
      DrDebug.unlock();
    }
    
    if ((Epoch.sameTid(now, lastReadWord)
          || VC.epochHB(Epoch.asDefault(lastReadWord), RVMThread.getCurrentThread().drThreadVC))
        && (!Dr.config().fibPreemptiveReadShare() || Epoch.asDefault(lastReadWord).EQ(AccessHistory.loadWriteWord(md, historyOffset)))
        && AccessHistory.attemptReadWord(md, historyOffset, lastReadWord, Epoch.asAlt(now))) {
      if (Dr.STATS) DrStats.readCasExcl.inc();

      if (FibCas.PRINT) {
        DrDebug.lock();
        DrDebug.twrite(); VM.sysWriteln("  +rcas fast ", AccessHistory.address(md, historyOffset));
        DrDebug.unlock();
      }

      return true;
    }
    return readCasSlowPath(md, historyOffset, now);
  }
  
  @Unpreemptible
  protected static boolean readCasSlowPath(Object md, Offset historyOffset, Word now) {
    if (FibCas.PRINT) {
      DrDebug.lock();
      DrDebug.twrite(); VM.sysWriteln("  +rcas slow ", AccessHistory.address(md, historyOffset));
      DrDebug.unlock();
    }
    final int maxSpins = FibComm.spinsBeforeYield();
    int tries = 0;    
    Word lastReadWord = AccessHistory.prepareReadWord(md, historyOffset);
    while (Epoch.isEpoch(lastReadWord)) {
      if (Epoch.isAlt(lastReadWord)) {
        Magic.readCeiling();
        final Word lastWriteWord = AccessHistory.loadWriteWord(md, historyOffset);
        if (VC.epochHB(lastWriteWord, RVMThread.getCurrentThread().drThreadVC)) {
          WordArray map = Dr.readers().create();
          Dr.readers().set(map, lastReadWord);
          Dr.readers().set(map, now);
          boolean result = Dr.readers().attemptInstall(md, historyOffset, lastReadWord, map);
          if (result) {
            Magic.fence();
            Magic.readCeiling();
            boolean safe = lastWriteWord.EQ(AccessHistory.loadWriteWord(md, historyOffset));
            if (Dr.STATS) (safe ? DrStats.readCasShare : DrStats.readCasShareConcurrentRace).inc();
            
            if (FibCas.PRINT) {
              DrDebug.lock();
              DrDebug.twrite(); VM.sysWriteln("  =rcas slow ", AccessHistory.address(md, historyOffset));
              DrDebug.unlock();
            }
            
            return safe;
          } else {
            if (Dr.STATS) DrStats.readCasShareRetry.inc();
            // Stash the map for later use.
            Dr.readers().set(map, Epoch.tid(lastReadWord), Word.zero());
            Dr.readers().stash(map);
            // pause
            if (++tries > maxSpins) {
              if (FibComm.CHECK_SPIN) FibComm.checkSpin(tries, FibComm.MAX_WAIT_SPINS, "readCAS, yield-spin");
              DrRuntime.respond();
              RVMThread.yieldNoHandshake();
            } else {
              if (FibComm.CHECK_SPIN) FibComm.checkSpin(tries, FibComm.MAX_WAIT_SPINS, "readCAS, pause-spin");
              Magic.pause();
            }
            // retry
            lastReadWord = AccessHistory.prepareReadWord(md, historyOffset);
          }
        } else {
          if (Dr.STATS) DrStats.readCasOrderRace.inc();
          
          if (FibCas.PRINT) {
            DrDebug.lock();
            DrDebug.twrite(); VM.sysWriteln("  !rcas slow ", AccessHistory.address(md, historyOffset));
            DrDebug.unlock();
          }
          
          return false;
        }
      } else {
        if (Dr.STATS) {
          DrStats.readCasConcurrentUnCas.inc();
          DrStats.readRemote.inc();
        }
        
        if (FibCas.PRINT) {
          DrDebug.lock();
          DrDebug.twrite(); VM.sysWriteln("  ?rcas slow ", AccessHistory.address(md, historyOffset));
          DrDebug.unlock();
        }

        final FibComm manager = RVMThread.getCurrentThread().drFibComm;
        boolean result = manager.requestTransition(md, historyOffset, lastReadWord, false);
        
        if (FibCas.PRINT) {
          DrDebug.lock();
          DrDebug.twrite(); VM.sysWriteln("  =rcas slow ", AccessHistory.address(md, historyOffset));
          DrDebug.unlock();
        }
        
        return result;
      }
    }
    
    // Race with reserved...
    if (!Epoch.isMapRef(lastReadWord)) return false;

    if (FibCas.PRINT) {
      DrDebug.lock();
      DrDebug.twrite(); VM.sysWriteln("  *rcas slow ", AccessHistory.address(md, historyOffset));
      DrDebug.unlock();
    }

    DrStats.readCasShared.inc();
    boolean result = readShared(md, historyOffset, Epoch.asMapRef(lastReadWord), now);
    
    if (FibCas.PRINT) {
      DrDebug.lock();
      DrDebug.twrite(); VM.sysWriteln("  =rcas slow ", AccessHistory.address(md, historyOffset));
      DrDebug.unlock();
    }
    
    return result;
  }
  
  @Override
  public void terminateThread(RVMThread dead) {
    DrDebug.lock();
    DrDebug.twrite();
    VM.sysWriteln("finished with ", dead.getDrRaces(), " races");
    if (VM.VerifyAssertions) {
      VM.sysWriteln("and block depth ", dead.drFibComm.getBlockDepth());
    }
    DrDebug.unlock();
  }

}
