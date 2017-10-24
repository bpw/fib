package org.jikesrvm.octet;

import org.jikesrvm.runtime.Magic;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.NoInline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Word;

/** Octet: implementation of pessimistic barriers, which always use a CAS or fence instead of Octet's optimistic approach */
@Uninterruptible
public final class PessimisticStateTransfers extends OctetState {

  /** Pessimistic read barrier that always uses both a CAS and fence. */
  @Inline
  public static final void readWithFence(Address addr) {
    Word oldMetadata = lockMetadata(addr);
    Word newMetadata = getNewReadState(oldMetadata);
    /* Client analysis-specific cross-thread dependence handling would go here */
    unlockAndUpdateMetadata(addr, newMetadata);
  }
  
  /** Pessimistic write barrier that always uses both a CAS and fence. */
  @Inline
  public static final void writeWithFence(Address addr) {
    Word oldMetadata = lockMetadata(addr);
    Word newMetadata = getNewWriteState(oldMetadata);
    /* Client analysis-specific cross-thread dependence handling would go here */
    unlockAndUpdateMetadata(addr, newMetadata);
  }

  /** Pessimistic read barrier that always uses a CAS but no fence. */
  @Inline
  public static final void readWithoutFence(Address addr) {
    Word oldMetadata = addr.prepareWord();
    Word newMetadata = getNewReadState(oldMetadata);
    /* Client analysis-specific cross-thread dependence handling would go here */
    if (!addr.attempt(oldMetadata, newMetadata)) {
      // Retry out-of-line
      readWithoutFenceSlowPath(addr);
    }
  }

  /** Pessimistic write barrier that always uses a CAS but no fence. */
  @Inline
  public static final void writeWithoutFence(Address addr) {
    Word oldMetadata = addr.prepareWord();
    Word newMetadata = getNewWriteState(oldMetadata);
    /* Client analysis-specific cross-thread dependence handling would go here */
    if (!addr.attempt(oldMetadata, newMetadata)) {
      // Retry out-of-line
      writeWithoutFenceSlowPath(addr);
    }
  }

  /** Pessimistic read barrier that uses no synchronization (for performance testing). */
  @Inline
  public static final void readWithoutSync(Address addr) {
    Word oldMetadata = lockMetadataWithoutSync(addr);
    Word newMetadata = getNewReadState(oldMetadata);
    /* Client analysis-specific cross-thread dependence handling would go here */
    addr.store(newMetadata);
  }

  /** Pessimistic write barrier that uses no synchronization (for performance testing). */
  @Inline
  public static final void writeWithoutSync(Address addr) {
    Word oldMetadata = lockMetadataWithoutSync(addr);
    Word newMetadata = getNewWriteState(oldMetadata);
    /* Client analysis-specific cross-thread dependence handling would go here */
    addr.store(newMetadata);
  }

  /** Compute the new state for a read (based on WrEx and RdSh states only). */
  @Inline
  static final Word getNewReadState(Word oldMetadata) {
    if (!isWriteExclForCurrentThread(oldMetadata) &&
        !isReadSharedPossiblyUnfenced(oldMetadata)) {
      return MAX_READ_SHARED; // doesn't really matter which read-shared value we use
    }
    return oldMetadata;
  }

  /** Compute the new state for a write (based on WrEx and RdSh states only). */
  @Inline
  static final Word getNewWriteState(Word oldMetadata) {
    if (!isWriteExclForCurrentThread(oldMetadata)) {
      return getExclusive(WRITE_EXCL);
    }
    return oldMetadata;
  }

  @Inline
  static final Word lockMetadata(Address addr) {
    Word oldMetadata = addr.prepareWord();
    //check(oldMetadata);
    // Use intermediate state as locked state
    if (oldMetadata.EQ(INTERMEDIATE) ||
        !addr.attempt(oldMetadata, INTERMEDIATE)) { 
      return lockMetadataSlowPath(addr);
    }
    return oldMetadata;
  }

  /** If first attempt to lock fails, we call this out-of-line method
      to do the looping behavior. */
  @NoInline
  static final Word lockMetadataSlowPath(Address addr) {
    Word oldMetadata;
    do {
      Magic.pause(); // If we are here, a CAS just failed 
      oldMetadata = addr.prepareWord();
      check(oldMetadata);
    } while (oldMetadata.EQ(INTERMEDIATE) ||
             !addr.attempt(oldMetadata, INTERMEDIATE));
    return oldMetadata;
  }

  /** Lock the metadata incorrectly -- without using synchronization (for performance testing). */
  @Inline
  static final Word lockMetadataWithoutSync(Address addr) {
    Word oldMetadata = addr.loadWord();
    //check(oldMetadata);
    // Use intermediate state as locked state
    if (oldMetadata.EQ(INTERMEDIATE)) {
      return lockMetadataWithoutSyncSlowPath(addr);
    }
    addr.store(INTERMEDIATE);
    return oldMetadata;
  }

  /** If first attempt to (incorrectly) lock fails, we call this out-of-line method to do the looping behavior. */
  @NoInline
  static final Word lockMetadataWithoutSyncSlowPath(Address addr) {
    Word oldMetadata;
    do {
      oldMetadata = addr.loadWord();
      check(oldMetadata);
    } while (oldMetadata.EQ(INTERMEDIATE));
    addr.store(INTERMEDIATE);
    return oldMetadata;
  }

  /** Unlock and update the metadata, including an mfence. */
  @Inline
  static final void unlockAndUpdateMetadata(Address addr, Word newMetadata) {
    Magic.fence();
    //if (VM.VerifyAssertions) { VM._assert(addr.loadWord().EQ(INTERMEDIATE)); }
    addr.store(newMetadata);
  }

  @NoInline
  static final void readWithoutFenceSlowPath(Address addr) {
    Word oldMetadata, newMetadata;
    do {
      Magic.pause(); // If we are here, a CAS just failed
      oldMetadata = addr.prepareWord();
      newMetadata = getNewReadState(oldMetadata);
      /* Client analysis-specific cross-thread dependence handling would go here */
    } while (!addr.attempt(oldMetadata, newMetadata));
  }

  @NoInline
  static final void writeWithoutFenceSlowPath(Address addr) {
    Word oldMetadata, newMetadata;
    do {
      Magic.pause(); // If we are here, a CAS just failed
      oldMetadata = addr.prepareWord();
      newMetadata = getNewWriteState(oldMetadata);
      /* Client analysis-specific cross-thread dependence handling would go here */
    } while (!addr.attempt(oldMetadata, newMetadata));
  }
}
