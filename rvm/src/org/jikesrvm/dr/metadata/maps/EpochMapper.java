package org.jikesrvm.dr.metadata.maps;

import org.jikesrvm.VM;
import org.jikesrvm.dr.DrDebug;
import org.jikesrvm.dr.DrRuntime;
import org.jikesrvm.dr.metadata.AccessHistory;
import org.jikesrvm.dr.metadata.Epoch;
import org.jikesrvm.mm.mminterface.Barriers;
import org.jikesrvm.mm.mminterface.MemoryManager;
import org.jikesrvm.objectmodel.ObjectModel;
import org.jikesrvm.runtime.Magic;
import org.jikesrvm.scheduler.RVMThread;
import org.mmtk.plan.TraceLocal;
import org.mmtk.plan.TransitiveClosure;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.ObjectReference;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;
import org.vmmagic.unboxed.WordArray;

/**
 * A map from tid to epoch.
 * 
 * TODO: optimization  -- store epoch of last modifier of the whole map.
 * Often, checking that might eliminate expensive VC operations...
 *
 */
@Uninterruptible
public abstract class EpochMapper {
  
  public void assertValidMap(WordArray map) {}

  /**
   * Create an empty read map.
   * @return
   */
  public abstract WordArray create();
  
  // Accessing contents
  
  /**
   * Set the epoch for tid.  If 0 <= tid < RVMThread.nextSlot, this must succeed.
   * If tid is outside the range, it is allowed to crash.
   * @param tid
   * @param epoch
   */
  public abstract void set(WordArray readers, int tid, Word epoch);
  
  /**
   * Set epoch in readers.
   * @param readers
   * @param epoch
   */
  @Inline
  public void set(WordArray readers, Word epoch) {
    if (VM.VerifyAssertions) {
      VM._assert(!Epoch.isMapRef(epoch));
    }
    set(readers, Epoch.tid(epoch), epoch);
  }

  /**
   * Get the epoch for tid.  If 0 <= tid < RVMThread.nextSlot, this must succeed.
   * If there is no mapping for tid, but it is in this range, Epoch.NONE
   * must be returned.  If tid is outside the range, this may crash.
   * @param tid
   */
  public abstract Word get(WordArray map, int tid);
  
  /**
   * CAS tid's entry in map from oldEpoch to newEpoch.
   * @param map
   * @param tid
   * @param oldEpoch
   * @param newEpoch
   * @return
   */
  public abstract boolean attempt(WordArray map, int tid, Word oldEpoch, Word newEpoch);
  
  /**
   * CAS oldEpoch to newEpoch in map.
   * @param map
   * @param tid
   * @param oldEpoch
   * @param newEpoch
   * @return
   */
  @Inline
  public boolean attempt(WordArray map, Word oldEpoch, Word newEpoch) {
    if (VM.VerifyAssertions) {
      VM._assert(!Epoch.isReserved(newEpoch), "Cannot CAS in RESERVED without tid.");
      VM._assert(Epoch.isEpoch(oldEpoch) || Epoch.isNone(oldEpoch), "Do not CAS out RESERVED or ref.");
      if (!(Epoch.sameTid(oldEpoch, newEpoch) || Epoch.isNone(oldEpoch))) {
        DrDebug.lock();
        DrDebug.twrite();
        VM.sysWriteln("EpochMap Warning: mismatched old/new ", ObjectReference.fromObject(map));
        Epoch.print(ObjectReference.fromObject(map).toAddress().toWord());
        Epoch.print(oldEpoch);  VM.sysWriteln("  Old Epoch");
        Epoch.print(newEpoch);  VM.sysWriteln("  New Epoch");
        DrDebug.unlock();
      }
      VM._assert(Epoch.sameTid(oldEpoch, newEpoch) || Epoch.isNone(oldEpoch),
          "Must replace only epochs of same thread.");
    }
    return attempt(map, Epoch.tid(newEpoch), oldEpoch, newEpoch);
  }
  
  /**
   * Lock and return tid's entry in map.
   * @param map
   * @param tid
   * @return
   */
  public Word acquire(final WordArray map, final int tid) {
    Word w;
    int spins = 0;
    do {
      w = ObjectReference.fromObject(map).toAddress().prepareWord(Offset.fromIntSignExtend(tid << Epoch.LOG_BYTES_IN_EPOCH));
      if (VM.VerifyAssertions) {
        spins++;
        if (spins == 10000) {
          DrDebug.twriteln("Passed 10000 spins in EpochMapper.acquire");
        }
      }
    } while (Epoch.isReserved(w) || !attemptReserve(map, tid, w));
    if (VM.VerifyAssertions) {
      VM._assert(Epoch.isEpoch(w) || Epoch.isNone(w));
    }
    return w;
  }
  
