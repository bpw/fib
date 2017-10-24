package org.jikesrvm.octet;

import org.jikesrvm.Constants;
import org.jikesrvm.VM;
import org.jikesrvm.runtime.Entrypoints;
import org.jikesrvm.runtime.Magic;
import org.jikesrvm.scheduler.RVMThread;
import org.vmmagic.pragma.Entrypoint;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.NoInline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.ObjectReference;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;

/** Octet: representation of Octet state in Octet metadata */
@Uninterruptible
public class OctetState implements Constants {

  // General layout for all:
  // lowest 3 bits: state (lowest bit is 0, which is a Jikes requirement)
  // highest 2 bits: 11 for read-shared; 00, 01, or 10 for all others
  // In current implementation, the highest 2 bits cannot be 00,
  // because we limit the RVMThread address to be at least 0x40000000.
  
  // Layout for write-excl, read-excl, and intermediate:
  // bit 0: 0 (Jikes requirement)
  // bit 1: 0 if write-excl; 1 if read-excl or intermediate
  // bit 2: 1 if write-excl or read-excl (works nicely because this bit is always 1 in the RVMThread address); 0 if intermediate
  // high 29 bits: high 29 bits of owner RVMThread object (won't be 11 because that maps to kernel addresses)

  // Thus, for write-excl, metadata == RVMThread address
  
  // Layout for read-shared:
  // lowest 3 bits: 000
  // middle bits: value of global read-shared counter when object become read-shared
  // highest 2 bits: 11
  
  // Thus, metadata is read-shared iff metadata >= 1100..00 (binary)
  
  static final int STATE_BITS = 3;
  static final int READ_SHARED_HIGH_BITS = 2;

  static final Word WRITE_EXCL = Word.fromIntZeroExtend(0x4); // 100
  static final Word READ_EXCL = Word.fromIntZeroExtend(0x6); // 110
  static final Word READ_SHARED = Word.fromIntZeroExtend(0x0); // 000
  static final Word INTERMEDIATE = Word.fromIntZeroExtend(0x2); // 010

  private static final Word EXCL_MASK = Word.fromIntZeroExtend(0x4); // 100
  private static final Word INTERMEDIATE_XOR_MASK = Word.fromIntZeroExtend(0x6); // 110
  static final Word STATE_MASK = Word.one().lsh(STATE_BITS).minus(Word.one()); // 111
  public static final Word THREAD_MASK_FOR_EXCL_STATE = Word.fromIntZeroExtend(~0x3); // 111..11100

  // read shared counter stuff; relies on top 2 bits being available (not used by other states)
  public static final Word MIN_READ_SHARED = Word.max().lsh(BITS_IN_WORD - READ_SHARED_HIGH_BITS); // 1100...00
  public static final Word MAX_READ_SHARED = Word.max().lsh(STATE_BITS); // 11...11000
  public static final Word READ_SHARED_DEC = Word.one().lsh(STATE_BITS); // 000...001000
  @Entrypoint
  public static Word globalReadSharedCounter = MAX_READ_SHARED;
  
  // Changes for RdEx -> RdSh transition
  // Values to lock the read shared counter: 000...0 means locked  
  public static final Word READ_SHARED_COUNTER_LOCKED = Word.zero();
  
  // constructing metadata for various states and threads:
  
  @Inline
  static final Word getExclusive(Word state) {
    if (VM.VerifyAssertions) { VM._assert(state.EQ(WRITE_EXCL) || state.EQ(READ_EXCL)); }
    return ObjectReference.fromObject(RVMThread.getCurrentThread()).toAddress().toWord().or(state);
  }
  
  @Inline
  public static final Word getInitial() {
    if (VM.runningVM) {
      return getExclusive(WRITE_EXCL);
    } else {
      // The "uninitialized state"
      return MIN_READ_SHARED;
    }
  }
  
  @Inline
  public static final Word getIntermediate() {
    return ObjectReference.fromObject(RVMThread.getCurrentThread()).toAddress().toWord().xor(INTERMEDIATE_XOR_MASK);
  }
  
