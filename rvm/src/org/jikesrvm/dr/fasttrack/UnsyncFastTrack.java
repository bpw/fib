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
 * This FastTrack does not do any synchronization to ensure the integrity
 * of analysis accesses to metadata, nor does it ensure check-access atomicity.
 *
 */
@Uninterruptible
public final class UnsyncFastTrack extends FastTrack {
  
  private static final boolean PRINT = false;
  
  @Inline
  @Override
  public void write(Object md, Offset historyOffset) {
    if (PRINT) {
      VM.sysWriteln("T", RVMThread.getCurrentThread().getDrID(), " W ", 
          AccessHistory.address(md, historyOffset));
    }
    final Word epoch = RVMThread.getCurrentThread().getDrEpoch();
    if (VM.VerifyAssertions) {
      VM._assert(Epoch.isEpoch(epoch));
    }
    final Word lastWriteEpoch = AccessHistory.loadWriteWord(md, historyOffset);

    if (lastWriteEpoch.EQ(epoch) || isFrozen(lastWriteEpoch)) {
      if (Dr.STATS) DrStats.writeSameWriteEpoch.inc();
      return;
    }

    Word lastReadWord = AccessHistory.loadReadWord(md, historyOffset);

    if (lastReadWord.EQ(epoch)) {
      AccessHistory.storeWriteWord(md, historyOffset, epoch);
      if (Dr.STATS) DrStats.writeSameReadEpoch.inc();
      return;
    }

    if (Epoch.sameTid(lastReadWord, epoch)) {
      AccessHistory.storeWriteWord(md, historyOffset, epoch);
      AccessHistory.storeReadWord(md, historyOffset, epoch);
      if (Dr.STATS) DrStats.writeOwned.inc();
      return;
    }

    writeSlowPath(md, historyOffset, lastWriteEpoch, epoch);
  }
  
  @Inline
  @Override
  public void writeThin(Object md, Offset historyOffset) {
    final Word epoch = RVMThread.getCurrentThread().getDrEpoch();
    final Word lastWriteEpoch = AccessHistory.loadWriteWord(md, historyOffset);

    if (lastWriteEpoch.EQ(epoch) || isFrozen(lastWriteEpoch)) {
      if (Dr.STATS) DrStats.writeSameWriteEpoch.inc();
      return;
    }
    writeMedPath(md, historyOffset, lastWriteEpoch, epoch);
  }
  
  private void writeMedPath(Object md, Offset historyOffset, Word lastWriteEpoch, Word epoch) {
    Word lastReadWord = AccessHistory.loadReadWord(md, historyOffset);

    if (lastReadWord.EQ(epoch)) {
      AccessHistory.storeWriteWord(md, historyOffset, epoch);
      if (Dr.STATS) DrStats.writeSameReadEpoch.inc();
      return;
    }

    if (Epoch.sameTid(lastReadWord, epoch)) {
      AccessHistory.storeWriteWord(md, historyOffset, epoch);
      AccessHistory.storeReadWord(md, historyOffset, epoch);
      if (Dr.STATS) DrStats.writeOwned.inc();
      return;
    }
    
    writeSlowPath(md, historyOffset, lastWriteEpoch, epoch);
  }

  private void writeSlowPath(Object md, Offset historyOffset, final Word lastWriteEpoch,
      final Word epoch) {
    final WordArray threadVC = RVMThread.getCurrentThread().drThreadVC;
    final Word lastReadEpoch = AccessHistory.loadReadWord(md, historyOffset);

    final boolean isRef = Epoch.isMapRef(lastReadEpoch);
    
    if (Dr.STATS) {
      if (isRef) {
        DrStats.writeShared.inc();
      } else if (Epoch.sameTid(lastReadEpoch, epoch)){
        DrStats.writeOwned.inc();
      } else if (Epoch.isNone(lastReadEpoch)) {
        DrStats.writeFirst.inc();
      } else {
        DrStats.writeRemote.inc();
      }
    }

    if (isRef
        ? !VC.hb(Epoch.asMapRef(lastReadEpoch), threadVC)
            : !VC.epochHB(lastReadEpoch, threadVC)) {
      race(true, md, historyOffset);
      return;
//      if (Dr.config().fibPatchRaces()) {
//        return;
//      }
    }
    
    AccessHistory.storeWriteWord(md, historyOffset, epoch);
    AccessHistory.storeReadWord(md, historyOffset, epoch);
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
    final Word lastReadWord = AccessHistory.loadReadWord(md, historyOffset);

    if (lastReadWord.EQ(epoch) || isFrozen(lastReadWord)) {
      if (Dr.STATS) DrStats.readSameReadEpoch.inc();
      return;
    }

    if (Epoch.sameTid(lastReadWord, epoch)) {
      AccessHistory.storeReadWord(md, historyOffset, epoch);
      if (Dr.STATS) DrStats.readOwned.inc();
      return;
    }

    if (Epoch.isMapRef(lastReadWord)) {
      final Word myLastReadEpoch = Dr.readers().get(Epoch.asMapRef(lastReadWord), Epoch.tid(epoch));
      if (myLastReadEpoch.EQ(epoch)) {
        if (Dr.STATS) DrStats.readSharedSameEpoch.inc();
        return;
      } else if (!Epoch.isNone(myLastReadEpoch)) {
        if (Dr.STATS) DrStats.readSharedAgain.inc();
        Dr.readers().set(Epoch.asMapRef(lastReadWord), epoch);
        return;
      }
    }
    

    readSlowPath(md, historyOffset, lastReadWord, epoch);
  }
  
