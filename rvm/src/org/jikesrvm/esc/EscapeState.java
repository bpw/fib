package org.jikesrvm.esc;

import org.jikesrvm.Constants;
import org.jikesrvm.VM;
import org.jikesrvm.classloader.Context;
import org.jikesrvm.objectmodel.JavaHeader;
import org.jikesrvm.objectmodel.MiscHeader;
import org.jikesrvm.objectmodel.ObjectModel;
import org.jikesrvm.octet.Site;
import org.jikesrvm.runtime.Magic;
import org.jikesrvm.scheduler.RVMThread;
import org.vmmagic.pragma.Entrypoint;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.NoInline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.pragma.UninterruptibleNoWarn;
import org.vmmagic.unboxed.*;

@Uninterruptible
public class EscapeState implements Constants {

  // We use the name "state" to refer to using 1 bit for the state, and the name "metadata" to refer to using 1 word.
  // Escape: By default GC uses the lowest byte, we use the highest bit (the 8th lowest bit) of the available byte for escape state.
  // The value 0 means Not_Escaped, 1 means Escaped. We rely on the fact that this bit is initialized to 0 by default.
  
  // Man: Later: Why does this "stateBitOffset" have a value of zero when the VM starts? (Try printing its value in the barrier)
  // This causes hard-to-debug errors when I tried to mark thread objects escaped in constructor of RVMThread.
  // protected static final Offset stateBitOffset = JavaHeader.ESCAPE_BIT_OFFSET;

  public static final Word ESCAPE_BIT_MASK = Word.fromIntZeroExtend(0x40); // ... 0100 0000 -- changed from 0x80 trying FIB lock delegation --bpw

  private static final int STATE_BITS = 3;
  private static final Word State_Mask = Word.one().lsh(STATE_BITS).minus(Word.one()); // 111
  private static final Word Escape_Metadata = Word.fromIntZeroExtend(0x3); // 011
  // private static final Word NoEscape_State = Word.fromIntZeroExtend(0x3); // 100
  // Temporarily make it conflict with Octet's states, so we can check if it correctly co-exists with Octet.
  private static final Word NoEscape_State = Word.fromIntZeroExtend(0x5); // 101
  // private static final Word UNINITIALIZED = Word.zero();
  static final Offset metadataOffset = MiscHeader.ESCAPE_OFFSET;

  @Inline
  protected static final boolean isEscapedState(Word state) {
    return !state.and(ESCAPE_BIT_MASK).isZero();
  }
  
  @Inline
  public static final Word getEscapedState(Word state) {
    return state.or(ESCAPE_BIT_MASK);
  }
  
  @Inline
  public static final boolean isEscapedObject(Object o) {
    Word state = Magic.getWordAtOffset(o, JavaHeader.ESCAPE_BIT_OFFSET);
    return isEscapedState(state);
  }
  
  /* not used
  @Inline
  public static final boolean isEscapedObject(Address a) {
    Word state = a.loadWord(JavaHeader.ESCAPE_BIT_OFFSET);
    return isEscapedState(state);
  }
  */
  
  @Inline
  public static final boolean isEscapedObjectMD(Object o) {
    Word metadata = Magic.getWordAtOffset(o, metadataOffset);
    return isEscapeMetadata(metadata);
  }
  
  @Inline
  public static final boolean isEscaped(Object o) {
    if (Esc.getConfig().escapeUseOneBitInGCHeader()) {
      return isEscapedObject(o);
    } else {
      return isEscapedObjectMD(o);
    }
  }
  
  @Inline
  private static final void setEscaped(Object o, Word oldState) {
    Magic.setWordAtOffset(o, JavaHeader.ESCAPE_BIT_OFFSET, getEscapedState(oldState));    
    Esc.getEscapeClient().escape(o);
  }
  @Inline
  public static final void setEscaped(Object o) {
    // Man: It uses one load and one store, ideally it should be just one store. But one store could possibly destroy the bits used by GC.
    Word state = Magic.getWordAtOffset(o, JavaHeader.ESCAPE_BIT_OFFSET);
    setEscaped(o, state);
  }
  @Inline
  public static final void setEscapedNoHook(Object o) {
    Word oldState = Magic.getWordAtOffset(o, JavaHeader.ESCAPE_BIT_OFFSET);
    Magic.setWordAtOffset(o, JavaHeader.ESCAPE_BIT_OFFSET, getEscapedState(oldState));    
  }
  
  /* Not used
  @Inline
  public static final void setEscaped(Address a) {
    Word state = a.loadWord(JavaHeader.ESCAPE_BIT_OFFSET);
    setEscaped(a.toObjectReference().toObject(), state);
  }
  */
  
  @Inline
  public static final Word getInitialMetadata() {
  	// If getInitial() is called when creating an object, it should set
  	// the state to NoEscape
    if (VM.VerifyAssertions) VM._assert(VM.runningVM);
    return getNoEscapeMetadata();
  }

  @Inline
  public static final Word getInitialMetadataInBootImageBuild() {
    if (VM.VerifyAssertions) VM._assert(!VM.runningVM);
    return getEscapeMetadata();
  }

