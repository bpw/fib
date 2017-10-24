package org.jikesrvm.dr;

import org.jikesrvm.SizeConstants;
import org.jikesrvm.VM;
import org.jikesrvm.dr.metadata.ObjectShadow;
import org.jikesrvm.mm.mminterface.MemoryManager;
import org.jikesrvm.objectmodel.JavaHeader;
import org.jikesrvm.runtime.Entrypoints;
import org.jikesrvm.runtime.Magic;
import org.jikesrvm.scheduler.RVMThread;
import org.jikesrvm.scheduler.Synchronization;
import org.vmmagic.pragma.Entrypoint;
import org.vmmagic.pragma.NoInline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Address;
import org.vmmagic.unboxed.ObjectReference;

@Uninterruptible
public class DrDebug {
  
  @Entrypoint
  private volatile static int globalDebugLock = 0;
  @NoInline
  public static void lock() {
    if (VM.VerifyAssertions) VM._assert(globalDebugLock != RVMThread.getCurrentThreadSlot(), "Trying to relock!");
    while (!Synchronization.testAndSet(Magic.getJTOC(), Entrypoints.drDebugLockField.getOffset(), RVMThread.getCurrentThreadSlot()));
  }
  @NoInline
  public static void unlock() {
    int old = Synchronization.fetchAndStore(Magic.getJTOC(), Entrypoints.drDebugLockField.getOffset(), 0);
    if (VM.VerifyAssertions) VM._assert(old == RVMThread.getCurrentThreadSlot(), "Mismatched unlock!");
  }
  @NoInline
  public static void twrite(RVMThread t) {
    if (t.isDrThread()) {
      VM.sysWrite("T", t.getDrID(), " [", t.getThreadSlot()); VM.sysWrite("]  ");
    } else {
      VM.sysWrite("T_ [", t.getThreadSlot()); VM.sysWrite("]  ");      
    }
  }
  @NoInline
  public static void twrite() {
    twrite(RVMThread.getCurrentThread());
  }
  @NoInline
  public static void twriteln(String s) {
    twrite();
    VM.sysWriteln(s);
  }


  public static void inspectHeader(Object o) {
    lock();
    MemoryManager.dumpRef(ObjectReference.fromObject(o));
    VM.sysWriteln("FIB=", ObjectShadow.loadHeader(o));
    VM.sysWriteln("Length/FieldZero=", ObjectReference.fromObject(o).toAddress().loadAddress(JavaHeader.ARRAY_LENGTH_OFFSET));
//    if (FibShadow.isInBootImageData(o)) {
//      VM.sysWriteln("... in Boot Image data section");
//    } else if (FibShadow.isInBootImageCode(o)) {
//      VM.sysWriteln("... in Boot Image code section");
//    } else {
//      VM.sysWriteln("... not in Boot Image");
//    }
    Address a = ObjectReference.fromObject(o).toAddress();
    for (Address i = a.minus(JavaHeader.OBJECT_REF_OFFSET); i.LT(a); i = i.plus(SizeConstants.BYTES_IN_WORD)) {
      VM.sysWriteln("", i, "  ", i.loadWord().toAddress());
    }
    unlock();
  }
}