  public abstract boolean attemptReserve(WordArray map, int tid, Word old);

  
  @Inline
  public void release(WordArray map, int tid, Word epoch) {
    if (VM.VerifyAssertions) {
      VM._assert(Epoch.isEpoch(epoch) || Epoch.isNone(epoch), "Can't release RESERVED or ref.");
      VM._assert(!Epoch.isEpoch(epoch) || Epoch.tid(epoch) == tid, "Mismatched epoch/tid.");
    }
    Magic.writeFloor();
    set(map, tid, epoch);
  }
  /**
   * Set and unlock tid's entry in map.
   * @param map
   * @param epoch
   */
  @Inline
  public void release(WordArray map, Word epoch) {
    if (VM.VerifyAssertions) {
      VM._assert(Epoch.isEpoch(epoch));
    }
    release(map, Epoch.tid(epoch), epoch);
  }
  
  // GC barriers and tracing
  
  /**
   * Install a read map into a read word for the first time.
   * 
   * Default: assumes map is an object reference, uses normal barrier behavior.
   * Override to change this.
   * 
   * @param md
   * @param mdOffset
   * @param map
   */
  @Inline
  public void installUnsync(Object md, Offset mdOffset, WordArray map) {
    if (VM.VerifyAssertions) {
      AccessHistory.assertValid(md, mdOffset);
      assertValidMap(map);
      // final Word old = ObjectReference.fromObject(md).toAddress().loadWord(mdOffset);
      // VM._assert(Epoch.isReserved(old) || Epoch.sameTid(old, RVMThread.getCurrentThread().getFibEpoch()));
    }
    Magic.writeFloor();
    final Offset readWordOffset = AccessHistory.readWordOffset(mdOffset);
    if (Barriers.NEEDS_OBJECT_PUTFIELD_BARRIER && md != null) {
      Barriers.objectFieldWrite(md, ObjectReference.fromObject(map).toObject(), readWordOffset, -1);
    } else if (Barriers.NEEDS_OBJECT_PUTSTATIC_BARRIER && md == null) {
      final Offset offset = readWordOffset.toWord().toAddress().diff(Magic.getTocPointer());
      Barriers.objectStaticWrite(ObjectReference.fromObject(map).toObject(), offset, -1);
    } else {
      ObjectReference.fromObject(md).toAddress().store(ObjectReference.fromObject(map), readWordOffset);
    }

  }

  /**
   * Install a read map into a read word for the first time.
   * 
   * Default: assumes map is an object reference, uses normal barrier behavior.
   * Override to change this.
   * 
   * @param md
   * @param mdOffset
   * @param map
   */
  @Inline
  public void install(Object md, Offset mdOffset, WordArray map) {
    if (VM.VerifyAssertions) {
      AccessHistory.assertValid(md, mdOffset);
      assertValidMap(map);
      // final Word old = ObjectReference.fromObject(md).toAddress().loadWord(mdOffset);
      // VM._assert(Epoch.isReserved(old) || Epoch.sameTid(old, RVMThread.getCurrentThread().getFibEpoch()));
    }
    Magic.writeFloor();
    final Offset readWordOffset = AccessHistory.readWordOffset(mdOffset);
    if (Barriers.NEEDS_OBJECT_PUTFIELD_BARRIER && md != null) {
      Barriers.objectFieldWrite(md, ObjectReference.fromObject(map).toObject(), readWordOffset, -1);
    } else if (Barriers.NEEDS_OBJECT_PUTSTATIC_BARRIER && md == null) {
      final Offset offset = readWordOffset.toWord().toAddress().diff(Magic.getTocPointer());
      Barriers.objectStaticWrite(ObjectReference.fromObject(map).toObject(), offset, -1);
    } else {
      ObjectReference.fromObject(md).toAddress().store(ObjectReference.fromObject(map), readWordOffset);
    }

  }
  /**
   * Install a read map into a read word for the first time.
   * 
   * Default: assumes map is an object reference, uses normal barrier behavior.
   * Override to change this.
   * 
   * @param md
   * @param mdOffset
   * @param map
   */
  @Inline
  public boolean attemptInstall(Object md, Offset mdOffset, Word epoch, WordArray map) {
    if (VM.VerifyAssertions) {
      AccessHistory.assertValid(md, mdOffset);
      assertValidMap(map);
      // final Word old = ObjectReference.fromObject(md).toAddress().loadWord(mdOffset);
      // VM._assert(Epoch.isReserved(old) || Epoch.sameTid(old, RVMThread.getCurrentThread().getFibEpoch()));
    }
    Magic.writeFloor();
    final Offset readWordOffset = AccessHistory.readWordOffset(mdOffset);
    if (Barriers.NEEDS_OBJECT_PUTFIELD_BARRIER && md != null) {
      Barriers.objectFieldWritePreBarrier(md, ObjectReference.fromObject(map).toObject(), readWordOffset, -1);
    } else if (Barriers.NEEDS_OBJECT_PUTSTATIC_BARRIER && md == null) {
      final Offset offset = readWordOffset.toWord().toAddress().diff(Magic.getTocPointer());
      Barriers.objectStaticWritePreBarrier(ObjectReference.fromObject(map).toObject(), offset, -1);
    }
    return ObjectReference.fromObject(md).toAddress().attempt(epoch, ObjectReference.fromObject(map).toAddress().toWord(), readWordOffset);
  }
  
