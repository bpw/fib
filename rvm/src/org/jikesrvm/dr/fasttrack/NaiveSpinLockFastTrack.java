package org.jikesrvm.dr.fasttrack;

import org.jikesrvm.VM;
import org.jikesrvm.dr.Dr;
import org.jikesrvm.dr.DrStats;
import org.jikesrvm.dr.metadata.AccessHistory;
import org.jikesrvm.dr.metadata.Epoch;
import org.jikesrvm.dr.metadata.VC;
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
 * to lock it during analysis.  It does this for EVERY access, disregarding even
 * same-epoch optimizations for CAS-omission...
 * 
 * It does NOT ensure check-access atomicity.
 * 
 * TODO respect epoch collapse optimization
 * 
 */
@Uninterruptible
public final class NaiveSpinLockFastTrack extends FastTrack {

  private static final boolean PRINT = false;

  @Inline
  @Override
  public void write(final Object md, final Offset historyOffset) {
    if (PRINT) {
      VM.sysWriteln("T", RVMThread.getCurrentThread().getDrID(), " W ", 
          AccessHistory.address(md, historyOffset));
    }
    final Word epoch = RVMThread.getCurrentThread().getDrEpoch();

    // LOCK reader
    final Word lastReadEpoch = AccessHistory.acquireReadWord(md, historyOffset);

    if (Epoch.sameTid(lastReadEpoch, epoch) || Epoch.isNone(lastReadEpoch) || isFrozen(lastReadEpoch)) {
      if (Dr.STATS) (Epoch.isNone(lastReadEpoch) ? DrStats.writeFirst : DrStats.writeOwned).inc();
      AccessHistory.storeWriteWord(md, historyOffset, epoch);
      AccessHistory.releaseReadWord(md, historyOffset, epoch);
      return;      
    }

    writeSlowPath(md, historyOffset, lastReadEpoch, epoch);

  }
  public void writeSlowPath(final Object md, final Offset historyOffset, final Word lastReadWord, final Word epoch) {

    final WordArray threadVC = RVMThread.getCurrentThread().drThreadVC;

    final boolean isRef = Epoch.isMapRef(lastReadWord);

    if (Dr.STATS) {
      if (isRef) {
        DrStats.writeShared.inc();
      } else if (Epoch.sameTid(lastReadWord, epoch)){
        DrStats.writeOwned.inc();
      } else if (Epoch.isNone(lastReadWord)) {
        DrStats.writeFirst.inc();
      } else {
        DrStats.writeRemote.inc();
      }
    }

    if (isRef
        ? !VC.hb(Epoch.asMapRef(lastReadWord), threadVC)
            : !VC.epochHB(lastReadWord, threadVC)) {
      race(true, md, historyOffset);
      return;
//      if (Dr.config().fibPatchRaces()) {
//        AccessHistory.releaseReadWord(md, historyOffset, lastReadWord);
//        return;
//      }
    }

    // SET writer
    AccessHistory.storeWriteWord(md, historyOffset, epoch);
    // UNLOCK reader
    AccessHistory.releaseReadWord(md, historyOffset, epoch);
  }

  @Inline
  @Unpreemptible
  @Override
  public void read(final Object md, final Offset historyOffset) {
    if (PRINT) {
      VM.sysWriteln("T", RVMThread.getCurrentThread().getDrID(), " R ", 
          AccessHistory.address(md, historyOffset));
    }

    final Word epoch = RVMThread.getCurrentThread().getDrEpoch();

    // LOCK reader
    final Word lastReadEpoch = AccessHistory.acquireReadWord(md, historyOffset);

    if (Epoch.sameTid(lastReadEpoch, epoch) || Epoch.isNone(lastReadEpoch) || isFrozen(lastReadEpoch)) {
      if (Dr.STATS) (Epoch.isNone(lastReadEpoch) ? DrStats.readFirst : DrStats.readOwned).inc();
      AccessHistory.releaseReadWord(md, historyOffset, epoch);
      return;
    }

    final boolean isRef = Epoch.isMapRef(lastReadEpoch);

    if (isRef){
      if (Dr.readers().get(Epoch.asMapRef(lastReadEpoch), Epoch.tid(epoch)).EQ(epoch)) {
        // This thread already read and others have.
        // UNLOCK reader
        // NOTE: this should have a barrier in general, however, we elide it since:
        // 1. No yield points may have occured since we replaced the pointer with
        //    the LOCKED word.
        // 2. We are writing back exactly the same pointer that was there previously.
        AccessHistory.releaseReadWord(md, historyOffset, lastReadEpoch);
        if (Dr.STATS) DrStats.readSharedSameEpoch.inc();
        return;
      }
    }

    readSlowPath(md, historyOffset, epoch, lastReadEpoch, isRef);
  }

  private void readSlowPath(final Object md, final Offset historyOffset,
      final Word epoch, final Word lastReadEpoch, final boolean isRef) {

    // GET writer
    final Word lastWriteEpoch = AccessHistory.loadWriteWord(md, historyOffset);

    final WordArray threadVC = RVMThread.getCurrentThread().drThreadVC;

    if (!VC.epochHB(lastWriteEpoch, threadVC)) {
      race(false, md, historyOffset);
      return;
//      if (Dr.config().fibPatchRaces()) {
//        if (isRef) {
//          AccessHistory.releaseReadWord(md, historyOffset, lastReadEpoch);
//        } else {
//          AccessHistory.releaseReadWord(md, historyOffset, lastReadEpoch);
//        }
//        return;
//      }
    }

    if (!isRef) {
      if (VC.epochHB(lastReadEpoch, threadVC)) {
        // This thread dominates the last read epoch, so overwrite it.
        // UNLOCK reader
        AccessHistory.releaseReadWord(md, historyOffset, epoch);
        if (Dr.STATS) {
          if (Epoch.sameTid(lastReadEpoch, epoch)) {
            DrStats.readOwned.inc();
          } else if (Epoch.isNone(lastReadEpoch)) {
            DrStats.readFirst.inc();
          } else {
            DrStats.readRemote.inc();
          }
        }
      } else {
        if (Dr.STATS) DrStats.readRemote.inc();
        // Inflate.
        // NOTE: GC MAY OCCUR HERE.  It may cause things to move.
        final WordArray readMap = Dr.readers().create();

        Dr.readers().set(readMap, lastReadEpoch);
        Dr.readers().set(readMap, epoch);
        // UNLOCK reader
        Dr.readers().install(md, historyOffset, readMap);
        if (Dr.STATS) DrStats.readShare.inc();
      }
    } else {
      // Set thread's entry in read map.
      Dr.readers().set(Epoch.asMapRef(lastReadEpoch), epoch);
      // UNLOCK reader
      // NOTE: this should have a GC barrier in general, however, we elide it since:
      // 1. No yield points may have occured since we replaced the pointer with
      //    the LOCKED word.
      // 2. We are writing back exactly the same pointer that was there previously.
      AccessHistory.releaseReadWord(md, historyOffset, lastReadEpoch);
      if (Dr.STATS) DrStats.readShared.inc();
    }
  }

}
