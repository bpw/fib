package org.jikesrvm.dr.metadata;

import org.jikesrvm.SizeConstants;
import org.jikesrvm.VM;
import org.jikesrvm.classloader.RVMType;
import org.jikesrvm.dr.Dr;
import org.jikesrvm.dr.DrDebug;
import org.jikesrvm.dr.DrRuntime;
import org.jikesrvm.dr.DrStats;
import org.jikesrvm.mm.mminterface.Barriers;
import org.jikesrvm.mm.mminterface.MemoryManager;
import org.jikesrvm.objectmodel.BootImageInterface;
import org.jikesrvm.objectmodel.JavaHeaderConstants;
import org.jikesrvm.objectmodel.MiscHeader;
import org.jikesrvm.objectmodel.ObjectModel;
import org.jikesrvm.objectmodel.TIB;
import org.jikesrvm.scheduler.RVMThread;
import org.mmtk.plan.TransitiveClosure;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.pragma.NoInline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.pragma.UninterruptibleNoWarn;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.ObjectReference;
import org.vmmagic.unboxed.Word;
import org.vmmagic.unboxed.WordArray;

/**
 * Management for additional metadata added to objects.
 * 
 * Metadata in data objects:
 *   - extra header word: reference to lock VC
 *   - inline access histories (for data object fields)
 * Metadata in data arrays:
 *   - extra header word: reference to dedicated metadata array
 *     (contains access histories for data array elements)
 * Metadata in metadata arrays:
 *   - extra header word: reference to lock VC for corresponding data array
 *   
 * NEW POLICIES:
 *   - array shadows are distinguished by a stolen header bit
 *   - until a 2nd thread acquires a lock, only an epoch is stored, not a VC.
 */
@Uninterruptible
public class ObjectShadow implements SizeConstants {
  
  static {
    if (VM.VerifyAssertions) {
      VM._assert(!Barriers.NEEDS_OBJECT_GETFIELD_BARRIER,
          "FIB does not currently support GCs that require read barriers.");
    }
  }

  /**
   * Initialize object's metadata header word at runtime:
   * Do nothing -- leave blank initially.
   * 
   * @param object
   * @param tib
   * @param size
   * @param isScalar
   */
  @Inline
  public static void initializeHeader(Object object, TIB tib, int size, boolean isScalar) { }
  
  /**
   * Initialize object's metadata header word at compile time:
   * Make sure it is null.
   * 
   * @param bootImage
   * @param ref
   * @param tib
   * @param size
   * @param isScalar
   */
  @Interruptible
  public static void initializeHeader(BootImageInterface bootImage, Address ref, TIB tib, int size, boolean isScalar) {
    RVMType type = tib.getType();
    if (type != RVMType.CodeArrayType
        && type != RVMType.CodeType
        && (!type.isArrayType()
            || !type.asArray().getInnermostElementType().isUnboxedType())) {
      // Make sure headers in the boot image will get traced.
      bootImage.setNullAddressWord(ref.plus(MiscHeader.FIB_OFFSET), true, true);
    }
  }
  
  /**
   * Index of header "available bit" used to tag WordArrays that serve as array shadows.
   */
  private static final int ARRAY_SHADOW_BIT_INDEX = 7;

  static {
    if (VM.VerifyAssertions) {
      // The array shadow tagging works under this scheme only.
      VM._assert(JavaHeaderConstants.ADDRESS_BASED_HASHING);
    }
  }
  
  /**
   * An object is an array shadow if it is tagged
   * // OLD a WordArray whose FIB header is non-null.
   * @param objectRef
   * @return
   */
  @Inline
  private static boolean isArrayShadow(ObjectReference objectRef) {
    return ObjectModel.testAvailableBit(objectRef.toObject(), ARRAY_SHADOW_BIT_INDEX);
  }
  
  /**
   * Get the shadow array holding access histories for this array.
   * Call on arrays only.
   * @param array
   * @return
   */
  @Inline
  public static Object getArrayShadow(final Object array) {
    if (VM.VerifyAssertions) {
      VM._assert(Dr.ARRAY_SHADOWS);
      VM._assert(VM.runningVM);
      // Only allowed on arrays.
      VM._assert(ObjectModel.getObjectType(array).isArrayType());
      VM._assert(!ObjectModel.getObjectType(array).getTypeRef().isMagicType());
    }
    // Get the header word.
    final Word s = loadHeader(array);
        
    if (Epoch.isMapRef(s)) {
      return s.toAddress().toObjectReference().toObject();
    } else {
      // SLOW PATH: lazy initialization.
      final Object as = initArrayShadow(array, s);
      return as;
    }
  }

//  private static final WordArray ARRAY_MARKER_NON_VC = WordArray.create(0);

