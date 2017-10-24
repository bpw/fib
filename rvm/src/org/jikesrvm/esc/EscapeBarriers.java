package org.jikesrvm.esc;

import org.jikesrvm.Constants;
import org.jikesrvm.VM;
import org.jikesrvm.classloader.FieldReference;
import org.jikesrvm.classloader.RVMField;
import org.jikesrvm.objectmodel.JavaHeader;
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
public final class EscapeBarriers implements Constants {
  
  @Entrypoint
  @Inline
  public static final void fieldWriteBarrierResolved(Word rhsValue, Object o, Offset fieldOffset, int fieldInfo, int siteID) {
    EscapeState.writeObject(rhsValue, ObjectReference.fromObject(o).toAddress(), fieldOffset, fieldInfo, siteID);
  }
  
  @Entrypoint
  @Inline
  public static final void fieldWriteBarrierStaticResolved(Word rhsValue, Offset fieldOffset, int fieldInfo, int siteID) {
    EscapeState.writeStatic(rhsValue, fieldOffset, fieldInfo, siteID);
  }
  
  @Entrypoint
  @Inline
  public static final void fieldWriteBarrierUnresolved(Word rhsValue, Object o, int fieldID, int siteID) {
    int fieldInfo = 0;
    Offset fieldOffset = null;
    if (Esc.getConfig().escapePassFieldOffset()) {
      RVMField field = handleUnresolvedField(fieldID);
      fieldOffset = field.getOffset();
      fieldInfo = getFieldInfo(field, fieldID);
    } else {
      fieldInfo = getFieldInfo(fieldID);
    }
    EscapeState.writeObject(rhsValue, ObjectReference.fromObject(o).toAddress(), fieldOffset, fieldInfo, siteID);
  }
  
  @Entrypoint
  @Inline
  public static final void fieldWriteBarrierStaticUnresolved(Word rhsValue, int fieldID, int siteID) {
    int fieldInfo = 0;
    Offset fieldOffset = null;
    if (Esc.getConfig().escapePassFieldOffset()) {
      RVMField field = handleUnresolvedField(fieldID);
      fieldOffset = field.getOffset();
      fieldInfo = getFieldInfo(field, fieldID);
    } else {
      fieldInfo = getFieldInfo(fieldID);
    }
    EscapeState.writeStatic(rhsValue, fieldOffset, fieldInfo, siteID);
  }
  
  @Entrypoint
  @Inline
  public static final void arrayWriteBarrier(Word rhsValue, Object o, int index, int siteID) {
    Offset fieldOffset = Esc.getConfig().escapePassFieldOffset() ?
        Offset.fromIntZeroExtend(index << LOG_BYTES_IN_ADDRESS) : null;
    EscapeState.writeObject(rhsValue, ObjectReference.fromObject(o).toAddress(), fieldOffset, index, siteID);
  }

  @Inline
  @Entrypoint
  public static final void incrementStatsVMContextAllocation(Address addr) {
    if (VM.runningVM) {
      EscapeStats.Alloc_Runtime_VMContext.inc();
      EscapeState.incrementEscapedStats(addr);
    }
  }
  
  // Helper methods:
  
  @Inline
  public static final RVMField handleUnresolvedField(int fieldID) {
    FieldReference fieldRef = FieldReference.getMemberRef(fieldID).asFieldReference();
    return fieldRef.getResolvedField();
  }
  
  @Inline
  public static final int getFieldInfo(int fieldID) {
    if (EscInstrDecisions.passFieldInfo()) {
      if (EscInstrDecisions.useFieldOffset()) {
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
    if (EscInstrDecisions.passFieldInfo()) {
      if (EscInstrDecisions.useFieldOffset()) {
        return field.getOffset().toInt();
      } else {
        return fieldID;
      }
    } else {
      return 0;
    }
  }
}
