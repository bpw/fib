// Octet: instrumentation added to the program
package org.jikesrvm.esc.sharing;

import org.jikesrvm.Constants;
import org.jikesrvm.classloader.RVMField;
import org.jikesrvm.objectmodel.MiscHeader;
import org.jikesrvm.octet.OctetBarriers;
import org.jikesrvm.runtime.Magic;
import org.vmmagic.pragma.Entrypoint;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.NoInline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.ObjectReference;
import org.vmmagic.unboxed.Offset;

@Uninterruptible
public final class SharingBarriers implements Constants {

  @Inline
  @Entrypoint
  static final boolean readObject(Object o, int fieldOrIndexInfo, int siteID) {
    return SharingTransitions.read(ObjectReference.fromObject(o).toAddress(), MiscHeader.OCTET_OFFSET, fieldOrIndexInfo, siteID);
  }
  
  @Inline
  @Entrypoint
  static final boolean writeObject(Object o, int fieldOrIndexInfo, int siteID) {
    return SharingTransitions.write(ObjectReference.fromObject(o).toAddress(), MiscHeader.OCTET_OFFSET, fieldOrIndexInfo, siteID);
  }
  
  @Inline
  @Entrypoint
  static final boolean readStatic(Offset octetOffset, int fieldInfo, int siteID) {
    // return SharingTransitions.read(Magic.getJTOC(), octetOffset, fieldInfo, siteID);
    return false;
  }

  @Inline
  @Entrypoint
  static final boolean writeStatic(Offset octetOffset, int fieldInfo, int siteID) {
    // return SharingTransitions.write(Magic.getJTOC(), octetOffset, fieldInfo, siteID);
    return false;
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
    int fieldInfo = OctetBarriers.getFieldInfo(fieldID);
    readObject(o, fieldInfo, siteID);
  }
  
  @Entrypoint
  @Inline
  public static final void fieldWriteBarrierUnresolved(Object o, int fieldID, int siteID) {
    int fieldInfo = OctetBarriers.getFieldInfo(fieldID);
    writeObject(o, fieldInfo, siteID);
  }
  
  // Octet: LATER: the hasMetadataOffset checks are necessary because some fields --
  // in particular, *final* fields -- might get instrumentation added for them at
  // "unresolved static" accesses, but their resolved fields won't have a metadata offset.
  
  @Entrypoint
  @NoInline
  public static final void fieldReadBarrierStaticUnresolved(int fieldID, int siteID) {
    RVMField field = OctetBarriers.handleUnresolvedField(fieldID);
    if (field.hasMetadataOffset()) {
      int fieldInfo = OctetBarriers.getFieldInfo(field, fieldID);
      readStatic(field.getMetadataOffset(), fieldInfo, siteID);
    }
  }
  
  @Entrypoint
  @NoInline
  public static final void fieldWriteBarrierStaticUnresolved(int fieldID, int siteID) {
    RVMField field = OctetBarriers.handleUnresolvedField(fieldID);
    if (field.hasMetadataOffset()) {
      int fieldInfo = OctetBarriers.getFieldInfo(field, fieldID);
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
}
