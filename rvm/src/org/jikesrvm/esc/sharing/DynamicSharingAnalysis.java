/** Octet: a client analysis that doesn't really do anything */
package org.jikesrvm.esc.sharing;

import org.jikesrvm.VM;
import org.jikesrvm.classloader.NormalMethod;
import org.jikesrvm.runtime.Entrypoints;
import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public final class DynamicSharingAnalysis extends org.jikesrvm.octet.ClientAnalysis {

  @Override
  public final boolean supportsIrBasedBarriers() {
    return false;
  }
  
  @Override
  @Pure
  public boolean incRequestCounterForImplicitProtocol() {
    return false;
  }
  
  @Override
  public NormalMethod chooseBarrier(NormalMethod method, boolean isRead, boolean isField, boolean isResolved, boolean isStatic, boolean hasRedundantBarrier, boolean isSpecialized) {
    if (isField) {
      // scalar fields and statics
      if (isRead) {
        if (isResolved) {
          if (isStatic) {
            return Entrypoints.sharingFieldReadBarrierStaticResolvedMethod;
          } else {
            return Entrypoints.sharingFieldReadBarrierResolvedMethod;
          }
        } else {
          if (isStatic) {
            return Entrypoints.sharingFieldReadBarrierStaticUnresolvedMethod;
          } else {
            return Entrypoints.sharingFieldReadBarrierUnresolvedMethod;
          }
        }
      } else {
        if (isResolved) {
          if (isStatic) {
            return Entrypoints.sharingFieldWriteBarrierStaticResolvedMethod;
          } else {
            return Entrypoints.sharingFieldWriteBarrierResolvedMethod;
          }
        } else {
          if (isStatic) {
            return Entrypoints.sharingFieldWriteBarrierStaticUnresolvedMethod;
          } else {
            return Entrypoints.sharingFieldWriteBarrierUnresolvedMethod;
          }
        }
      }
    } else {
      // arrays
      if (VM.VerifyAssertions) { VM._assert(isResolved); } // array accesses can't be unresolved
      if (isRead) {
        return Entrypoints.sharingArrayReadBarrierMethod;
      } else {
        return Entrypoints.sharingArrayWriteBarrierMethod;
      }
    }
  }
}