  @Inline
  static final Word decReadSharedCounter() {
    // first decrement the counter
    Word oldValue;
    Word newValue;
    Offset offset = Entrypoints.octetGlobalReadSharedCounterField.getOffset(); 
    do {
      oldValue = Magic.getTocPointer().prepareWord(offset);
      newValue = oldValue.minus(READ_SHARED_DEC);
    } while (!Magic.getTocPointer().attempt(oldValue, newValue, offset));
    return newValue;
  }
  
  @Inline
  static final Word lockReadSharedCounter() {
    Word oldValue;
    Offset offset = Entrypoints.octetGlobalReadSharedCounterField.getOffset();
    boolean result = false;
    do {
      oldValue = Magic.getTocPointer().prepareWord(offset);
      // If the gRdShCtr is already locked, wait until it's unlocked.
      if (oldValue.NE(READ_SHARED_COUNTER_LOCKED)) {
        Word newValue = READ_SHARED_COUNTER_LOCKED;
        result = Magic.getTocPointer().attempt(oldValue, newValue, offset);
      }
    } while (!result);
    return oldValue;
  }
  
  // queries about states:
  
  @Inline
  public static final boolean isWriteExclForCurrentThread(Word metadata) {
    return metadata.EQ(ObjectReference.fromObject(RVMThread.getCurrentThread()).toAddress().toWord());
  }
  
  @Inline
  public static final boolean isWriteExclForGivenThread(RVMThread thread, Word metadata) {
    return metadata.EQ(ObjectReference.fromObject(thread).toAddress().toWord());
  }
  
  @Inline
  public static final boolean isReadExcl(Word metadata) {
    return metadata.and(STATE_MASK).EQ(READ_EXCL);
  }

  @Inline
  public static final boolean isExclForCurrentThread(Word metadata) {
    Word thread = ObjectReference.fromObject(RVMThread.getCurrentThread()).toAddress().toWord();
    return metadata.and(THREAD_MASK_FOR_EXCL_STATE).EQ(thread);
  }
  
  @Inline
  public static final boolean isReadExclForCurrentThread(Word metadata) {    
    return metadata.and(STATE_MASK).EQ(READ_EXCL);
  }
  
  /** Does the metadata represent the RdSh state? */
  @Inline
  public static final boolean isReadSharedPossiblyUnfenced(Word metadata) {
    boolean result = metadata.GE(MIN_READ_SHARED);
    if (VM.VerifyAssertions) {
      VM._assert(!result || !isExclState(metadata));
    }
    return result;
  }

  /** Does the metadata represent the RdSh state but needs a fence transition? */
  @Inline
  public static final boolean isReadSharedUnfenced(Word metadata) {
    if (isReadSharedPossiblyUnfenced(metadata)) {
      return RVMThread.getCurrentThread().octetReadSharedCounter.GT(metadata);
    }
    return false;
  }
  
  /** Does the metadata represent the RdSh state and doesn't need a fence transition? */
  @Inline
  public static final boolean isReadSharedAndFenced(Word metadata) {
    // the value of the metadata will be greater than the local counter
    boolean result = RVMThread.getCurrentThread().octetReadSharedCounter.LE(metadata);
    if (VM.VerifyAssertions) {
      VM._assert(!result || !isExclState(metadata));
    }
    return result;
  }
  
  @Inline
  static final Word getState(Word metadata) {
    return metadata.and(STATE_MASK);
  }

  @Inline
  public static final boolean isExclState(Word metadata) {
    if (VM.VerifyAssertions) { VM._assert(getState(metadata).NE(INTERMEDIATE)); }
    return !metadata.and(EXCL_MASK).isZero();
  }
  
  @Inline
  public static final boolean isExclStateWithoutAssert(Word metadata) {
    return !metadata.and(EXCL_MASK).isZero();
  }

  // getting threads from metadata:
  
