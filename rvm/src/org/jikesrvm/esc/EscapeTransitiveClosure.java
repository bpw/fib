package org.jikesrvm.esc;

import org.jikesrvm.VM;
import org.jikesrvm.classloader.Context;
import org.jikesrvm.classloader.RVMArray;
import org.jikesrvm.classloader.RVMField;
import org.jikesrvm.classloader.RVMClass;
import org.jikesrvm.classloader.RVMType;
import org.jikesrvm.mm.mminterface.MemoryManager;
import org.jikesrvm.objectmodel.JavaHeader;
import org.jikesrvm.objectmodel.ObjectModel;
import org.jikesrvm.octet.Site;
import org.jikesrvm.runtime.Magic;
import org.jikesrvm.scheduler.RVMThread;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.NoInline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.pragma.UninterruptibleNoWarn;
import org.vmmagic.unboxed.*;

@Uninterruptible
public class EscapeTransitiveClosure extends EscapeState {

  private static final int Verbosity = 0;
  private static final boolean printTracing = (Verbosity > 3) && Esc.getConfig().escapePassSite();

  @NoInline
  static final void computeTransitiveClosure(Address rootAddr, Address lhsAddr, Offset fieldOffset, int fieldOrIndexInfo, int siteID) {
    if (printTracing) {
      VM.sysWrite("Start computing transitive closure from site: ");
      Site.lookupSite(siteID).sysWriteln();
    }
    transitiveClosureNaive(rootAddr, siteID);
  }

  @Inline
  private static final void transitiveClosureNaive(Address rootAddr, int siteID) {
    LinkedListAddress workList = allocateLinkedList();

    // Object rootObj = rootAddr.toObjectReference().toObject();
    traceOutgoingReferences(rootAddr, workList, siteID);

    int iter = 0;
    while(!workList.isEmpty()) {
      iter++;
      Address objAddr = workList.popHead();
      if (Esc.getConfig().escapeUseOneBitInGCHeader()) {
        Word state = objAddr.loadWord(JavaHeader.ESCAPE_BIT_OFFSET);
        if (Esc.getConfig().escapeCheckRhsMetadata()) checkState(state, objAddr.toObjectReference().toObject(), siteID);
        if (!isEscapedState(state)) {
          Word newState = getEscapedState(state);
          objAddr.store(newState, JavaHeader.ESCAPE_BIT_OFFSET);
          Esc.getEscapeClient().escape(objAddr.toObjectReference().toObject());
          incrementEscapedStats(objAddr);
          traceOutgoingReferences(objAddr, workList, siteID);
        }
      } else {
        Word metadata = objAddr.loadWord(metadataOffset);
        if (Esc.getConfig().escapeCheckRhsMetadata()) checkMetadata(metadata, objAddr.toObjectReference().toObject(), siteID, false);
        if (isNoEscapeMetadata(metadata)) {
          Word newMetadata = getEscapeMetadata();
          objAddr.store(newMetadata, metadataOffset);
          Esc.getEscapeClient().escape(objAddr.toObjectReference().toObject());
          incrementEscapedStats(objAddr);
          traceOutgoingReferences(objAddr, workList, siteID);
        }
      }
    }
    EscapeStats.logTracingIter.incBin(iter);
  }

  @UninterruptibleNoWarn
  protected static LinkedListAddress allocateLinkedList() {
    MemoryManager.startAllocatingInUninterruptibleCode();
    LinkedListAddress workList = new LinkedListAddress();
    MemoryManager.stopAllocatingInUninterruptibleCode();
    return workList;
  }

  @Inline
  private static void traceOutgoingReferences(Address objAddr, LinkedListAddress workList, int siteID) {
    if (Esc.getConfig().escapeTraceOutEdgesGCStyle()) {
      traceOutgoingReferencesGCStyle(objAddr, workList, siteID);
    } else {
      traceOutgoingReferencesNaive(objAddr, workList, siteID);
    }
  }