  /**
   * Initialize the array shadow of a data array:
   * Allocate a metadata WordArray and a lock VC.
   * 
   * @param array
   * @param headerWord
   * @return
   */
  @NoInline
  @UninterruptibleNoWarn
  private static Object initArrayShadow(final Object array, final Word headerWord) {
    if (VM.VerifyAssertions) VM._assert(!Epoch.isMapRef(headerWord));
    // If it's null, create a shadow and CAS a pointer to it in the header word.
    MemoryManager.startAllocatingInUninterruptibleCode();
    final Object s = WordArray.create(ObjectModel.getArrayLength(array) * AccessHistory.WORDS_IN_HISTORY);
    MemoryManager.stopAllocatingInUninterruptibleCode();
    
    // Mark as array shadow.
    if (VM.VerifyAssertions) {
      VM._assert(!ObjectModel.testAvailableBit(s, ARRAY_SHADOW_BIT_INDEX));
    }
    ObjectModel.setAvailableBit(s, ARRAY_SHADOW_BIT_INDEX, true);
    
    // CAS it into place.
    if (attemptHeader(array, headerWord, ObjectReference.fromObject(s).toAddress().toWord())) {
      Barriers.objectFieldWritePreBarrier(array, s, MiscHeader.FIB_OFFSET, -1);
      if (Dr.STATS) {
        DrStats.arrayShadows.inc();
      }
      return s;
    } else {
      final Word w = loadHeader(array);
      if (VM.VerifyAssertions) {
        VM._assert(Epoch.isMapRef(w));
      }
      return w.toAddress().toObjectReference().toObject();
    }
  }
  
  
  /**
   * Get the EpochMap/vector clock for this object's lock.
   * Only call this when the object is currently locked by the current thread.
   * @param object
   * @return
   */
  public static WordArray getVC(Object object) {
    if (VM.VerifyAssertions) {
      VM._assert(Dr.LOCKS);
      VM._assert(VM.runningVM);
      VM._assert(!ObjectModel.getObjectType(object).getTypeRef().isMagicType());
    }

    // Extra indirection for arrays used as locks.
    if (Dr.ARRAY_SHADOWS && ObjectModel.getObjectType(object).isArrayType()) {
      object = getArrayShadow(object);
    }
    final Object headerRef = loadHeader(object).toAddress().toObjectReference().toObject();
    if (headerRef == null) {
      final WordArray vc = VC.create();
      setHeaderRef(object, vc);
      if (Dr.STATS) DrStats.lockVCs.inc();
      return vc;
    } else {
      return (WordArray)headerRef;
    }
    // FIXME: ignores ESCAPE
  }
  
//  private static final Word HOLDER_LOCKED = Epoch.TAG;
//  private static final Word HOLDER_NONE = Word.zero();
  
  private static void checkTid(Word e) {
    int max = DrRuntime.maxLiveDrThreads();
    int tid = Epoch.tid(e);
    if (max <= tid) {
      DrDebug.lock();
      DrDebug.twrite(); VM.sysWriteln("DR thread holder ", tid, " of ", max, " max live DR threads.");
      RVMThread.dumpStack();
      DrDebug.unlock();
    }
  }
  
  public static WordArray setMostRecentHolderAndGetVC(Object monitor) {
    checkTid(RVMThread.getCurrentThread().getDrEpoch());
    WordArray vc = getVC(monitor);
    storeHeader(ObjectReference.fromObject(vc).toObject(), RVMThread.getCurrentThread().getDrEpoch());
    return vc;
  }
  
  /**
   * Set the metadata header word of object to point to VC or metadata array s.
   * @param object
   * @param s
   */
  @Inline
  private static void setHeaderRef(final Object object, final WordArray s) {
    if (Barriers.NEEDS_OBJECT_PUTFIELD_BARRIER) {
      Barriers.objectFieldWrite(object, ObjectReference.fromObject(s).toObject(), MiscHeader.FIB_OFFSET, -1);
    } else {
      ObjectReference.fromObject(object).toAddress().store(ObjectReference.fromObject(s), MiscHeader.FIB_OFFSET);
    }
  }

