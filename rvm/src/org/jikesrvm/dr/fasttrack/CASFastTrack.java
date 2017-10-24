package org.jikesrvm.dr.fasttrack;

import org.jikesrvm.VM;
import org.jikesrvm.dr.Dr;
import org.jikesrvm.dr.DrDebug;
import org.jikesrvm.dr.DrStats;
import org.jikesrvm.dr.metadata.AccessHistory;
import org.jikesrvm.dr.metadata.Epoch;
import org.jikesrvm.dr.metadata.VC;
import org.jikesrvm.mm.mminterface.MemoryManager;
import org.jikesrvm.scheduler.RVMThread;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.pragma.Unpreemptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;
import org.vmmagic.unboxed.WordArray;

/**
 * This FastTrack uses CASed replacement of the read word in an access history
 * to lock it during analysis.
 * 
 * TODO respect epoch collapse optimization
 * 
 */
@Uninterruptible
public final class CASFastTrack extends FastTrack {

  /** Print events? */
  private static final boolean PRINT = false;

  @Inline
  @Override
  public void write(final Object md, final Offset historyOffset) {
    if (PRINT) {
      VM.sysWriteln("T", RVMThread.getCurrentThread().getDrID(), " W ", 
          AccessHistory.address(md, historyOffset));
    }
    final Word now = RVMThread.getCurrentThread().getDrEpoch();
    final Word lastWriteEpoch = AccessHistory.loadWriteWord(md, historyOffset);
    if (lastWriteEpoch.EQ(now) || isFrozen(lastWriteEpoch)) {
      // Write in same epoch.
      if (Dr.STATS) DrStats.writeSameWriteEpoch.inc();
      return;
    }

    writeSlowPath(md, historyOffset, now);
  }

  private void writeSlowPath(final Object md, final Offset historyOffset,
      final Word now) {
    final WordArray nowVC = RVMThread.getCurrentThread().drThreadVC;

    // LOCK reader
    final Word r = AccessHistory.acquireReadWord(md, historyOffset);

    if (Dr.STATS) {
      if (Epoch.isMapRef(r)) {
        DrStats.writeShared.inc();
      } else if (Epoch.sameEpoch(r, now)) {
        DrStats.writeSameReadEpoch.inc();
      } else if (Epoch.sameTid(r, now)){
        DrStats.writeOwned.inc();
      } else if (Epoch.isNone(r)) {
        DrStats.writeFirst.inc();
      } else {
        DrStats.writeRemote.inc();
      }
    }

    if ((Epoch.isEpoch(r) && VC.epochHB(r, nowVC))
        || ((Epoch.isMapRef(r)
            && (Dr.config().drCasFineGrained() && casHB(md, historyOffset, Epoch.asMapRef(r), nowVC)
                || !Dr.config().drCasFineGrained() && VC.hb(Epoch.asMapRef(r), nowVC)))
        || Epoch.isNone(r))) {
      // SET writer
      AccessHistory.storeWriteWord(md, historyOffset, now);
      // UNLOCK reader
      AccessHistory.releaseReadWord(md, historyOffset, now);
    } else {
      race(true, md, historyOffset, AccessHistory.loadWriteWord(md, historyOffset), r);
      return;
//      if (Dr.config().fibPatchRaces()) {
//        if (Epoch.isMapRef(r)) {
//          Dr.readers().install(md, historyOffset, Epoch.asMapRef(r));
//        } else {
//          AccessHistory.releaseReadWord(md, historyOffset, r);
//        }
//      }
    }
  }

  /**
   * Does the given reader map happen before the given vc?
   * If not report a race for this location.
   * 
   * @param md
   * @param historyOffset
   * @param map
   * @param nowVC
   * @return
   */
  private static boolean casHB(final Object md, final Offset historyOffset,
      final WordArray map, final WordArray nowVC) {
    for (int tid = 0; tid < Epoch.MAX_THREADS; tid++) {
      final Word e = Dr.readers().get(map, tid);
      if (!VC.epochHB(e, nowVC) || !Dr.readers().attemptReserve(map, tid, e)) {
        race(true, md, historyOffset); //, AccessHistory.loadWriteWord(md, historyOffset), map.toWord());
//        if (Dr.config().fibPatchRaces()) {
//          for (; tid >= 0; tid--) {
//            Dr.readers().set(map, tid, Epoch.NONE); // Cheating, since no way to recover old...
//          }
//        }
        return false;
      }
    }
    return true;
  }

