// Octet: decisions about adding instrumentation to the program
package org.jikesrvm.octet;

import org.jikesrvm.Constants;
import org.jikesrvm.VM;
import org.jikesrvm.classloader.FieldReference;
import org.jikesrvm.classloader.NormalMethod;
import org.jikesrvm.classloader.RVMField;
import org.jikesrvm.runtime.Entrypoints;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public final class InstrDecisions implements Constants {

  /** Pass info about the particular field or array element accessed? */
  @Inline public static final boolean passFieldInfo() {
    return Octet.getClientAnalysis().needsFieldInfo();
  }
  /** If passing field info, should the field be passed as an offset (otherwise it's passed as a field ID)? */
  @Inline public static final boolean useFieldOffset() {
    return Octet.getClientAnalysis().useFieldOffset();
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
    return Octet.getClientAnalysis().needsSites();
  }

  // decide about field and object metadata

  public static final boolean staticFieldMightHaveMetadata(FieldReference fieldRef) {
    //if (VM.VerifyAssertions) { VM._assert(!fieldRef.isResolved()); }
    if (Octet.getConfig().addHeaderWord()) {
      return Octet.shouldAddMetadataForField(fieldRef);
    } else {
      return false;
    }
  }

  public static final boolean staticFieldHasMetadata(RVMField field) {
    if (Octet.getConfig().addHeaderWord()) {
      boolean hasMetadata = field.hasMetadataOffset();
      // at least for now, we expect the metadata to provide the same result as for an unresolved field,
      // except that the metadata should also be avoiding final fields
      if (VM.VerifyAssertions) { VM._assert(hasMetadata == (staticFieldMightHaveMetadata(field.getMemberRef().asFieldReference()) && !field.isFinal())); }
      return hasMetadata;
    } else {
      return false;
    }
  }

  public static final boolean objectOrFieldMightHaveMetadata(FieldReference fieldRef) {
    return Octet.getConfig().addHeaderWord();
  }

  public static final boolean objectOrFieldHasMetadata(RVMField field) {
    // ignore final fields
    return !field.isFinal() &&
           objectOrFieldMightHaveMetadata(field.getMemberRef().asFieldReference());
  }

  /** The client analysis will call this if it doesn't want to change the default behavior. */
  static final NormalMethod chooseBarrier(boolean isRead, boolean isField, boolean isResolved, boolean isStatic) {
    // If pessimistic barriers are enabled, use those instead.
    if (Octet.getConfig().usePessimisticBarriers()) {
      return choosePessimisticBarrier(isRead, isField, isResolved, isStatic);
    }
    
    if (isField) {
      // scalar fields and statics
      if (isRead) {
        if (isResolved) {
          if (isStatic) {
            return Entrypoints.octetFieldReadBarrierStaticResolvedMethod;
          } else {
            return Entrypoints.octetFieldReadBarrierResolvedMethod;
          }
        } else {
          if (isStatic) {
            return Entrypoints.octetFieldReadBarrierStaticUnresolvedMethod;
          } else {
            return Entrypoints.octetFieldReadBarrierUnresolvedMethod;
          }
        }
      } else {
        if (isResolved) {
          if (isStatic) {
            return Entrypoints.octetFieldWriteBarrierStaticResolvedMethod;
          } else {
            return Entrypoints.octetFieldWriteBarrierResolvedMethod;
          }
        } else {
          if (isStatic) {
            return Entrypoints.octetFieldWriteBarrierStaticUnresolvedMethod;
          } else {
            return Entrypoints.octetFieldWriteBarrierUnresolvedMethod;
          }
        }
      }
    } else {
      // arrays
      if (VM.VerifyAssertions) { VM._assert(isResolved); } // array accesses can't be unresolved
      if (isRead) {
        return Entrypoints.octetArrayReadBarrierMethod;
      } else {
        return Entrypoints.octetArrayWriteBarrierMethod;
      }
    }
  }

  /** Chooses a pessimistic barrier, which is a barrier that always uses a CAS or fence, instead of being optimistic. */
  static final NormalMethod choosePessimisticBarrier(boolean isRead, boolean isField, boolean isResolved, boolean isStatic) {
    if (VM.VerifyAssertions) { VM._assert(Octet.getConfig().usePessimisticBarriers()); }
    
    if (isField) {
      // scalar fields and statics
      if (isRead) {
        if (isResolved) {
          if (isStatic) {
            return Entrypoints.octetPessimisticFieldReadBarrierStaticResolvedMethod;
          } else {
            return Entrypoints.octetPessimisticFieldReadBarrierResolvedMethod;
          }
        } else {
          if (isStatic) {
            return Entrypoints.octetPessimisticFieldReadBarrierStaticUnresolvedMethod;
          } else {
            return Entrypoints.octetPessimisticFieldReadBarrierUnresolvedMethod;
          }
        }
      } else {
        if (isResolved) {
          if (isStatic) {
            return Entrypoints.octetPessimisticFieldWriteBarrierStaticResolvedMethod;
          } else {
            return Entrypoints.octetPessimisticFieldWriteBarrierResolvedMethod;
          }
        } else {
          if (isStatic) {
            return Entrypoints.octetPessimisticFieldWriteBarrierStaticUnresolvedMethod;
          } else {
            return Entrypoints.octetPessimisticFieldWriteBarrierUnresolvedMethod;
          }
        }
      }
    } else {
      // arrays
      if (VM.VerifyAssertions) { VM._assert(isResolved); } // array accesses can't be unresolved
      if (isRead) {
        return Entrypoints.octetPessimisticArrayReadBarrierMethod;
      } else {
        return Entrypoints.octetPessimisticArrayWriteBarrierMethod;
      }
    }
  }
  /** returns a call for getting metadata operand of unresolved statics at runtime. */
  public static final NormalMethod resolveStatic() {
    return Entrypoints.octetResolveMethod;
  }


}