  @Inline
  private static void traceOutgoingReferencesGCStyle(Address objAddr, LinkedListAddress workList, int siteID) {
    Object obj = objAddr.toObjectReference().toObject();
    if (printTracing) {
      VM.sysWrite("Tracing from object: ", objAddr);
//      Address tibAddr = objAddr.loadAddress(JavaHeader.TIB_OFFSET);
//      VM.sysWrite("(Object's TIB pointer: ", tibAddr, ")");
      Word jHeader = objAddr.loadWord(JavaHeader.ESCAPE_BIT_OFFSET);
      VM.sysWrite("(Object's Java header: ", jHeader, ")");
    }
    RVMType type = ObjectModel.getObjectType(obj);
    if (printTracing) {
      VM.sysWriteln(", Type: ", type.getDescriptor());
    }

    int[] offsets = type.getReferenceOffsets();
    if (offsets != RVMType.REFARRAY_OFFSET_ARRAY) {
      if (VM.VerifyAssertions) VM._assert(type.isClassType() || (type.isArrayType() && !type.asArray().getElementType().isReferenceType()));
      for(int i=0; i < offsets.length; i++) {
        Address refValue = objAddr.plus(offsets[i]).loadAddress();
        if (!refValue.isZero()) {
          if (printTracing) {
            VM.sysWrite("Adding field ref to worklist at: ");
            Site.lookupSite(siteID).sysWriteln();
            VM.sysWriteln("Ref value: ", refValue);
          }
          if (VM.VerifyAssertions && !isValidAddressRange(refValue)) {
            VM.sysWriteln("Unexpected refValue: ", refValue);
            Site.lookupSite(siteID).sysWriteln();
            VM.sysFail("Wrong refValue");
          }
          workList.add(refValue);
        }
      }
    } else {
      if (VM.VerifyAssertions) VM._assert(type.isArrayType() && type.asArray().getElementType().isReferenceType());
      for(int i=0; i < ObjectModel.getArrayLength(obj); i++) {
        Address refValue = objAddr.plus(i << LOG_BYTES_IN_ADDRESS).loadAddress();
        if (!refValue.isZero()) {
          if (printTracing) {
            VM.sysWrite("Adding array element to worklist at: ");
            Site.lookupSite(siteID).sysWriteln();
            VM.sysWriteln("element value: ", refValue);
          }
          if (VM.VerifyAssertions && !isValidAddressRange(refValue)) {
            VM.sysWriteln("Unexpected element address: ", refValue);
            Site.lookupSite(siteID).sysWriteln();
            VM.sysFail("Wrong element address");
          }
          workList.add(refValue);
        }
      }
    }
  }
  
  @Inline
  private static void traceOutgoingReferencesNaive(Address objAddr, LinkedListAddress workList, int siteID) {
    Object obj = objAddr.toObjectReference().toObject();
    RVMType type = ObjectModel.getObjectType(obj);

    if (type.isArrayType()) {
      RVMArray arrayType = type.asArray();
      RVMType eleType = arrayType.getElementType();
      if (eleType.isReferenceType() && Esc.shouldTraceObject(eleType.getTypeRef())) {
        for(int i=0; i < ObjectModel.getArrayLength(obj); i++) {
          Address refValue = objAddr.plus(i << LOG_BYTES_IN_ADDRESS).loadAddress();
          if (!refValue.isZero()) {
            if (printTracing) {
              VM.sysWriteln("Adding array element to worklist, element type: ", eleType.getDescriptor());
            }
            if (VM.VerifyAssertions && !isValidAddressRange(refValue)) {
              VM.sysWriteln("Unexpected element address: ", refValue);
              VM.sysWriteln("Element type: ", eleType.getDescriptor());
              VM.sysFail("Wrong element address");
            }
            workList.add(refValue);
          }
        }
      }
    } else {
      if (VM.VerifyAssertions) VM._assert(type.isClassType());
      RVMClass classType = type.asClass();
      RVMField [] fields = classType.getInstanceFields();
      for (RVMField f : fields) {
        if (f.isReferenceType() && Esc.shouldTraceObject(f.getType())) {
          Address refValue = objAddr.loadAddress(f.getOffset());
          if (!refValue.isZero()) {
            if (printTracing) {
              VM.sysWriteln("Adding field ref to worklist, declaring class: ", classType.getDescriptor());
              VM.sysWriteln("field type: ", f.getDescriptor());
              VM.sysWriteln("field name: ", f.getName());
            }
            if (VM.VerifyAssertions && !isValidAddressRange(refValue)) {
              VM.sysWriteln("Unexpected refValue: ", refValue);
              VM.sysWriteln("Declaring class: ", classType.getDescriptor());
              VM.sysWriteln("Reading field: ", f.getName());
              VM.sysFail("Wrong refValue");
            }
            workList.add(refValue);
          }
        }
      }
    }
  }
  
  @Inline
  public static final boolean isValidAddressRange(Address addr) {
    return addr.GE(Address.fromIntZeroExtend(0x40000000)) && addr.LT(Address.fromIntZeroExtend(0xc0000000));
  }

}
