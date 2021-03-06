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
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;
import org.vmmagic.unboxed.WordArray;

/**
 * This FastTrack is unsynchronized and tries to match the original
 * as closely as possible.
 *
 */
@Uninterruptible
public final class OrigUnsyncFastTrack extends FastTrack {
    
  @Inline
  @Override
  public void write(Object md, Offset historyOffset) {
    final Word now = RVMThread.getCurrentThread().getDrEpoch();

    // WRITE SAME EPOCH
    final Word lastWriteEpoch = AccessHistory.loadWriteWord(md, historyOffset);

    if (lastWriteEpoch.EQ(now) || isFrozen(lastWriteEpoch)) {
      if (Dr.STATS) DrStats.writeSameWriteEpoch.inc();
      return;
    }

    WordArray vcNow = RVMThread.getCurrentThread().drThreadVC;
    if (VC.epochHB(lastWriteEpoch, vcNow)) {
      Word lastReadWord = AccessHistory.loadReadWord(md, historyOffset);
      if (VC.epochHB(lastReadWord, vcNow)) {
        // WRITE EXCLUSIVE
        AccessHistory.storeWriteWord(md, historyOffset, now);
        if (Dr.STATS) DrStats.writeRemote.inc(); // stand-in for excl
        return;
      } else {
        writeShared(md, historyOffset, lastReadWord, now, vcNow);
        return;
      }
    }
    race(true, md, historyOffset);
  }
  
  private void writeShared(Object md, Offset historyOffset,
      final Word lastReadWord, final Word now, final WordArray vcNow) {
    if (Epoch.isMapRef(lastReadWord) && VC.hb(Epoch.asMapRef(lastReadWord), vcNow)) {
      // WRITE SHARED
      AccessHistory.storeWriteWord(md, historyOffset, now);
      AccessHistory.storeReadWord(md, historyOffset, Epoch.NONE);
      if (Dr.STATS) DrStats.writeShared.inc();
      return;
    }
    race(true, md, historyOffset);
  }


  
  @Inline
  @Override
  public void writeThin(Object md, Offset historyOffset) {
    final Word now = RVMThread.getCurrentThread().getDrEpoch();

    // WRITE SAME EPOCH
    final Word lastWriteEpoch = AccessHistory.loadWriteWord(md, historyOffset);

    if (lastWriteEpoch.EQ(now) || isFrozen(lastWriteEpoch)) {
      if (Dr.STATS) DrStats.writeSameWriteEpoch.inc();
      return;
    }

    writeSlowPath(md, historyOffset, lastWriteEpoch, now);
  }


  private void writeSlowPath(Object md, Offset historyOffset,
      final Word lastWriteEpoch, final Word now) {
    WordArray vcNow = RVMThread.getCurrentThread().drThreadVC;
    if (VC.epochHB(lastWriteEpoch, vcNow)) {
      Word lastReadWord = AccessHistory.loadReadWord(md, historyOffset);
      if (VC.epochHB(lastReadWord, vcNow)) {
        // WRITE EXCLUSIVE
        AccessHistory.storeWriteWord(md, historyOffset, now);
        if (Dr.STATS) DrStats.writeRemote.inc(); // stand-in for excl
        return;
      } else if (Epoch.isMapRef(lastReadWord) && VC.hb(Epoch.asMapRef(lastReadWord), vcNow)) {
        // WRITE SHARED
        AccessHistory.storeWriteWord(md, historyOffset, now);
        AccessHistory.storeReadWord(md, historyOffset, Epoch.NONE);
        if (Dr.STATS) DrStats.writeShared.inc();
        return;
      }
    }
    race(true, md, historyOffset);
  }

  @Inline
  @Unpreemptible
  @Override
  public void read(final Object md, final Offset historyOffset) {

    final Word now = RVMThread.getCurrentThread().getDrEpoch();
    final Word lastReadWord = AccessHistory.loadReadWord(md, historyOffset);

    // READ SAME EPOCH
    if (lastReadWord.EQ(now) || isFrozen(lastReadWord)) {
      if (Dr.STATS) DrStats.readSameReadEpoch.inc();
      return;
    }

    final WordArray vcNow = RVMThread.getCurrentThread().drThreadVC;

    Word lastWriteEpoch = AccessHistory.loadWriteWord(md, historyOffset);
    if (VC.epochHB(lastWriteEpoch, vcNow)) {
      if (Epoch.isEpoch(lastReadWord) || Epoch.isNone(lastReadWord)) {
        if (VC.epochHB(lastReadWord, vcNow)) {
          // READ EXCLUSIVE
          AccessHistory.storeReadWord(md, historyOffset, now);
          if (Dr.STATS) DrStats.readRemote.inc(); // stand-in for excl
        } else {
          readShare(md, historyOffset, lastReadWord, now);
        }
      } else {
        // READ SHARED
        if (VM.VerifyAssertions) VM._assert(Epoch.isMapRef(lastReadWord));
        Dr.readers().set(Epoch.asMapRef(lastReadWord), now);
        if (Dr.STATS) DrStats.readShared.inc();
      }
      return;
    }
    race(false, md, historyOffset);
  }

  @Unpreemptible
  private void readShare(final Object md, final Offset historyOffset, final Word lastReadWord, final Word now) {
    // READ SHARE
    WordArray map = Dr.readers().create();
    Dr.readers().set(map, lastReadWord);
    Dr.readers().set(map, now);
    Dr.readers().install(md, historyOffset, map);
    if (Dr.STATS) DrStats.readShare.inc();
  }
  
  @Inline
  @Unpreemptible
  @Override
  public void readThin(final Object md, final Offset historyOffset) {

    final Word epoch = RVMThread.getCurrentThread().getDrEpoch();
    final Word lastReadWord = AccessHistory.loadReadWord(md, historyOffset);

    // READ SAME EPOCH
    if (lastReadWord.EQ(epoch) || isFrozen(lastReadWord)) {
      if (Dr.STATS) DrStats.readSameReadEpoch.inc();
      return;
    }

    readSlowPath(md, historyOffset, lastReadWord, epoch);
  }

  private void readSlowPath(final Object md, final Offset historyOffset,
      final Word lastReadWord, final Word now) {

    final WordArray vcNow = RVMThread.getCurrentThread().drThreadVC;

    Word lastWriteEpoch = AccessHistory.loadWriteWord(md, historyOffset);
    if (VC.epochHB(lastWriteEpoch, vcNow)) {
      if (Epoch.isEpoch(lastReadWord) || Epoch.isNone(lastReadWord)) {
        if (VC.epochHB(lastReadWord, vcNow)) {
          // READ EXCLUSIVE
          AccessHistory.storeReadWord(md, historyOffset, now);
          if (Dr.STATS) DrStats.readRemote.inc(); // stand-in for excl
        } else {
          readShare(md, historyOffset, now, lastReadWord);
        }
      } else {
        // READ SHARED
        if (VM.VerifyAssertions) VM._assert(Epoch.isMapRef(lastReadWord));
        Dr.readers().set(Epoch.asMapRef(lastReadWord), now);
        if (Dr.STATS) DrStats.readShared.inc();
      }
      return;
    }
    race(false, md, historyOffset);
  }

}
