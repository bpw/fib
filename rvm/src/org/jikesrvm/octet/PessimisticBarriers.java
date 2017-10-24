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
import org.vmmagic.unboxed.ObjectReference;
import org.vmmagic.unboxed.Offset;

/** Octet: pessimistic barriers that always use a CAS or fence instead of Octet's optimistic approach */
@Uninterruptible
public final class PessimisticBarriers implements Constants {

  @Inline
  static final void readObject(Object o) {
    Octet.getConfig().pessimisticRead(ObjectReference.fromObject(o).toAddress().plus(MiscHeader.OCTET_OFFSET));
  }
  
  @Inline
  static final void writeObject(Object o) {
    Octet.getConfig().pessimisticWrite(ObjectReference.fromObject(o).toAddress().plus(MiscHeader.OCTET_OFFSET));
  }
  
  @Inline
  static final void readStatic(Offset octetOffset) {
    Octet.getConfig().pessimisticRead(Magic.getJTOC().plus(octetOffset));
  }

  @Inline
  static final void writeStatic(Offset octetOffset) {
    Octet.getConfig().pessimisticWrite(Magic.getJTOC().plus(octetOffset));
  }

  @Entrypoint
  @Inline
  public static final void fieldReadBarrierResolved(Object o, int fieldInfo, int siteID) {
    readObject(o);
  }
  
  @Entrypoint
  @Inline
  public static final void fieldWriteBarrierResolved(Object o, int fieldInfo, int siteID) {
    writeObject(o);
  }
  
  @Entrypoint
  @Inline
  public static final void fieldReadBarrierStaticResolved(Offset octetOffset, int fieldInfo, int siteID) {
    readStatic(octetOffset);
  }
  
  @Entrypoint
  @Inline
  public static final void fieldWriteBarrierStaticResolved(Offset octetOffset, int fieldInfo, int siteID) {
    writeStatic(octetOffset);
  }
  
  @Entrypoint
  @Inline
  public static final void fieldReadBarrierUnresolved(Object o, int fieldID, int siteID) {
    readObject(o);
  }
  
  @Entrypoint
  @Inline
  public static final void fieldWriteBarrierUnresolved(Object o, int fieldID, int siteID) {
    writeObject(o);
  }
  
  @Entrypoint
  @NoInline
  public static final void fieldReadBarrierStaticUnresolved(int fieldID, int siteID) {
    RVMField field = handleUnresolvedField(fieldID);
    if (field.hasMetadataOffset()) {
      readStatic(field.getMetadataOffset());
    }
  }
  
  @Entrypoint
  @NoInline
  public static final void fieldWriteBarrierStaticUnresolved(int fieldID, int siteID) {
    RVMField field = handleUnresolvedField(fieldID);
    if (field.hasMetadataOffset()) {
      writeStatic(field.getMetadataOffset());
    }
  }
  
  @Entrypoint
  @Inline
  public static final void arrayReadBarrier(Object o, int index, int siteID) {
    readObject(o);
  }
  
  @Entrypoint
  @Inline
  public static final void arrayWriteBarrier(Object o, int index, int siteID) {
    writeObject(o);
  }

  // Helper method:
  
  @Inline
  private static final RVMField handleUnresolvedField(int fieldID) {
    FieldReference fieldRef = FieldReference.getMemberRef(fieldID).asFieldReference();
    return fieldRef.getResolvedField();
  }
}
