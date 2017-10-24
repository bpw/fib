package org.jikesrvm.dr.fasttrack;

import org.jikesrvm.VM;
import org.jikesrvm.dr.Dr;
import org.jikesrvm.dr.DrDebug;
import org.jikesrvm.dr.DrStats;
import org.jikesrvm.dr.metadata.AccessHistory;
import org.jikesrvm.dr.metadata.Epoch;
import org.jikesrvm.dr.metadata.VC;
import org.jikesrvm.scheduler.RVMThread;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.NoInline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.pragma.Unpreemptible;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;

@Uninterruptible
public abstract class FastTrack {
  /**
   * Write barrier.
   * 
   * @param mdObject
   * @param historyOffset - offset of access history from mdObject address.
   */
  @Unpreemptible
  public abstract void write(Object mdObject, Offset historyOffset);
  @Unpreemptible
  public void writeThin(Object mdObject, Offset historyOffset) {
    VM.sysFail("Not implemented.");
  }
  
  /**
   * Read barrier.  It is @Unpreemptible instead of @Uninterruptible because it
   * may need to allocate in the read-vc inflation case.
   * 
   * @param thread - thread performing this read
   * @param mdObject - object where metadata is stored (or null if static)
   * @param historyOffset - address of access history
   */
  @Unpreemptible
  public abstract void read(Object mdObject, Offset historyOffset);
  @Unpreemptible
  public void readThin(Object mdObject, Offset historyOffset) {
    VM.sysFail("Not implemented.");
  }
  
  @Unpreemptible
  @NoInline
  public final void writeNoInline(Object mdObject, Offset historyOffset) {
    write(mdObject, historyOffset);
  }
  @Unpreemptible
  @NoInline
  public final void readNoInline(Object mdObject, Offset historyOffset) {
    read(mdObject, historyOffset);
  }
  
  public void terminateThread(RVMThread dead) {
    DrDebug.lock();
    DrDebug.twriteln("Terminating.");
    VM.sysWrite(dead.getDrRaces());
    VM.sysWriteln(" races recorded");
    DrDebug.unlock();
  }
    
  /**
   * Report a race on an access that is a (isWrite ? write : read) in epoch with
   * threadVC to a variable whose access history is currently history.
   * @param isWrite
   * @param mdObject
   * @param historyOffset
   */
  public static void race(boolean isWrite, Object mdObject, Offset historyOffset) {
    if (Dr.STATS) {
      DrStats.races.inc();
    }
    if (Dr.config().drFirstRacePerLocation()) {
      freeze(mdObject, historyOffset);
    }
    if (RVMThread.getCurrentThread().incDrRaces() > 1L) return;
    if (Dr.REPORTS) {
      DrDebug.lock();
      DrDebug.twriteln("######## RACE (first in this thread) ########");
      DrDebug.twrite(); VM.sysWrite(isWrite ? " W " : " R ",
          AccessHistory.address(mdObject, historyOffset), " during ");
      Epoch.print(RVMThread.getCurrentThread().getDrEpoch());
      VM.sysWrite(", after ");  VC.print(RVMThread.getCurrentThread().drThreadVC); VM.sysWriteln();
      AccessHistory.print(mdObject, historyOffset);
      RVMThread.dumpStack();
      DrDebug.twriteln("#############################################");
      DrDebug.unlock();
    }
  }

  public static void race(boolean isWrite, Object mdObject, Offset historyOffset, Word lw, Word lr) {
    if (Dr.STATS) {
      DrStats.races.inc();
    }
    if (Dr.config().drFirstRacePerLocation()) {
      freeze(mdObject, historyOffset);
    }
    if (RVMThread.getCurrentThread().incDrRaces() > 1L) return;
    if (Dr.REPORTS) {
      DrDebug.lock();
      DrDebug.twriteln("######## RACE (first in this thread) ########");
      DrDebug.twrite(); VM.sysWrite(isWrite ? " W " : " R ",
          AccessHistory.address(mdObject, historyOffset), " during ");
      Epoch.print(RVMThread.getCurrentThread().getDrEpoch());
      VM.sysWrite(", after ");  VC.print(RVMThread.getCurrentThread().drThreadVC); VM.sysWriteln();
      AccessHistory.print(lw, lr);
      RVMThread.dumpStack();
      DrDebug.twriteln("#############################################");
      DrDebug.unlock();
    }
  } 

  
  /**
   * Set read and write word to origin epoch (Epoch.TAG) that happens before
   * all non-zero epochs.  Will cause all HB analyses to treat as in same
   * epoch and pass all later checks cheaply with no updates.
   * @param md
   * @param historyOffset
   */
  public static void freeze(Object md, Offset historyOffset) {
    AccessHistory.storeReadWord(md, historyOffset, Epoch.ORIGIN);
    AccessHistory.storeWriteWord(md, historyOffset, Epoch.ORIGIN);
  }
  
  /**
   * Check if epoch is frozen.
   * @param e
   * @return
   */
  @Inline
  public static boolean isFrozen(Word e) {
    return Dr.config().drFirstRacePerLocation() && Epoch.isOrigin(e);
  }
}
