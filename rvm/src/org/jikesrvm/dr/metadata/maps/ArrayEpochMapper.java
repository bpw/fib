package org.jikesrvm.dr.metadata.maps;

import org.jikesrvm.VM;
import org.jikesrvm.classloader.RVMType;
import org.jikesrvm.dr.DrDebug;
import org.jikesrvm.dr.metadata.Epoch;
import org.jikesrvm.mm.mminterface.MemoryManager;
import org.jikesrvm.objectmodel.ObjectModel;
import org.jikesrvm.objectmodel.TIB;
import org.jikesrvm.runtime.Magic;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.NoInline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.pragma.UninterruptibleNoWarn;
import org.vmmagic.unboxed.ObjectReference;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;
import org.vmmagic.unboxed.WordArray;

/**
 * Standard vector-clock representation of read maps: an array of epochs.
 * Consecutive threads' entries are consecutive in memory.
 * 
 * @author bpw
 *
 */
@Uninterruptible
public final class ArrayEpochMapper extends EpochMapper {
  
  @NoInline
  public void assertValidMap(WordArray map) {
    VM._assert(MemoryManager.validRef(ObjectReference.fromObject(map)), "Bad epoch map reference.");
    TIB tib = ObjectModel.getTIB(ObjectReference.fromObject(map));
    if (tib != RVMType.WordArrayType.getTypeInformationBlock()) {
      DrDebug.lock();
      DrDebug.twrite(); VM.sysWriteln("Map (WordArray) expected, found ", tib.getType().getTypeRef().getName());
      DrDebug.unlock();
      VM.sysFail("Invalid map type.");
    }
  }
  @NoInline
  public void nullAndBoundsCheck(WordArray map, int tid) {
    assertValidMap(map);
    if (!(map != null && tid >= 0 && tid < map.length())) {
      DrDebug.lock();
      DrDebug.twrite();
      VM.sysWriteln("ArrayEpochMap fail tid = ", tid);
      VM.sysWriteln("                length = ", map.length());
      DrDebug.unlock();
      VM.sysFail("Bounds error on epoch map.");
    }
  }

  @Inline
  @Override
  public void set(WordArray map, int tid, Word epoch) {
    if (VM.VerifyAssertions) {
      nullAndBoundsCheck(map, tid);
      VM._assert(!Epoch.isMapRef(epoch));
    }
    if (Epoch.isMapRef(epoch)) {
      DrDebug.lock();
      DrDebug.twrite(); VM.sysWriteln("Found map ref ", epoch.toAddress(), " as entry in map ", ObjectReference.fromObject(map).toAddress());
      DrDebug.twrite(); VM.sysWriteln("Outer map has type ", ObjectModel.getObjectType(ObjectReference.fromObject(map).toObject()).getDescriptor());
      DrDebug.twrite(); VM.sysWriteln("Inner map has type ", ObjectModel.getObjectType(epoch.toAddress().toObjectReference().toObject()).getDescriptor());
      DrDebug.unlock();
      VM.sysFail("Found map as entry in epoch map.");
    }
    ObjectReference.fromObject(map).toAddress().store(epoch, Offset.fromIntSignExtend(tid << Epoch.LOG_BYTES_IN_EPOCH));
  }

  @Inline
  @Override
  public Word get(WordArray map, int tid) {
    if (VM.VerifyAssertions) nullAndBoundsCheck(map, tid);
    return ObjectReference.fromObject(map).toAddress().loadWord(Offset.fromIntSignExtend(tid << Epoch.LOG_BYTES_IN_EPOCH));
  }
  
  @Inline
  @Override
  public boolean attempt(WordArray map, int tid, Word oldEpoch, Word newEpoch) {
    if (VM.VerifyAssertions) nullAndBoundsCheck(map, tid);
    return ObjectReference.fromObject(map).toAddress().attempt(oldEpoch, newEpoch, Offset.fromIntSignExtend(tid << Epoch.LOG_BYTES_IN_EPOCH));
  }
  
  @Inline
  @Override
  public boolean attemptReserve(WordArray map, int tid, Word old) {
    if (VM.VerifyAssertions) nullAndBoundsCheck(map, tid);
    return Epoch.attemptReserve(map, Offset.fromIntSignExtend(tid << Epoch.LOG_BYTES_IN_EPOCH), old);
  }

  @Override
  @UninterruptibleNoWarn("Allocates WordArray")
  public WordArray create() {
//    Address map = unstash();
//    if (map.isZero()) return map;

    Magic.writeFloor();
    MemoryManager.startAllocatingInUninterruptibleCode();
    final WordArray map = WordArray.create(Epoch.MAX_THREADS << Epoch.LOG_WORDS_IN_EPOCH);
    MemoryManager.stopAllocatingInUninterruptibleCode();
    Magic.readCeiling();
    return map;
  }
}
