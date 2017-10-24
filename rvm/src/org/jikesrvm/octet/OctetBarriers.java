// Octet: instrumentation added to the program
package org.jikesrvm.octet;

import org.jikesrvm.Constants;
import org.jikesrvm.classloader.FieldReference;
import org.jikesrvm.classloader.RVMField;
import org.jikesrvm.objectmodel.MiscHeader;
import org.jikesrvm.runtime.Magic;
import org.vmmagic.pragma.Entrypoint;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.NoInline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.ObjectReference;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;

@Uninterruptible
public final class OctetBarriers implements Constants {

  @Inline
  @Entrypoint
  static final boolean readObject(Object o, int fieldOrIndexInfo, int siteID) {
    return StateTransfers.read(ObjectReference.fromObject(o).toAddress(), MiscHeader.OCTET_OFFSET, fieldOrIndexInfo, siteID);
  }
  
  @Inline
  @Entrypoint
  static final boolean writeObject(Object o, int fieldOrIndexInfo, int siteID) {
    return StateTransfers.write(ObjectReference.fromObject(o).toAddress(), MiscHeader.OCTET_OFFSET, fieldOrIndexInfo, siteID);
  }
  
  @Inline
  @Entrypoint
  static final boolean readStatic(Offset octetOffset, int fieldInfo, int siteID) {
    return StateTransfers.read(Magic.getJTOC(), octetOffset, fieldInfo, siteID);
  }

  @Inline
  @Entrypoint
  static final boolean writeStatic(Offset octetOffset, int fieldInfo, int siteID) {
    return StateTransfers.write(Magic.getJTOC(), octetOffset, fieldInfo, siteID);
  }

  @Entrypoint
  @Inline
  public static final void fieldReadBarrierResolved(Object o, int fieldInfo, int siteID) {
    readObject(o, fieldInfo, siteID);
  }
  
  @Entrypoint
  @Inline
  public static final void fieldWriteBarrierResolved(Object o, int fieldInfo, int siteID) {
    writeObject(o, fieldInfo, siteID);
  }
  
  @Entrypoint
  @Inline
  public static final void fieldReadBarrierStaticResolved(Offset octetOffset, int fieldInfo, int siteID) {
    readStatic(octetOffset, fieldInfo, siteID);
  }
  
  @Entrypoint
  @Inline
  public static final void fieldWriteBarrierStaticResolved(Offset octetOffset, int fieldInfo, int siteID) {
    writeStatic(octetOffset, fieldInfo, siteID);
  }
  
  // Octet: TODO: field might not actually be resolved here if inserted in the opt compiler during early instrumentation!
  
  // Octet: TODO: can we resolve a field?  is that interruptible?  how does that work in Jikes?
  
  @Entrypoint
  @Inline
  public static final void fieldReadBarrierUnresolved(Object o, int fieldID, int siteID) {
    int fieldInfo = getFieldInfo(fieldID);
    readObject(o, fieldInfo, siteID);
  }
  
  @Entrypoint
  @Inline
  public static final void fieldWriteBarrierUnresolved(Object o, int fieldID, int siteID) {
    int fieldInfo = getFieldInfo(fieldID);
    writeObject(o, fieldInfo, siteID);
  }
  
  // Octet: LATER: the hasMetadataOffset checks are necessary because some fields --
  // in particular, *final* fields -- might get instrumentation added for them at
  // "unresolved static" accesses, but their resolved fields won't have a metadata offset.
  
  @Entrypoint
  @NoInline
  public static final void fieldReadBarrierStaticUnresolved(int fieldID, int siteID) {
    RVMField field = handleUnresolvedField(fieldID);
    if (field.hasMetadataOffset()) {
      int fieldInfo = getFieldInfo(field, fieldID);
      readStatic(field.getMetadataOffset(), fieldInfo, siteID);
    }
  }
  
  @Entrypoint
  @NoInline
  public static final void fieldWriteBarrierStaticUnresolved(int fieldID, int siteID) {
    RVMField field = handleUnresolvedField(fieldID);
    if (field.hasMetadataOffset()) {
      int fieldInfo = getFieldInfo(field, fieldID);
      writeStatic(field.getMetadataOffset(), fieldInfo, siteID);
    }
  }
  
  @Entrypoint
  @Inline
  public static final void arrayReadBarrier(Object o, int index, int siteID) {
    readObject(o, index, siteID);
  }
  
  @Entrypoint
  @Inline
  public static final void arrayWriteBarrier(Object o, int index, int siteID) {
    writeObject(o, index, siteID);
  }

  // Helper methods:
  
  @Inline
  public static final RVMField handleUnresolvedField(int fieldID) {
    FieldReference fieldRef = FieldReference.getMemberRef(fieldID).asFieldReference();
    return fieldRef.getResolvedField();
  }
  
  @Inline
  public static final int getFieldInfo(int fieldID) {
    if (InstrDecisions.passFieldInfo()) {
      if (InstrDecisions.useFieldOffset()) {
        return handleUnresolvedField(fieldID).getOffset().toInt();
      } else {
        return fieldID;
      }
    } else {
      return 0;
    }
  }
  
  @Inline
  public static final int getFieldInfo(RVMField field, int fieldID) {
    if (InstrDecisions.passFieldInfo()) {
      if (InstrDecisions.useFieldOffset()) {
        return field.getOffset().toInt();
      } else {
        return fieldID;
      }
    } else {
      return 0;
    }
  }
  
  /** takes field ID of an unresolved static and returns its metadata; it is resolved at runtime before the access */
  @Entrypoint
  @NoInline
  public static final Word getResolved(int fieldID) {
    // FieldReference fieldRef = FieldReference.getMemberRef(fieldID).asFieldReference();
    // RVMField field = fieldRef.getResolvedField();
    RVMField field = handleUnresolvedField(fieldID);
    // Man: actually getAddressAtOffset() performs the actual load word from the memory address, but why not use the easier-to-understand idiom? 
    // Address addr = Magic.getAddressAtOffset( Magic.getJTOC(), field.getMetadataOffset());
    // return addr.loadWord();
    if (field.hasMetadataOffset()) {
      return Magic.getTocPointer().prepareWord(field.getMetadataOffset());
    }
    /** Man: returning zero will make the IR call the slowpath method, which is fieldReadBarrierStaticUnresolved() or fieldWriteBarrierStaticUnresolved().
     *  It will try to resolve the fieldID again, then just returns without executing the barrier.
     */
    return Word.zero();
  }
}