  @Inline
  public void stash(WordArray map) {
    if (VM.VerifyAssertions) VM._assert(MemoryManager.validRef(ObjectReference.fromObject(map)));
    RVMThread.getCurrentThread().drEpochMapStash = map;
  }
  @Inline
  public WordArray unstash() {
    WordArray map = RVMThread.getCurrentThread().drEpochMapStash;
    if (map != null) RVMThread.getCurrentThread().drEpochMapStash = null;
    if (VM.VerifyAssertions) VM._assert(MemoryManager.validRef(ObjectReference.fromObject(map)));
    return map;
  }

  /**
   * Process a last-read slot in an object as a potential heap edge.
   * 
   * Default: assumes slot holds an object reference, uses normal barrier behavior.
   * Override to change this.
   * 
   * @param a
   */
  public void processEdgeGC(TransitiveClosure trace,
      ObjectReference md, Address readSlot, Word readWord) {
    // report a reachable epoch map.
    trace.processEdge(md, readSlot);
  }
  
  /**
   * Process a last-read static slot as a potential heap edge.
   * 
   * Default: assumes slot holds an object reference, uses normal barrier behavior.
   * Override to change this.
   * 
   * @param a
   */
  public void processRootEdgeGC(TraceLocal trace,
      Address readSlot, Word readWord) {
    // report a reachable epoch map.
    trace.processRootEdge(readSlot, true);
  }

  /**
   * Called while world is stopped before (full-heap) GC begins.
   */
  public void prepareGlobalGC() { }
  /**
   * Called while world is stopped after (full-heap) GC completes.
   */
  public void releaseGlobalGC() { }
  /**
   * Called with each thread (concurrently) while mutators are stopped
   * before (full-heap) GC begins.
   */
  public void preparePerThreadGC(RVMThread t) { }
  /**
   * Called with each thread (concurrently) while mutators are stopped
   * after (full-heap) GC completes.
   */
  public void releasePerThreadGC(RVMThread t) { }
  
  // Reporting
  
  /**
   * Print a read map.
   * @param readers
   */
  public void print(WordArray readers) {
    VM.sysWrite("{  ");
    final int bound = DrRuntime.maxLiveDrThreads();
    for (int tid = 0; tid < bound; tid++) {
      Word w = get(readers, tid);
      if (Epoch.isNone(w)) continue;
      if (Epoch.isMapRef(w)) {
        DrDebug.lock();
        DrDebug.twrite(); VM.sysWriteln("Found map ref ", w.toAddress(), " as entry in map ", ObjectReference.fromObject(readers).toAddress());
        DrDebug.twrite(); VM.sysWriteln("Outer map has type ", ObjectModel.getObjectType(ObjectReference.fromObject(readers).toObject()).getDescriptor());
        DrDebug.twrite(); VM.sysWriteln("Inner map has type ", ObjectModel.getObjectType(w.toAddress().toObjectReference().toObject()).getDescriptor());
        DrDebug.unlock();
        VM.sysFail("Found map as entry in epoch map.");
      }
      Epoch.print(w);
      if (Epoch.isReserved(w)) {
        VM.sysWrite("@T", tid);
      }
      VM.sysWrite("  ");
    }
    VM.sysWrite("}");
  }
}