  @Inline
  public static final RVMThread getThreadFromExclusive(Word metadata) {
    if (VM.VerifyAssertions) { VM._assert(getState(metadata).EQ(READ_EXCL) || getState(metadata).EQ(WRITE_EXCL)); }
    Word value = metadata.and(THREAD_MASK_FOR_EXCL_STATE);
    return Magic.objectAsThread(value.toAddress().toObjectReference().toObject());
  }
  
  @Inline
  static final RVMThread getThreadFromIntermediate(Word metadata) {
    if (VM.VerifyAssertions) { VM._assert(getState(metadata).EQ(INTERMEDIATE)); }
    Word value = metadata.xor(INTERMEDIATE_XOR_MASK);
    return Magic.objectAsThread(value.toAddress().toObjectReference().toObject());
  }

  /** Combined read check: checks for WrEx, RdEx, and fenced RdSh with one conditional.
      It turns out to be *slower* than checking for WrEx/RdEx and then checking for RdSh if that fails. */
  @Inline
  static final boolean isReadable(Word metadata) {
    // mask will be 0xffffffff for fenced RdSh;
    // otherwise: a small number (0x0, 0x1, or 0x2)
    Word mask = metadata.minus(RVMThread.getCurrentThread().octetReadSharedCounter).rshl(BITS_IN_WORD - READ_SHARED_HIGH_BITS).minus(Word.one());
    // make a WrEx or RdEx both look like the thread address by masking out the second-lowest bit,
    // then OR by the mask, which yields 0xffffffff for fenced RdSh, but otherwise leaves the top 30 bits unaffected
    Word maskedMetadata = metadata.and(THREAD_MASK_FOR_EXCL_STATE).or(mask);
    // also mask the thread, which yields 0xffffffff for fenced RdSh, but otherwise leaves the top 30 bits unaffected
    Word maskedThread  = ObjectReference.fromObject(RVMThread.getCurrentThread()).toAddress().toWord().or(mask);
    return maskedMetadata.EQ(maskedThread);
  }
  
  // sanity checking:
  
  @Inline
  public static final void check(Word metadata) {
    if (VM.VerifyAssertions) {
      checkNoInline(metadata);
    }
  }

  @NoInline
  private static final void checkNoInline(Word metadata) {
    Word state = getState(metadata);
    if (state.EQ(WRITE_EXCL) || state.EQ(READ_EXCL) || state.EQ(INTERMEDIATE)) {
      RVMThread otherThread;
      if (state.EQ(WRITE_EXCL) || state.EQ(READ_EXCL)) {
        otherThread = getThreadFromExclusive(metadata);
      } else {
        otherThread = getThreadFromIntermediate(metadata);
      }

      // Octet: LATER: fix?
      /*
      // is it some kind of thread?
      // is it either our current thread or a regular RVMThread or the FinalizerThread?
      RVMType otherType = ObjectModel.getObjectType(otherThread);
      myAssert(TypeReference.Thread.peekType().isAssignableFrom(otherType), metadata);
      TypeReference otherTypeRef = otherType.getTypeRef();
      if (!(otherTypeRef == TypeReference.Thread ||
            otherTypeRef == TypeReference.FinalizerThread ||
            otherTypeRef == TypeReference.CompilationThread ||
            otherTypeRef == TypeReference.ControllerThread ||
            otherThread == RVMThread.getCurrentThread())) {
        VM.sysWriteln(otherTypeRef.getName());
        VM.sysWriteln(ObjectModel.getObjectType(RVMThread.getCurrentThread()).getDescriptor());
      }
      
      myAssert(otherTypeRef == TypeReference.Thread ||
               otherTypeRef == TypeReference.FinalizerThread ||
               otherTypeRef == TypeReference.CompilationThread ||
               otherTypeRef == TypeReference.ControllerThread ||
               otherThread == RVMThread.getCurrentThread(), metadata);
      */

    } else if (state.EQ(READ_SHARED)) {
      myAssert(isReadSharedPossiblyUnfenced(metadata), metadata);
    } else {
      myAssert(false, metadata);
    }
  }
  
  private static final void myAssert(boolean b, Word metadata) {
    if (!b) {
      VM.sysWriteln("Metadata: ", metadata);
    }
    VM._assert(b);
  }
}
