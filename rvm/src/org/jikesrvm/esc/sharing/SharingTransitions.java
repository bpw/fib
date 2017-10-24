package org.jikesrvm.esc.sharing;

import org.jikesrvm.VM;
import org.jikesrvm.esc.Esc;
import org.jikesrvm.esc.EscapeState;
import org.jikesrvm.esc.EscapeStats;
import org.jikesrvm.objectmodel.JavaHeader;
import org.jikesrvm.objectmodel.MiscHeader;
import org.jikesrvm.objectmodel.ObjectModel;
import org.jikesrvm.runtime.Magic;
import org.jikesrvm.runtime.Time;
import org.jikesrvm.scheduler.RVMThread;
import org.vmmagic.pragma.Entrypoint;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.NoInline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;

@Uninterruptible
public class SharingTransitions extends EscapeState {

  @Inline
  @Entrypoint
  public static boolean read(Address baseAddr, Offset octetOffset, int fieldOrIndexInfo, int siteID) {
    if (Esc.getConfig().escapeUseOneBitInGCHeader()) {
      Word state = baseAddr.loadWord(JavaHeader.ESCAPE_BIT_OFFSET);
      checkState(state, baseAddr.toObjectReference().toObject(), siteID);
      if (isEscapedState(state)) {
        EscapeStats.ReadObject_Escaped.inc();
      } else {
        EscapeStats.ReadObject_Not_Escaped.inc();
      }
    } else {
      Word metadata = baseAddr.loadWord(MiscHeader.ESCAPE_OFFSET);
      checkMetadata(metadata, baseAddr.toObjectReference().toObject(), siteID, false);
      
      if (isEscapeMetadata(metadata)) {
        EscapeStats.ReadObject_Escaped.inc();
      } else {
        EscapeStats.ReadObject_Not_Escaped.inc();
      }
    }
    return true;
  }

  @Inline
  @Entrypoint
  public static boolean write(Address baseAddr, Offset octetOffset, int fieldOrIndexInfo, int siteID) {
    if (Esc.getConfig().escapeUseOneBitInGCHeader()) {
      Word state = baseAddr.loadWord(JavaHeader.ESCAPE_BIT_OFFSET);
      checkState(state, baseAddr.toObjectReference().toObject(), siteID);
      if (isEscapedState(state)) {
        EscapeStats.WriteObject_Escaped.inc();
      } else {
        EscapeStats.WriteObject_Not_Escaped.inc();
      }
    } else {
      Word metadata = baseAddr.loadWord(MiscHeader.ESCAPE_OFFSET);
      checkMetadata(metadata, baseAddr.toObjectReference().toObject(), siteID, false);
      
      if (isEscapeMetadata(metadata)) {
        EscapeStats.WriteObject_Escaped.inc();
      } else {
        EscapeStats.WriteObject_Not_Escaped.inc();
      }
    }
    return true;
  }
}