  @Inline
  @Override
  @Unpreemptible
  public void readThin(Object md, Offset historyOffset) {
    final Word epoch = RVMThread.getCurrentThread().getDrEpoch();
    final Word lastReadWord = AccessHistory.loadReadWord(md, historyOffset);

    if (lastReadWord.EQ(epoch) || isFrozen(lastReadWord)) {
      if (Dr.STATS) DrStats.readSameReadEpoch.inc();
      return;
    }

    readMedPath(md, historyOffset, lastReadWord, epoch);
  }
  
  private void readMedPath(Object md, Offset historyOffset, Word lastReadWord, Word epoch) {
    if (Epoch.sameTid(lastReadWord, epoch)) {
      AccessHistory.storeReadWord(md, historyOffset, epoch);
      if (Dr.STATS) DrStats.readOwned.inc();
      return;
    }

    if (Epoch.isMapRef(lastReadWord)) {
      final Word myLastReadEpoch = Dr.readers().get(Epoch.asMapRef(lastReadWord), Epoch.tid(epoch));
      if (myLastReadEpoch.EQ(epoch)) {
        if (Dr.STATS) DrStats.readSharedSameEpoch.inc();
        return;
      } else if (!Epoch.isNone(myLastReadEpoch)) {
        if (Dr.STATS) DrStats.readSharedAgain.inc();
        Dr.readers().set(Epoch.asMapRef(lastReadWord), epoch);
        return;
      }
    }
    
    readSlowPath(md, historyOffset, lastReadWord, epoch);
  }

  private void readSlowPath(final Object md, final Offset historyOffset,
      final Word epoch, final Word lastReadWord) {

    final WordArray threadVC = RVMThread.getCurrentThread().drThreadVC;

    if (Epoch.isMapRef(lastReadWord)) {
      final Word lastWriteEpoch = AccessHistory.loadWriteWord(md, historyOffset);
      if (!VC.epochHB(lastWriteEpoch, threadVC)) {
        race(false, md, historyOffset);
        return;
//        if (Dr.config().fibPatchRaces()) {
//          return;
//        }
      }
      // Set thread's entry in read map.
      Dr.readers().set(Epoch.asMapRef(lastReadWord), epoch);
      if (Dr.STATS) DrStats.readShared.inc();
      return;
    } else  {
      if (VC.epochHB(lastReadWord, threadVC)) {
        if (Dr.STATS) DrStats.readRemote.inc();
        AccessHistory.storeReadWord(md, historyOffset, epoch);
        return;
      } else {
        final Word lastWriteEpoch = AccessHistory.loadWriteWord(md, historyOffset);
        if (!VC.epochHB(lastWriteEpoch, threadVC)) {
          race(false, md, historyOffset);
          return;
//          if (Dr.config().fibPatchRaces()) {
//            return;
//          }
        }
        if (Dr.STATS) DrStats.readRemote.inc();
        // Inflate.
        // NOTE: GC MAY OCCUR HERE.  It may cause things to move.
        final WordArray readMap = Dr.readers().create();
        Dr.readers().set(readMap, lastReadWord);
        Dr.readers().set(readMap, epoch);
        Dr.readers().installUnsync(md, historyOffset, readMap);
        if (Dr.STATS) DrStats.readShare.inc();
        return;
      }
    }
    
  }

}