  @Inline
  public static final Word getEscapeMetadata() {
    return Escape_Metadata;
  }

  @Inline
  private static final Word getNoEscapeMetadata() {
    return ObjectReference.fromObject(RVMThread.getCurrentThread()).toAddress().toWord().or(NoEscape_State);
  }

  @Inline
  protected static final boolean isNoEscapeMetadata(Word metadata) {
    return metadata.and(State_Mask).EQ(NoEscape_State);
  }

  @Inline
  protected static final boolean isNoEscapeForCurrentThread(Word metadata) {
    return metadata.EQ(getNoEscapeMetadata());
  }

  @Inline
  protected static final boolean isEscapeMetadata(Word metadata) {
    return metadata.EQ(getEscapeMetadata());
  }

  // This method is called from library methods Class.newInstance(), Constructor.newInstance() and Object.clone().
  // TODO: If VM creates and array of library objects to be used by application, through reflection (Array.newInstance()), then the array should be marked as escaped.
  @Inline
  @Entrypoint
  public static final void markObjectEscaped(Object obj) {
    if (Esc.getConfig().escapeUseOneBitInGCHeader()) {
      setEscaped(obj);
    } else {
      if (VM.VerifyAssertions) VM._assert(Esc.getConfig().escapeAddHeaderWord());
      ObjectReference.fromObject(obj).toAddress().store(EscapeState.getEscapeMetadata(), metadataOffset);
      Esc.getEscapeClient().escape(obj);
    }
    EscapeStats.Alloc_Runtime_Reflect.inc();
    EscapeState.incrementEscapedStats(ObjectReference.fromObject(obj).toAddress());
  }
  
  @Inline
  public static final boolean writeObject(Word rhsValue, Address lhsAddr, Offset fieldOffset, int fieldOrIndexInfo, int siteID) {
    if (!Esc.getConfig().escapeUseOneBitInGCHeader()) {
      return writeObjectWordMetadata(rhsValue, lhsAddr, fieldOffset, fieldOrIndexInfo, siteID);
    }
    if (!rhsValue.isZero()) {
      Word lhsState = lhsAddr.loadWord(JavaHeader.ESCAPE_BIT_OFFSET);
      checkState(lhsState, lhsAddr.toObjectReference().toObject(), siteID);
      if (isEscapedState(lhsState)) {
        Address rhsAddr = rhsValue.toAddress();
        Word rhsState = rhsAddr.loadWord(JavaHeader.ESCAPE_BIT_OFFSET);
        if (Esc.getConfig().escapeCheckRhsMetadata()) checkState(rhsState, rhsAddr.toObjectReference().toObject(), siteID);
        if (!isEscapedState(rhsState)) {
          Word newState = getEscapedState(rhsState);
          rhsAddr.store(newState, JavaHeader.ESCAPE_BIT_OFFSET);
          Esc.getEscapeClient().escape(rhsAddr.toObjectReference().toObject());
          incrementEscapedStats(rhsAddr);
          
          EscapeTransitiveClosure.computeTransitiveClosure(rhsAddr, lhsAddr, fieldOffset, fieldOrIndexInfo, siteID);
          return true;
        }
      }
    }
    return false;
  }
  
  @Inline
  public static final boolean writeStatic(Word rhsValue, Offset fieldOffset, int fieldOrIndexInfo, int siteID) {
    if (!Esc.getConfig().escapeUseOneBitInGCHeader()) {
      return writeStaticWordMetadata(rhsValue, fieldOffset, fieldOrIndexInfo, siteID);
    }
    if (!rhsValue.isZero()) {
      Address rhsAddr = rhsValue.toAddress();
      Word rhsState = rhsAddr.loadWord(JavaHeader.ESCAPE_BIT_OFFSET);
      if (Esc.getConfig().escapeCheckRhsMetadata()) checkState(rhsState, rhsAddr.toObjectReference().toObject(), siteID);
      if (!isEscapedState(rhsState)) {
        Word newState = getEscapedState(rhsState);
        rhsAddr.store(newState, JavaHeader.ESCAPE_BIT_OFFSET);
        Esc.getEscapeClient().escape(rhsAddr.toObjectReference().toObject());
        incrementEscapedStats(rhsAddr);
        
        EscapeTransitiveClosure.computeTransitiveClosure(rhsAddr, null, fieldOffset, fieldOrIndexInfo, siteID);
        return true;
      }
    }
    return false;
  }
  
