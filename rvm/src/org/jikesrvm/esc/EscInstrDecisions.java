// Octet: decisions about adding instrumentation to the program
package org.jikesrvm.esc;

import org.jikesrvm.Constants;
import org.jikesrvm.VM;
import org.jikesrvm.classloader.FieldReference;
import org.jikesrvm.classloader.NormalMethod;
import org.jikesrvm.classloader.RVMField;
import org.jikesrvm.classloader.RVMType;
import org.jikesrvm.classloader.TypeReference;
import org.jikesrvm.runtime.Entrypoints;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public final class EscInstrDecisions implements Constants {

  /** Pass info about the particular field or array element accessed? */
  @Inline public static final boolean passFieldInfo() {
    return Esc.getConfig().escapePassFieldInfo();
  }
  /** If passing field info, should the field be passed as an offset (otherwise it's passed as a field ID)? */
  @Inline public static final boolean useFieldOffset() {
    return Esc.getConfig().escapeUseFieldOffset();
  }
  /** Helper method for getting field info, which can be an offset or a field ID. */
  @Inline public static final int getFieldInfo(RVMField field) {
    if (passFieldInfo()) {
      if (useFieldOffset()) {
        return field.getOffset().toInt();
      } else {
        return field.getId();
      }
    } else {
      return 0;
    }
  }
  
  /** Pass information about the site (program location)? */
  @Inline public static final boolean passSite() {
    return Esc.getConfig().escapePassSite();
  }

  /** The client analysis will call this if it doesn't want to change the default behavior. */
  public static final NormalMethod chooseBarrier(boolean isField, boolean isResolved, boolean isStatic) {
    if (isField) {
      // scalar fields and statics
      if (isResolved) {
        if (isStatic) {
          return Entrypoints.escapeFieldWriteBarrierStaticResolvedMethod;
        } else {
          return Entrypoints.escapeFieldWriteBarrierResolvedMethod;
        }
      } else {
        if (isStatic) {
          return Entrypoints.escapeFieldWriteBarrierStaticUnresolvedMethod;
        } else {
          return Entrypoints.escapeFieldWriteBarrierUnresolvedMethod;
        }
      }
    } else {
      // arrays
     if (VM.VerifyAssertions) { VM._assert(isResolved); } // array accesses can't be unresolved
     return Entrypoints.escapeArrayWriteBarrierMethod;
    }
  }

  @Interruptible
  public static boolean needsEscape(TypeReference tr) {
    return !tr.isMagicType();
  }


}
