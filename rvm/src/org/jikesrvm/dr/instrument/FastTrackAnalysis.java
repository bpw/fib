package org.jikesrvm.dr.instrument;

import org.jikesrvm.VM;
import org.jikesrvm.classloader.NormalMethod;
import org.jikesrvm.compilers.baseline.ia32.OctetBaselineInstr;
import org.jikesrvm.compilers.baseline.ia32.PlainFastTrackBaselineInstr;
import org.jikesrvm.compilers.opt.OctetOptInstr;
import org.jikesrvm.compilers.opt.OctetOptSelection;
import org.jikesrvm.compilers.opt.PlainFastTrackOptInstr;
import org.jikesrvm.compilers.opt.PlainFastTrackOptSelection;
import org.jikesrvm.compilers.opt.RedundantBarrierRemover;
import org.jikesrvm.compilers.opt.ir.Instruction;
import org.jikesrvm.dr.DrRuntime;
import org.jikesrvm.octet.ClientAnalysis;
import org.jikesrvm.octet.Octet;
import org.jikesrvm.runtime.Entrypoints;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class FastTrackAnalysis extends ClientAnalysis {

  @Override
  public boolean supportsIrBasedBarriers() {
    // TODO Auto-generated method stub
    return false;
  }

  @Override
  public boolean incRequestCounterForImplicitProtocol() {
    // TODO Auto-generated method stub
    return false;
  }


  /** Allows the client analysis to do something when the VM is booting. */
  @Interruptible
  protected void boot() {
    // some analyses won't want to do anything, so that's the default behavior
  }

  /** Should the Octet barriers pass any field or array index info? */
  @Inline
  protected boolean needsFieldInfo() {
    return true;
  }

  /** Should the Octet barrier pass the field ID (value = false) or the field offset (value = true)?
        Only relevant if needsFieldInfo() == true */
  @Inline
  protected boolean useFieldOffset() {
    return true;
  }

  /** Let the client analysis specify the chooseBarrier. */
  public NormalMethod chooseBarrier(NormalMethod method, boolean isRead, boolean isField, boolean isResolved, boolean isStatic, boolean hasRedundantBarrier, boolean isSpecialized) {
    if (isField) {
      // scalar fields and statics
      if (isRead) {
        if (isResolved) {
          if (isStatic) {
            return DrRuntime.maxLiveDrThreads() <= 1 ? Entrypoints.drGetStaticResolvedMethod : Entrypoints.drGetStaticResolvedObserveInitMethod;
          } else {
            return Entrypoints.drGetFieldResolvedMethod;
          }
        } else {
          if (isStatic) {
            return Entrypoints.drGetStaticUnresolvedMethod;
          } else {
            return Entrypoints.drGetFieldUnresolvedMethod;
          }
        }
      } else {
        if (isResolved) {
          if (isStatic) {
            return Entrypoints.drPutStaticResolvedMethod;
          } else {
            return Entrypoints.drPutFieldResolvedMethod;
          }
        } else {
          if (isStatic) {
            return Entrypoints.drPutStaticUnresolvedMethod;
          } else {
            return Entrypoints.drPutFieldUnresolvedMethod;
          }
        }
      }
    } else {
      // arrays
      if (VM.VerifyAssertions) { VM._assert(isResolved); } // array accesses can't be unresolved
      if (isRead) {
        return Entrypoints.drAloadMethod;
      } else {
        return Entrypoints.drAstoreMethod;
      }
    }
  }

  /** Support overriding/customizing barrier insertion in the baseline compiler */
  @Interruptible
  public OctetBaselineInstr newBaselineInstr() {
    return new PlainFastTrackBaselineInstr();
  }

  /** Support overriding/customizing the choice of which instructions the opt compiler should instrument */
  @Interruptible
  public OctetOptSelection newOptSelect() {
    return new PlainFastTrackOptSelection();
  }

  /** Analyses that want to always override the redundant barrier analysis level should override this method,
        rather than overriding BaseConfig.overrideDefaultRedundantBarrierAnalysisLevel() it in their derived configs. */
  @Interruptible
  public RedundantBarrierRemover newRedundantBarriersAnalysis() {
    return new RedundantBarrierRemover(Octet.getConfig().overrideDefaultRedundantBarrierAnalysisLevel());
  }

  /** Support overriding/customizing barrier insertion in the opt compiler */
  @Interruptible
  public OctetOptInstr newOptInstr(boolean lateInstr, RedundantBarrierRemover redundantBarrierRemover) {
    return new PlainFastTrackOptInstr(lateInstr, redundantBarrierRemover);
  }

  /** Let client analyses throw exceptions from barriers. */
  @Inline
  public boolean barriersCanThrowExceptions() {
    return false;
  }

  /** Decide whether we should instrument instructions having redundant barriers. Field sensitive analysis might need to turn this on to instrument accesses that have redundant barriers.*/
  // OctetOptInstr.instrumentScalarAccess:246 NPE crash with true.
  public boolean instrInstructionHasRedundantBarrier(Instruction inst) { 
    return false; 
  }

  @Inline
  public boolean isSpecializedMethod(NormalMethod method) {
    return false;
  }

  /** Call this when the client analysis only want to instrument a set of selected methods. By default, it returns true, which means all methods will be considered as candidates for instrumentation.*/
  @Inline
  public boolean isSelectedInstrMethod(NormalMethod method) {
    return true;
  }
}