  @NoInline
  public static final boolean writeObjectWordMetadata(Word rhsValue, Address lhsAddr, Offset fieldOffset, int fieldOrIndexInfo, int siteID) {
    if (!rhsValue.isZero()) {
      Word lhsMetadata = lhsAddr.loadWord(metadataOffset);
      checkMetadata(lhsMetadata, lhsAddr.toObjectReference().toObject(), siteID, false);
      if (isEscapeMetadata(lhsMetadata)) {
        Address rhsAddr = rhsValue.toAddress();
        Word rhsMetadata = rhsAddr.loadWord(metadataOffset);
        if (Esc.getConfig().escapeCheckRhsMetadata()) checkMetadata(rhsMetadata, rhsAddr.toObjectReference().toObject(), siteID, false);
        // q.f = p (inherited)
        // if 'q' is 'Escaped', 'p' becomes 'Escaped'
        if (isNoEscapeMetadata(rhsMetadata)) {
          Word newMetadata = getEscapeMetadata();
          rhsAddr.store(newMetadata, metadataOffset);
          Esc.getEscapeClient().escape(rhsAddr.toObjectReference().toObject());
          incrementEscapedStats(rhsAddr);

          EscapeTransitiveClosure.computeTransitiveClosure(rhsAddr, lhsAddr, fieldOffset, fieldOrIndexInfo, siteID);
          return true;
        }
      }
    }
    return false;
  }

  @NoInline
  public static final boolean writeStaticWordMetadata(Word rhsValue, Offset fieldOffset, int fieldOrIndexInfo, int siteID) {
    // T.sf = p (escaped)
    // 'p' ie, RHS should become 'Escaped'
    if (!rhsValue.isZero()) {
      Address rhsAddr = rhsValue.toAddress();
      Word rhsMetadata = rhsAddr.loadWord(metadataOffset);
      if (Esc.getConfig().escapeCheckRhsMetadata()) checkMetadata(rhsMetadata, rhsAddr.toObjectReference().toObject(), siteID, false);
      if (isNoEscapeMetadata(rhsMetadata)) {
        Word newMetadata = getEscapeMetadata();
        rhsAddr.store(newMetadata, metadataOffset);
        Esc.getEscapeClient().escape(rhsAddr.toObjectReference().toObject());
        incrementEscapedStats(rhsAddr);

        EscapeTransitiveClosure.computeTransitiveClosure(rhsAddr, null, fieldOffset, fieldOrIndexInfo, siteID);
        return true;
      }
    }
    return false;
  }

  @Inline
  public static final void incrementEscapedStats(Address addr) {
    if (Esc.getConfig().escapeStats()) {
      if (VM.VerifyAssertions) VM._assert(VM.runningVM);
      EscapeStats.Escaped_Runtime.inc();
      EscapeStats.Escaped_Runtime_inHarness.inc();
      Object obj = addr.toObjectReference().toObject();
      if (Context.isVMPrefix(ObjectModel.getObjectType(obj).getTypeRef())) {
        EscapeStats.Escaped_Runtime_VM.inc();
      } else {
        EscapeStats.Escaped_Runtime_NonVM.inc();
      }
    }
  }
  
  @NoInline
  public static final void printHeaderWord(Object obj) {
    Word state = Magic.getWordAtOffset(obj, JavaHeader.ESCAPE_BIT_OFFSET);
    if (!state.isZero()) {
      VM.sysWriteln("Object has header value of: ", state);
    }
  }

  @NoInline
  public static final void checkState(Word state, Object obj, int siteID) {
    if (VM.VerifyAssertions && Esc.getConfig().escapeInstrumentAllocation()) {
      if (!isEscapedState(state)) {
        Word metadata = Magic.getWordAtOffset(obj, metadataOffset);
        checkMetadata(metadata, obj, siteID, true);
      }
    }
  }
  
  @UninterruptibleNoWarn
  @NoInline
  public static final void checkMetadata(Word metadata, Object obj, int siteID, boolean mustBeNotEscaped) {
    if (VM.VerifyAssertions) {
      if (mustBeNotEscaped || isNoEscapeMetadata(metadata)) {
        if (!isNoEscapeForCurrentThread(metadata)) {
          if (RVMThread.getCurrentThread().isSystemThread()) {
            // mainly to avoid checking metadata in FinalizerThread
            return;
          }
          // ignore the failure if not marking object allocated in VM context escaped
          if (Esc.getConfig().escapeVMContextEscape() || Esc.getConfig().escapeInstrumentVM()) {
            if (Esc.getConfig().escapePassSite() && !Esc.getConfig().escapeCollectAssertFailure()) {
//              TypeReference tRef = ObjectModel.getObjectType(obj).getTypeRef();
//              if (Context.isApplicationPrefix(tRef) && !tRef.isArrayType()) {
                Site.lookupSite(siteID).sysWriteln();
                myAssert(false, metadata);
//              }
            }
            myAssert(false, metadata);
          }
          if (Esc.getConfig().escapeCollectAssertFailure()) {
            EscapeStats.failedObjects.put(obj, null);
          }
        }
      } else if (isEscapeMetadata(metadata)) {

      } else {
        myAssert(false, metadata);
      }
    }
  }

  @Inline
  private static final void myAssert(boolean b, Word metadata) {
    if (!b) {
      VM.sysWriteln("Current thread address: ", ObjectReference.fromObject(RVMThread.getCurrentThread()).toAddress());
      VM.sysWriteln("metadata value: ", metadata);
    }
    VM._assert(b);
  }
}