  /**
   * Set the metadata header word of object to contain epoch.  (Used for escape.)
   * To be called on application objects.
   * @param object
   * @param epoch
   */
  @Inline
  public static void setHeaderEpoch(final Object object, final Word epoch) {
    if (VM.VerifyAssertions) {
      VM._assert(!Epoch.isMapRef(epoch));
      if (!loadHeader(object).isZero()) {
        DrDebug.twrite(); VM.sysWriteln("setHeaderEpoch ", ObjectReference.fromObject(object).toAddress().toWord(), " would overwrite ", loadHeader(object));
        VM.sysWriteln("a reference to an object of type ", ObjectModel.getObjectType(loadHeader(object).toAddress().toObjectReference().toObject()).getDescriptor());
        VM.sysWriteln("in header of object of type ", ObjectModel.getObjectType(object).getDescriptor());
      }
      VM._assert(loadHeader(object).isZero());
      // Debug.twrite();  VM.sysWriteln("setHeaderEpoch ", ObjectReference.fromObject(object).toAddress().toWord(), " => ", epoch);
    }
    storeHeader(object, epoch.or(Epoch.TAG));
  }
  
  @Inline
  public static Address headerAddress(ObjectReference objectRef) {
    return objectRef.toAddress().plus(MiscHeader.FIB_OFFSET);
  }
  @Inline
  public static Address headerAddress(Object object) {
    return headerAddress(ObjectReference.fromObject(object));
  }



  /**
   * Get the metadata header word of object.
   * @param object
   * @return
   */
  @Inline
  public static Word loadHeader(final Object object) {
    return ObjectReference.fromObject(object).toAddress().loadWord(MiscHeader.FIB_OFFSET);
  }
  @Inline
  public static void storeHeader(final Object object, Word w) {
    ObjectReference.fromObject(object).toAddress().store(w, MiscHeader.FIB_OFFSET);
  }
  @Inline
  public static Word prepareHeader(final Object object) {
    return ObjectReference.fromObject(object).toAddress().prepareWord(MiscHeader.FIB_OFFSET);
  }
  @Inline
  public static boolean attemptHeader(final Object object, Word old, Word w) {
    return ObjectReference.fromObject(object).toAddress().attempt(old, w, MiscHeader.FIB_OFFSET);
  }
    
  
  /**
   * Scan all FIB references stored in objectRef, submitting them to trace.
   * @param trace - the transitive closure underway
   * @param objectRef - object to scan
   * @param type - type of objectRef
   */
  @Inline
  public static void scan(final TransitiveClosure trace, final ObjectReference objectRef) {
    if (VM.VerifyAssertions) {
      VM._assert(Dr.SCAN);
      VM._assert(MemoryManager.validRef(objectRef));
    }
    
    scanHeader(trace, objectRef);
    
    if (!Dr.FULL_SCAN) return;

    if (isArrayShadow(objectRef)) {
      // If this is a WordArray, it might be a FIB array shadow.
      // If it is, it must be scanned further.
      // Scan the individual last reader words.
      final WordArray w = (WordArray)objectRef.toObject();
      final Address end = objectRef.toAddress().plus(w.length() << LOG_BYTES_IN_WORD);
      for (Address historyAddress = objectRef.toAddress();
          historyAddress.LT(end);
          historyAddress = historyAddress.plus(AccessHistory.BYTES_IN_HISTORY)) {
        AccessHistory.scan(trace, objectRef, historyAddress);
      }
    } else {
      // Scan any inlined access history ref offsets...
      final int[] historyOffsets = ObjectModel.getObjectType(objectRef.toObject()).getDrHistoryOffsets();
      if (historyOffsets != null) {
        for (int offset : historyOffsets) {
          final Address historyAddress = objectRef.toAddress().plus(offset);
          AccessHistory.scan(trace, objectRef, historyAddress);
        }
      }
    }
  }

  /**
   * Scan only the header word to see if it refers to an array shadow or EpochMap.
   * @param trace
   * @param objectRef
   */
  @Inline
  private static void scanHeader(final TransitiveClosure trace, final ObjectReference objectRef) {
    final Address a = headerAddress(objectRef);
    if (Epoch.isMapRef(a.loadWord())) { // so we can tag in our metadata objects...
      trace.processEdge(objectRef, a);
    }
  }
  
}