  @Inline
  @Unpreemptible
  @Override
  public void read(final Object md, final Offset historyOffset) {
    if (PRINT) {
      VM.sysWriteln("T", RVMThread.getCurrentThread().getDrID(), " R ", 
          AccessHistory.address(md, historyOffset));
    }

    final Word now = RVMThread.getCurrentThread().getDrEpoch();

    final Word r = AccessHistory.loadReadWord(md, historyOffset);
    if (r.EQ(now) || isFrozen(r)) {
      // Read in same epoch as last read.
      if (Dr.STATS) DrStats.readSameReadEpoch.inc();
      return;
    }
    if (Epoch.isMapRef(r)){
      final Word e = Dr.readers().get(Epoch.asMapRef(r), Epoch.tid(now));
      if (e.EQ(now)) {
        // Read in same epoch as last read by this thread.
        if (Dr.STATS) DrStats.readSharedSameEpoch.inc();
        return;
      } else if (Dr.config().drCasFineGrained() && !Epoch.isReserved(e)) {
        // Read in newer epoch than last read by this thread.
        // Check write hb read and CAS to record read.
        final Word w = AccessHistory.loadWriteWord(md, historyOffset);
        if (VC.epochHB(w, RVMThread.getCurrentThread().drThreadVC)
            && Dr.readers().attempt(Epoch.asMapRef(r), e, now)) {
          if (Dr.STATS) {
            if (Epoch.sameTid(e, now)) {
              DrStats.readSharedAgain.inc();
            } else {
              DrStats.readShared.inc();
            }
          }
          return;
        }
      }
    }

    readSlowPath(md, historyOffset, now);
  }

  private void readSlowPath(final Object md, final Offset historyOffset,
      final Word now) {
    
    // LOCK reader for slow path
    final Word r = AccessHistory.acquireReadWord(md, historyOffset);

    // GET writer
    final Word w = AccessHistory.loadWriteWord(md, historyOffset);

    final WordArray threadVC = RVMThread.getCurrentThread().drThreadVC;

    // Check write hb read.
    if (!VC.epochHB(w, threadVC)) {
      race(false, md, historyOffset, w, r);
      return;
//      if (Dr.config().fibPatchRaces()) {
//        if (Epoch.isMapRef(r)) {
//          Dr.readers().install(md, historyOffset, Epoch.asMapRef(r));
//        } else {
//          AccessHistory.releaseReadWord(md, historyOffset, r);
//        }
//        return;
//      }
    }

    if (Epoch.isEpoch(r)) {
      // Last read is epoch.
      if (VC.epochHB(r, threadVC)) {
        // Last read hb this read.
        // UNLOCK reader
        AccessHistory.releaseReadWord(md, historyOffset, now);
        if (Dr.STATS) {
          if (Epoch.sameTid(r, now)) {
            DrStats.readOwned.inc();
          } else if (Epoch.isNone(r)) {
            DrStats.readFirst.inc();
          } else {
            DrStats.readRemote.inc();
          }
        }
      } else {
        if (Dr.STATS) DrStats.readRemote.inc();
        // Inflate.
        // NOTE: GC MAY OCCUR HERE.  It may cause things to move.
        final WordArray map = Dr.readers().create();

        Dr.readers().set(map, r);
        Dr.readers().set(map, now);
        // UNLOCK reader
        // AccessHistory.setAndUnlockReadRef(md, historyOffset, readMap);
        Dr.readers().install(md, historyOffset, map);
        if (Dr.STATS) DrStats.readShare.inc();
      }
    } else if (Epoch.isMapRef(r)){
      // Set thread's entry in read map.
      Dr.readers().set(Epoch.asMapRef(r), now);
      // UNLOCK reader
      // NOTE: this should have a GC barrier in general, however, we elide it since:
      // 1. No yield points may have occurred since we replaced the pointer with
      //    the LOCKED word.
      // 2. We are writing back exactly the same pointer that was there previously.
      Dr.readers().install(md, historyOffset, Epoch.asMapRef(r));
      if (Dr.STATS) {
        if (Epoch.sameTid(now, Dr.readers().get(Epoch.asMapRef(r), Epoch.tid(now)))) {
          DrStats.readSharedAgain.inc();
        } else {
          DrStats.readShared.inc();
        }
      }
    } else {
      if (VM.VerifyAssertions) VM._assert(Epoch.isNone(r));
      if (Dr.STATS) DrStats.readFirst.inc();
      AccessHistory.releaseReadWord(md, historyOffset, now);
    }
  }

}
