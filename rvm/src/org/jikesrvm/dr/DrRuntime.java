package org.jikesrvm.dr;

import org.jikesrvm.VM;
import org.jikesrvm.classloader.FieldReference;
import org.jikesrvm.classloader.RVMClass;
import org.jikesrvm.classloader.RVMField;
import org.jikesrvm.classloader.RVMType;
import org.jikesrvm.config.dr.Base.InlineLevel;
import org.jikesrvm.dr.fib.FibComm;
import org.jikesrvm.dr.instrument.FieldTreatment;
import org.jikesrvm.dr.metadata.AccessHistory;
import org.jikesrvm.dr.metadata.Epoch;
import org.jikesrvm.dr.metadata.ObjectShadow;
import org.jikesrvm.dr.metadata.VC;
import org.jikesrvm.mm.mminterface.Barriers;
import org.jikesrvm.mm.mminterface.MemoryManager;
import org.jikesrvm.objectmodel.ObjectModel;
import org.jikesrvm.runtime.Magic;
import org.jikesrvm.scheduler.RVMThread;
import org.jikesrvm.scheduler.ThinLock;
import org.mmtk.plan.MutatorContext;
import org.vmmagic.pragma.Entrypoint;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.pragma.NoInline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.pragma.UninterruptibleNoWarn;
import org.vmmagic.pragma.Unpreemptible;
import org.vmmagic.unboxed.ObjectReference;
import org.vmmagic.unboxed.Offset;
import org.vmmagic.unboxed.Word;
import org.vmmagic.unboxed.WordArray;

/**
 * Barriers/hooks for runtime events.
 * 
 * Some are called directly from RVM code (e.g., many synchronization events).
 * Calls to others are inserted by the compilers.
 * 
 * All access barriers are inserted before accesses unless names indicate otherwise.
 * 
 * @author bpw
 *
 */
@Uninterruptible
public final class DrRuntime {
  
  /** Enable extra event reporting. */
  private static final boolean PRINT = VM.VerifyAssertions && false;
  
  /** Enable types of access barriers. */
  private static final boolean   RESOLVED_FIELD  = true;
  private static final boolean UNRESOLVED_FIELD  = true;
  private static final boolean   RESOLVED_STATIC = true;
  private static final boolean UNRESOLVED_STATIC = true;
  private static final boolean ARRAY = true;
  
  // Access events
  
  /**
   * Analyze a read with access history at object+historyOffset.
   * Called by all specific read barriers below.
   * @param object
   * @param historyOffset
   */
  @Inline
  @Unpreemptible
  private static void read(Object object, Offset historyOffset) {
    if (Dr.config().drInlineRaceChecks() == InlineLevel.THICK) {
      Dr.fasttrack().read(object, historyOffset);
    } else if (Dr.config().drInlineRaceChecks() == InlineLevel.THIN) {
      Dr.fasttrack().readThin(object, historyOffset);
    } else {
      Dr.fasttrack().readNoInline(object, historyOffset);
    }
  }
  @Inline
  @Unpreemptible
  private static void readStatic(Offset historyOffset) {
    if (Dr.config().drInlineRaceChecks() == InlineLevel.THICK) {
      Dr.fasttrackStatic().read(null, historyOffset);
    } else if (Dr.config().drInlineRaceChecks() == InlineLevel.THIN) {
      Dr.fasttrack().readThin(null, historyOffset);
    } else {
      Dr.fasttrackStatic().readNoInline(null, historyOffset);
    }
  }
  
  /**
   * Analyze a write with access history at object+historyOffset.
   * Called by all specific write barriers below.
   * @param object
   * @param historyOffset
   */
  @Inline
  @Unpreemptible
  private static void write(Object object, Offset historyOffset) {
    if (Dr.config().drInlineRaceChecks() == InlineLevel.THICK) {
      Dr.fasttrack().write(object, historyOffset);
    } else if (Dr.config().drInlineRaceChecks() == InlineLevel.THIN) {
      Dr.fasttrack().writeThin(object, historyOffset);
    } else {
      Dr.fasttrack().writeNoInline(object, historyOffset);
    }
  }
  @Inline
  @Unpreemptible
  private static void writeStatic(Offset historyOffset) {
    if (Dr.config().drInlineRaceChecks() == InlineLevel.THICK) {
      Dr.fasttrackStatic().write(null, historyOffset);
    } else if (Dr.config().drInlineRaceChecks() == InlineLevel.THIN) {
      Dr.fasttrack().writeThin(null, historyOffset);
    } else {
      Dr.fasttrackStatic().writeNoInline(null, historyOffset);
    }
  }
  
  @SuppressWarnings("unused")
  @Inline
  @Entrypoint
  @Unpreemptible
  public static void getfieldResolved(final Object object, final Offset historyOffset) {
    if (!RESOLVED_FIELD) return;
    if (PRINT && maxLiveDrThreads() > 2) {
      DrDebug.lock();
      DrDebug.twrite(); VM.sysWriteln("> getfield resolved ", AccessHistory.address(object, historyOffset));
      DrDebug.twrite(); VM.sysWriteln("  in a ", ObjectModel.getTIB(object).getType().getTypeRef().getName());
      DrDebug.unlock();
    }
    if (VM.VerifyAssertions && Dr.COMMUNICATION) FibComm.assertNotBlocked();
    if (VM.VerifyAssertions) {
      VM._assert(object != null, "getfieldResolved(null,...)");
      VM._assert(MemoryManager.validRef(ObjectReference.fromObject(object)));
      if (!RVMThread.getCurrentThread().enterDR(object, historyOffset)) {
        DrDebug.lock();
        DrDebug.twrite(); VM.sysWriteln(" reentrant getfield resolved ", AccessHistory.address(object, historyOffset));
        DrDebug.twrite(); VM.sysWriteln("  in a ", ObjectModel.getTIB(object).getType().getTypeRef().getName());
        RVMThread.dumpStack();
        DrDebug.unlock();
      }
    }
    if (Dr.STATS) DrStats.read.inc();
    read(object, historyOffset);
    if (VM.VerifyAssertions) {
      RVMThread.getCurrentThread().exitDR(object, historyOffset);
    }
    if (PRINT && maxLiveDrThreads() > 2) {
      DrDebug.lock();
      DrDebug.twrite(); VM.sysWriteln("< getfield resolved ", AccessHistory.address(object, historyOffset));
      DrDebug.unlock();
    }
  }
  
  // Specific read barriers

  @Entrypoint
  @Unpreemptible
  public static void getfieldUnresolved(final Object object, final int fid) {
    if (!UNRESOLVED_FIELD) return;
    if (PRINT) DrDebug.twriteln("> getfield unresolved");
    if (VM.VerifyAssertions && Dr.COMMUNICATION) FibComm.assertNotBlocked();
    if (VM.VerifyAssertions) {
      VM._assert(object != null, "getFieldUnresolved(null,...)");
      VM._assert(MemoryManager.validRef(ObjectReference.fromObject(object)));
    }
    if (Dr.STATS) DrStats.read.inc();
    RVMField field = FieldReference.getMemberRef(fid).asFieldReference().getResolvedField();
    if (VM.VerifyAssertions) RVMThread.getCurrentThread().enterDR(object, field.getOffset());
    if (FieldTreatment.check(field)) {
      read(object, field.getDrOffset());
    } else if (FieldTreatment.vol(field)) {
      volatileReadResolved(object, field.getDrOffset());
    }
    if (PRINT) DrDebug.twriteln("< getfield unresolved");
  }
  
  @Inline
  @Entrypoint
  @Unpreemptible
  public static void getstaticResolved(final Offset historyOffsetFromZero) {
    if (!RESOLVED_STATIC) return;
    if (PRINT) DrDebug.twriteln("> getstatic resolved");
    if (VM.VerifyAssertions && Dr.COMMUNICATION) FibComm.assertNotBlocked();
    if (VM.VerifyAssertions) RVMThread.getCurrentThread().enterDR();
    if (Dr.STATS) DrStats.read.inc();
    readStatic(historyOffsetFromZero);
    if (VM.VerifyAssertions) RVMThread.getCurrentThread().exitDR();
    if (PRINT) DrDebug.twriteln("< getstatic resolved");
  }
  
  @Inline
  @Entrypoint
  @Unpreemptible
  public static void getstaticResolvedObserveInit(final Offset historyOffsetFromZero, int classID) {
    if (!RESOLVED_STATIC) return;
    if (PRINT) DrDebug.twriteln("> getstatic resolved order init");
    if (VM.VerifyAssertions && Dr.COMMUNICATION) FibComm.assertNotBlocked();
    if (VM.VerifyAssertions) RVMThread.getCurrentThread().enterDR();
    observeClassInit(classID);
    if (Dr.STATS) DrStats.read.inc();
    readStatic(historyOffsetFromZero);
    if (VM.VerifyAssertions) RVMThread.getCurrentThread().exitDR();
    if (PRINT) DrDebug.twriteln("< getstatic resolved order init");
  }
  @Inline
  private static void observeClassInit(int classID) {
    if (Dr.STATS) DrStats.getStaticObserveInitAll.inc();
    if (!VM.runningVM || !VM.fullyBooted || maxLiveDrThreads() <= 1) return;
    if (Dr.STATS) DrStats.getStaticObserveInitMultithreaded.inc();
    if (!RVMThread.getCurrentThread().drClassInitsObserved.get(classID)) {
      observeClassInitSlowPath(classID);
    }
  }
  @NoInline
  private static void observeClassInitSlowPath(int classID) {
    if (Dr.STATS) DrStats.getStaticObserveInitSlow.inc();
    WordArray initVC = RVMType.getType(classID).asClass().drGetClassInitVC();
    if (initVC != null) {
      VC.advanceTo(RVMThread.getCurrentThread().drThreadVC, initVC);
      RVMThread.getCurrentThread().drClassInitsObserved.set(classID);
    }
  }
  
  @Entrypoint
  @Uninterruptible
  private static void postGetStaticFinalResolved(final int classID) {
    if (!VM.runningVM || !VM.fullyBooted || maxLiveDrThreads() <= 1) return;
    if (VM.VerifyAssertions && Dr.COMMUNICATION) FibComm.assertNotBlocked();
    if (VM.VerifyAssertions) RVMThread.getCurrentThread().enterDR();
    observeClassInit(classID);
    if (VM.VerifyAssertions) RVMThread.getCurrentThread().exitDR();
  }


  @Entrypoint
  @Unpreemptible
  public static void getstaticUnresolved(final int fid) {
    if (!UNRESOLVED_STATIC) return;
    if (PRINT) DrDebug.twriteln("> getstatic unresolved");
    if (VM.VerifyAssertions && Dr.COMMUNICATION) FibComm.assertNotBlocked();
    if (VM.VerifyAssertions) RVMThread.getCurrentThread().enterDR();
    if (Dr.STATS) DrStats.read.inc();
    RVMField field = FieldReference.getMemberRef(fid).asFieldReference().getResolvedField();
    observeClassInit(field.getDeclaringClass().getId());
    if (FieldTreatment.check(field)) {
      readStatic(field.getDrOffset());
    } else if (FieldTreatment.vol(field)) {
      volatileReadResolved(null, field.getDrOffset());
    }
    if (VM.VerifyAssertions) RVMThread.getCurrentThread().exitDR();
    if (PRINT) DrDebug.twriteln("< getstatic unresolved");
  }

  @Inline
  @Entrypoint
  @Unpreemptible
  public static void aload(final Object array, final int index) {
    // Octet FIXME Barrier insertion should really happen AFTER bounds check!
    if (!ARRAY || index < 0 || index >= ObjectModel.getArrayLength(array)) return;
    if (PRINT) DrDebug.twriteln("> aload");
    if (VM.VerifyAssertions && Dr.COMMUNICATION) FibComm.assertNotBlocked();
    if (VM.VerifyAssertions) {
      VM._assert(array != null, "aload(null,...)");
      VM._assert(MemoryManager.validRef(ObjectReference.fromObject(array)));
      RVMThread.getCurrentThread().enterDR();
    }
    if (Dr.STATS) {
      DrStats.read.inc();
      DrStats.aload.inc();
    }
    read(ObjectShadow.getArrayShadow(array), AccessHistory.arrayHistoryOffset(index));
    if (VM.VerifyAssertions) RVMThread.getCurrentThread().exitDR();
    if (PRINT) DrDebug.twriteln("< aload");
  }
  
  // Specific write barriers
  
  @Inline
  @Entrypoint
  @Unpreemptible
  public static void putfieldResolved(final Object object, final Offset historyOffset) {
    if (!RESOLVED_FIELD) return;
    if (PRINT) DrDebug.twriteln("> putfield resolved");
    if (VM.VerifyAssertions && Dr.COMMUNICATION) FibComm.assertNotBlocked();
    if (VM.VerifyAssertions) {
      VM._assert(object != null, "putfieldResolved(null,...)");
      VM._assert(MemoryManager.validRef(ObjectReference.fromObject(object)));
      RVMThread.getCurrentThread().enterDR(object, historyOffset);
    }
    if (Dr.STATS) DrStats.write.inc();
    write(object, historyOffset);
    if (VM.VerifyAssertions) RVMThread.getCurrentThread().exitDR(object, historyOffset);
    if (PRINT) DrDebug.twriteln("< putfield resolved");
  }

  @Entrypoint
  @Unpreemptible
  public static void putfieldUnresolved(final Object object, final int fid) {
    if (!UNRESOLVED_FIELD) return;
    if (PRINT) DrDebug.twriteln("> putfield unresolved");
    if (VM.VerifyAssertions && Dr.COMMUNICATION) FibComm.assertNotBlocked();
    if (VM.VerifyAssertions) {
      VM._assert(object != null, "putfieldUnresolved(null,...)");
      VM._assert(MemoryManager.validRef(ObjectReference.fromObject(object)));
    }
    if (Dr.STATS) DrStats.write.inc();
    RVMField field = FieldReference.getMemberRef(fid).asFieldReference().getResolvedField();
    if (VM.VerifyAssertions) RVMThread.getCurrentThread().enterDR(object, field.getOffset());
    if (FieldTreatment.check(field)) {
      write(object, field.getDrOffset());
    } else if (FieldTreatment.vol(field)) {
      volatileWriteResolved(object, field.getDrOffset());
    }
    if (VM.VerifyAssertions) RVMThread.getCurrentThread().exitDR(object, Offset.zero());
    if (PRINT) DrDebug.twriteln("< putfield unresolved");
  }

  @Inline
  @Entrypoint
  @Unpreemptible
  public static void putstaticResolved(final Offset historyOffsetFromZero) {
    if (!RESOLVED_STATIC) return;
    if (PRINT) DrDebug.twriteln("> putstatic resolved");
    if (VM.VerifyAssertions && Dr.COMMUNICATION) FibComm.assertNotBlocked();
    if (VM.VerifyAssertions) RVMThread.getCurrentThread().enterDR(null, historyOffsetFromZero);
    if (Dr.STATS) DrStats.write.inc();
    writeStatic(historyOffsetFromZero);
    if (VM.VerifyAssertions) RVMThread.getCurrentThread().exitDR();
    if (PRINT) DrDebug.twriteln("< putstatic resolved");
  }
  
  @Entrypoint
  @Unpreemptible
  public static void putstaticUnresolved(final int fid) {
    if (!UNRESOLVED_STATIC) return;
    if (PRINT) DrDebug.twriteln("> putstatic unresolved");
    if (VM.VerifyAssertions && Dr.COMMUNICATION) FibComm.assertNotBlocked();
    if (VM.VerifyAssertions) RVMThread.getCurrentThread().enterDR();
    if (Dr.STATS) DrStats.write.inc();
    RVMField field = FieldReference.getMemberRef(fid).asFieldReference().getResolvedField();
    if (FieldTreatment.check(field)) {
      writeStatic(field.getDrOffset());
    } else if (FieldTreatment.vol(field)) {
      volatileWriteResolved(null, field.getDrOffset());
    }
    if (VM.VerifyAssertions) RVMThread.getCurrentThread().exitDR();
    if (PRINT) DrDebug.twriteln("< putstatic unresolved");
  }

  @Inline
  @Entrypoint
  @Unpreemptible
  public static void astore(final Object array, final int index) {
    // LATER Barrier insertion should really happen AFTER bounds check!
    if (!ARRAY || index < 0 || index >= ObjectModel.getArrayLength(array)) return;
    if (PRINT) DrDebug.twriteln("> astore");
    if (VM.VerifyAssertions && Dr.COMMUNICATION) FibComm.assertNotBlocked();
    if (VM.VerifyAssertions) {
      VM._assert(array != null, "astore(null,...)");
      VM._assert(MemoryManager.validRef(ObjectReference.fromObject(array)));
      RVMThread.getCurrentThread().enterDR();
    }
    if (Dr.STATS) {
      DrStats.write.inc();
      DrStats.astore.inc();
    }
    write(ObjectShadow.getArrayShadow(array), AccessHistory.arrayHistoryOffset(index));
    if (VM.VerifyAssertions) RVMThread.getCurrentThread().exitDR();
    if (PRINT) DrDebug.twriteln("< astore");
  }
  
  
  // Thread accounting
  
  /**
   * Number of interesting threads created so far.
   */
  private static volatile int drThreadCount = 0;
  
  /**
   * Number of interesting threads created so far.
   * @return
   */
  @Inline
  public static int maxLiveDrThreads() {
    return drThreadCount;
  }
  
  /**
   * Interesting threads.
   */
  protected static final RVMThread[] drThreads = new RVMThread[Epoch.MAX_THREADS];
  
  /**
   * Get FIB thread with ID tid.
   * @param tid
   * @return
   */
  @Inline
  public static RVMThread getDrThread(int tid) {
    if (drThreadCount <= tid) {
      DrDebug.lock();
      DrDebug.twrite(); VM.sysWriteln("Requested DR thread ", tid, " of ", drThreadCount, " max live DR threads -- spinning to wait...");
      RVMThread.dumpStack();
      DrDebug.unlock();
      while (drThreadCount <= tid) {
        Magic.pause();
      }
      DrDebug.lock();
      DrDebug.twrite(); VM.sysWriteln("DR thread ", tid, " should now be available (max live = ", drThreadCount, ")");
      DrDebug.unlock();
    }
    RVMThread t = drThreads[tid];
    if (t == null) {
      DrDebug.lock();
      DrDebug.twrite(); VM.sysWriteln("Requested invalid DR thread ", tid, " of ", drThreadCount, " max live DR threads");
      DrDebug.unlock();
      VM.sysFail("Requested invalid DR thread.");
    }
    return t;
  }
  

  
  // Thread events

  @Interruptible
  public static FibComm newThread(final RVMThread thread) {
    if (VM.VerifyAssertions && Dr.COMMUNICATION) FibComm.assertNotBlocked();
    final int tid;
    synchronized (drThreads) {
      // Before second thread, fill first thread's class observations vector.
      if (1 == maxLiveDrThreads()) {
        RVMThread.getCurrentThread().drClassInitsObserved.setUpThrough(DrRuntime.maxSingleThreadedClassInit);
      }
      
      tid = drThreadCount;
      if (Epoch.MAX_TID < tid) {
        DrDebug.lock();
        DrDebug.twrite();  VM.sysWrite("Too many threads creating ");
        DrDebug.twrite(thread);
        VM.sysWriteln("Increase your config's epochTidBits().");
        DrDebug.unlock();
        VM.sysExit(124);
      }

      WordArray em = VC.create();
      VC.set(em, tid, Epoch.one(tid));
      thread.setDrEpoch(Epoch.one(tid));
      FibComm fc = new FibComm(thread, tid, em);
      // Reuse ID for boot thread and main thread.
      if (tid > 0 || thread.isMainThread()) {
        drThreads[tid] = thread;
        drThreadCount++;
      }
      
      return fc;
    }
  }
  
  public static void fork(final RVMThread child) {
    if (VM.VerifyAssertions && Dr.COMMUNICATION) FibComm.assertNotBlocked();
    if (VM.VerifyAssertions) {
      VM._assert(child.isDrThread());
    }
    final WordArray pvc = RVMThread.getCurrentThread().drThreadVC;

    VC.advanceTo(child.drThreadVC, pvc);
    VC.set(pvc, RVMThread.getCurrentThread().incDrEpoch());

    // Inherit all class init observations
    child.drClassInitsObserved.copyBits(RVMThread.getCurrentThread().drClassInitsObserved);
  }
  
  public static void join(final RVMThread joinee) {
    if (VM.VerifyAssertions && Dr.COMMUNICATION) FibComm.assertNotBlocked();
    if (VM.VerifyAssertions) {
      VM._assert(joinee.isDrThread());
    }
    VC.advanceTo(RVMThread.getCurrentThread().drThreadVC, joinee.drThreadVC);
  }
  
  public static void terminateThread(final RVMThread dead) {
    if (VM.VerifyAssertions && Dr.COMMUNICATION) FibComm.assertNotBlocked();
    if (Dr.COMMUNICATION) {
      DrRuntime.block();
    }
    Dr.fasttrack().terminateThread(dead);
  }
  
  
  // Lock events
  
  @Unpreemptible
  public static void monitorenter(final Object monitor) {
    if (PRINT) DrDebug.twriteln("> monitorenter");
    if (VM.VerifyAssertions && Dr.COMMUNICATION) FibComm.assertNotBlocked();
    if (VM.VerifyAssertions) {
      VM._assert(Dr.LOCKS);
      VM._assert(monitor != null);
      VM._assert(RVMThread.getCurrentThread().holdsLock(monitor));
    }
    
    final WordArray vc = ObjectShadow.getVC(monitor);
    VC.advanceTo(RVMThread.getCurrentThread().drThreadVC, vc);

    if (Dr.STATS) DrStats.acquire.inc();
    if (PRINT) DrDebug.twriteln("< monitorenter");
  }
  
  @Unpreemptible
  public static void monitorexit(final Object monitor) {
    if (PRINT) DrDebug.twriteln("> monitorexit");
    if (VM.VerifyAssertions && Dr.COMMUNICATION) FibComm.assertNotBlocked();
    if (VM.VerifyAssertions) {
      VM._assert(Dr.LOCKS);
      VM._assert(monitor != null);
      VM._assert(RVMThread.getCurrentThread().holdsLock(monitor));
    }
    
    final WordArray lvc = ObjectShadow.getVC(monitor);
    if (VM.VerifyAssertions) VM._assert(lvc != null);
    final WordArray tvc = RVMThread.getCurrentThread().drThreadVC;
    VC.advanceTo(tvc, lvc);

    VC.advanceTo(lvc, tvc);
    VC.set(tvc, RVMThread.getCurrentThread().incDrEpoch());
    if (Dr.STATS) DrStats.release.inc();
    
    if (PRINT) DrDebug.twriteln("< monitorexit");
  }
  
  /**
   * Check if monitor is being locked reentrantly.
   * @param monitor
   * @return
   */
  @UninterruptibleNoWarn("getHeavyLock is non-@Uninterruptible only if create=true; we use create=false")
  private static boolean lockIsRecursive(final Object monitor) {
    final Word lockWord = ObjectReference.fromObject(monitor).toAddress().loadWord(ObjectModel.getThinLockOffset(monitor));
    if (ThinLock.isFat(lockWord)) {
      return ObjectModel.getHeavyLock(monitor, false).getRecursionCount() > 1;
    } else {
      return ThinLock.getRecCount(lockWord) > 1;
    }
  }
  
  /**
   * Accounting for stats.
   * @param monitor
   */
  private static void countMonitorOpTypes(Object monitor) {
    if (Dr.STATS) {
      if (RVMThread.getCurrentThread().isDrThread()) {
        DrStats.fibThreadMonitorOps.inc();
      } else {
        DrStats.nonFibThreadMonitorOps.inc();
      }
      if (lockIsRecursive(monitor)) {
        DrStats.recursiveMonitorOps.inc();
      } else {
        DrStats.nonRecursiveMonitorOps.inc();
      }
    }
  }
  
  @Entrypoint
  @Unpreemptible
  public static void genericLock(final Object monitor) {
    ObjectModel.genericLock(monitor);
    if (Dr.STATS) countMonitorOpTypes(monitor);
    if (RVMThread.getCurrentThread().isDrThread() && !lockIsRecursive(monitor)) {
      monitorenter(monitor);
    }
  }
  
  @Entrypoint
  @Unpreemptible
  public static void genericUnlock(final Object monitor) {
    if (Dr.STATS) countMonitorOpTypes(monitor);
    if (RVMThread.getCurrentThread().isDrThread() && !lockIsRecursive(monitor)) {
      monitorexit(monitor);
      // Respond before release...
      if (Dr.COMMUNICATION) respond();
    }
    ObjectModel.genericUnlock(monitor);
  }
  
  @Inline
  @Entrypoint
  @Unpreemptible
  public static void inlineLock(final Object monitor, final Offset lockOffset) {
    ThinLock.inlineLock(monitor, lockOffset);
    if (Dr.STATS) countMonitorOpTypes(monitor);
    if (RVMThread.getCurrentThread().isDrThread() && !lockIsRecursive(monitor)) {
      monitorenter(monitor);
    }
  }
  @Inline
  @Entrypoint
  @Unpreemptible
  public static void inlineUnlock(final Object monitor, final Offset lockOffset) {
    if (Dr.STATS) countMonitorOpTypes(monitor);
    if (RVMThread.getCurrentThread().isDrThread() && !lockIsRecursive(monitor)) {
      monitorexit(monitor);
      // Respond before release...
      if (Dr.COMMUNICATION) respond();
    }
    ThinLock.inlineUnlock(monitor, lockOffset);
  }
  
  @Unpreemptible
  public static void prewait(final RVMThread t, final Object monitor) {
    if (VM.VerifyAssertions) VM._assert(t.isDrThread());
    if (t.isDrThread()) {
      monitorexit(monitor);
    }
  }
  
  @Unpreemptible
  public static void postwait(final RVMThread t, final Object monitor) {
    if (VM.VerifyAssertions) VM._assert(t.isDrThread());
    if (t.isDrThread()) {
      monitorenter(monitor);
    }
  }
  
  // Volatile events
  static {
    if (VM.VerifyAssertions) {
      VM._assert(!Barriers.NEEDS_OBJECT_GETFIELD_BARRIER);
      VM._assert(!Barriers.NEEDS_OBJECT_GETSTATIC_BARRIER);
      VM._assert(!Barriers.NEEDS_OBJECT_PUTSTATIC_BARRIER);
    }
  }
  private static final WordArray VOL_LOCKED = WordArray.create(0);
  
  /**
   * 
   * @param object
   * @param fieldOffset
   * @param size
   */
  @Entrypoint
  @Unpreemptible
  private static void volatileReadResolved(final Object object, final Offset vcOffset) {
    if (PRINT) {
      DrDebug.lock();
      DrDebug.twrite();
      VM.sysWrite("v read resolved ", ObjectReference.fromObject(object));
      VM.sysWriteln(" + ", vcOffset);
      DrDebug.unlock();
    }
    if (VM.VerifyAssertions && Dr.COMMUNICATION) FibComm.assertNotBlocked();
    if (VM.VerifyAssertions) {
      VM._assert(Dr.VOLATILES);
      VM._assert(RVMThread.getCurrentThread().drActiveVolatileVC == null);
    }
    final WordArray vc = acquireVolatileVC(object, vcOffset);
    if (vc == null) {
      if (PRINT) DrDebug.twriteln("v read resolved found null");
      return;
    } else {
      if (PRINT) DrDebug.twriteln("v read resolved found vc");
      VC.advanceTo(RVMThread.getCurrentThread().drThreadVC, vc);      
      RVMThread.getCurrentThread().drActiveVolatileVC = vc;
    }
  }
  /**
   * 
   * @param object
   * @param fieldOffset
   * @param size
   */
  @Entrypoint
  @Unpreemptible
  private static void volatileStaticReadResolved(final int classID, final Offset vcOffset) {
    if (VM.VerifyAssertions && Dr.COMMUNICATION) FibComm.assertNotBlocked();
    observeClassInit(classID);
    final WordArray vc = acquireVolatileVC(null, vcOffset);
      if (vc == null) {
        return;
      } else {
        VC.advanceTo(RVMThread.getCurrentThread().drThreadVC, vc);      
        RVMThread.getCurrentThread().drActiveVolatileVC = vc;
      }
  }

  @Entrypoint
  @Unpreemptible
  private static void postVolatileReadResolved(final Object object, final Offset vcOffset) {
    if (PRINT) DrDebug.twriteln("post v read resolved");
    if (VM.VerifyAssertions && Dr.COMMUNICATION) FibComm.assertNotBlocked();
    if (VM.VerifyAssertions) {
      VM._assert(Dr.VOLATILES);
    }
    final WordArray vc = RVMThread.getCurrentThread().drActiveVolatileVC;
    if (PRINT) {
      DrDebug.lock();
      DrDebug.twrite();
      VM.sysWrite("post volatile r vc ", ObjectReference.fromObject(vc));
      VM.sysWrite(" in ", ObjectReference.fromObject(object));
      VM.sysWriteln(" + ", vcOffset);
      DrDebug.unlock();
    }
    releaseVolatileVC(object, vcOffset, vc);
    if (vc != null) {
      if (PRINT) DrDebug.twriteln("post v read resolved  found vc");
      RVMThread.getCurrentThread().drActiveVolatileVC = null;
    } else {
      if (PRINT) DrDebug.twriteln("post v read resolved  found null");
    }
    if (Dr.STATS) DrStats.volatileRead.inc();
  }
  @Entrypoint
  @Unpreemptible
  private static void postReadUnresolved(final Object object, final int fid) {
    if (PRINT) DrDebug.twriteln("post read unresolved");
    if (VM.VerifyAssertions && Dr.COMMUNICATION) FibComm.assertNotBlocked();
    final RVMField field = FieldReference.getMemberRef(fid).asFieldReference().getResolvedField();
    if (FieldTreatment.vol(field)) {
      if (PRINT) DrDebug.twriteln("post read unresolved v");
      postVolatileReadResolved(object, field.getDrOffset());
    }
//    else {
//      if (PRINT) DrDebug.twriteln("post read unresolved non-v");
//      // class-init --hb--> static final field read.
//      if (FieldTreatment.staticFinal(field)
//          && !field.getDeclaringClass().fibThreadHasObservedInit()) {
//        Magic.writeFloor();
//        final WordArray vc = field.getDeclaringClass().drGetClassInitVC();
//        if (vc != null) {
//          VC.advanceTo(RVMThread.getCurrentThread().drThreadVC, vc);
//        }
//        Magic.readCeiling();
//      }
//    }
  }
  
  @Entrypoint
  @Unpreemptible
  private static void volatileWriteResolved(final Object object, final Offset vcOffset) {
    if (PRINT) {
      DrDebug.lock();
      DrDebug.twrite();
      VM.sysWrite("v write resolved ", ObjectReference.fromObject(object));
      VM.sysWriteln(" + ", vcOffset);
      DrDebug.unlock();
    }
    if (VM.VerifyAssertions && Dr.COMMUNICATION) FibComm.assertNotBlocked();
    if (VM.VerifyAssertions) {
      VM._assert(Dr.VOLATILES);
      VM._assert(RVMThread.getCurrentThread().drActiveVolatileVC == null);
    }
    WordArray vc = acquireVolatileVC(object, vcOffset);    
    if (vc == null) {
      if (PRINT) DrDebug.twriteln("v write resolved found null");
      // First write -- allocate VC as copy of thread's.
      vc = VC.copy(RVMThread.getCurrentThread().drThreadVC);
      if (Dr.STATS) DrStats.volVCs.inc();
    } else {
      if (PRINT) DrDebug.twriteln("v write resolved found vc");
      // Merge from thread vc.
      VC.advanceTo(vc, RVMThread.getCurrentThread().drThreadVC);
    }
    RVMThread.getCurrentThread().drActiveVolatileVC = vc;
  }
  @Entrypoint
  @Unpreemptible
  private static void postVolatileWriteResolved(final Object object, final Offset vcOffset) {
    if (PRINT) {
      DrDebug.lock();
      DrDebug.twrite();
      VM.sysWriteln("post v write resolved ", ObjectReference.fromObject(object).toAddress(), " + ", vcOffset.toWord().toAddress());
      DrDebug.unlock();
    }
    if (VM.VerifyAssertions && Dr.COMMUNICATION) FibComm.assertNotBlocked();
    if (VM.VerifyAssertions) {
      VM._assert(Dr.VOLATILES);
    }
    final WordArray vc = RVMThread.getCurrentThread().drActiveVolatileVC;
    RVMThread.getCurrentThread().drActiveVolatileVC = null;
    if (VM.VerifyAssertions) VM._assert(vc != null);
    if (PRINT) {
      DrDebug.lock();
      DrDebug.twrite();
      VM.sysWrite("post volatile w vc ", ObjectReference.fromObject(vc));
      VM.sysWrite(" in ", ObjectReference.fromObject(object));
      VM.sysWriteln(" + ", vcOffset);
      DrDebug.unlock();
    }
    releaseVolatileVC(object, vcOffset, vc);
    VC.set(RVMThread.getCurrentThread().drThreadVC, RVMThread.getCurrentThread().incDrEpoch());
    if (Dr.STATS) DrStats.volatileWrite.inc();
  }
  @Entrypoint
  @Unpreemptible
  private static void postWriteUnresolved(final Object object, final int fid) {
    if (PRINT) DrDebug.twriteln("post write unresolved");
    if (VM.VerifyAssertions && Dr.COMMUNICATION) FibComm.assertNotBlocked();
    final RVMField field = FieldReference.getMemberRef(fid).asFieldReference().getResolvedField();
    if (FieldTreatment.vol(field)) {
      if (PRINT) DrDebug.twriteln("post write unresolved v");
      postVolatileWriteResolved(object, field.getDrOffset());
    } else {
      if (PRINT) DrDebug.twriteln("post write unresolved non-v");
    }
  }
  
  private static WordArray acquireVolatileVC(final Object object, final Offset vcOffset) {
    while (true) {
      WordArray vc = (WordArray)ObjectReference.fromObject(object)
          .toAddress().prepareObjectReference(vcOffset).toObject();
      if (vc != VOL_LOCKED
          && ObjectReference.fromObject(object).toAddress().attempt(
              ObjectReference.fromObject(vc),
              ObjectReference.fromObject(VOL_LOCKED), vcOffset)) {
        Magic.readCeiling(); // don't allow vol read above lock.
        return vc;
      } else {
        Magic.pause();        
      }
    }
  }

  private static void releaseVolatileVC(final Object object, final Offset vcOffset,
      final WordArray vc) {
    if (VM.VerifyAssertions) {
      ObjectReference oldVC = ObjectReference.fromObject(object)
          .toAddress().prepareObjectReference(vcOffset);
      if (oldVC.toObject() != VOL_LOCKED) {
        DrDebug.lock();
        DrDebug.twrite();
        VM.sysWrite("setVolatileVC(...) expected VOL_LOCKED (",
            ObjectReference.fromObject(VOL_LOCKED));
        VM.sysWriteln(") but found ", oldVC);
        DrDebug.unlock();
        VM.sysFail("shucks");
      }
    }
    Magic.writeFloor();
    if (Barriers.NEEDS_OBJECT_PUTFIELD_BARRIER && object != null) {
      Barriers.objectFieldWrite(object, ObjectReference.fromObject(vc).toObject(), vcOffset, 0);
    } else {
      ObjectReference.fromObject(object).toAddress().store(ObjectReference.fromObject(vc), vcOffset);
    }
  }
  
  private static int maxSingleThreadedClassInit = 0;

  @Inline
  @Interruptible
  public static void classInit(RVMClass c) {
    if (VM.VerifyAssertions && Dr.COMMUNICATION) FibComm.assertNotBlocked();
    if (Dr.SYNC && VM.runningVM && c.hasStaticFields()) {
      final WordArray threadVC = RVMThread.getCurrentThread().drThreadVC;
      if (Dr.STATS && threadVC != null) DrStats.classInitVCs.inc();
      c.drSetClassInitVC(threadVC == null ? VC.ORIGIN : VC.copy(threadVC));
      RVMThread.getCurrentThread().drClassInitsObserved.set(c.getId());
      if (DrRuntime.maxLiveDrThreads() <= 1 && maxSingleThreadedClassInit < c.getId()) {
        maxSingleThreadedClassInit = c.getId();
      }
    }
  }
  
  @Uninterruptible
  public static boolean initHappensBeforeAll(RVMClass c) {
    // If class has not been initialized, could happen at arbitrary later time.
    if (!c.isInitialized()) return false;

    // Class IS INITIALIZED.
    
    // If not yet running VM, initialzed fields will all be visible before
    // this code is used.
    if (!VM.runningVM) return true;
    
    final int nFibThreads = DrRuntime.maxLiveDrThreads();
    // If there are no FIB threads yet, then all will be forked at a time after class init.  (?)
    if (nFibThreads == 0) return true;
    // If there is one FIB thread and this is it, then since we are compiling this class
    // now, we or one of our ancestors initialized it, so init happens-before now, which
    // happens-before all future threads by fork.
    if (RVMThread.getCurrentThread().isDrThread() && nFibThreads == 1) return true;

    final WordArray classVC = c.drGetClassInitVC();
    // If no vc after init, there will be nothing to do.
    if (classVC == null) return true;
    
    // For now, skip the finer grain ordering checks and just deny knowledge of ordering.
    // This is conservative in what checks are inserted.  It may insert checks that we
    // could prove to be unnecessary.  It does not miss data races or report false data
    // races, since those checks will still execute at run time.
    return false;
    
//    // Full version
//    // For each thread other than this one...
//    final int thisTid = RVMThread.getCurrentThread().isDrThread() ? RVMThread.getCurrentThread().getDrID() : -1;
//    for (int tid = 0; tid < DrRuntime.maxLiveDrThreads(); tid++) {
//      if (tid == thisTid) continue;
//      final RVMThread t = DrRuntime.getDrThread(tid);
//      // If the thread's current VC does not happen after this class's init,
//      // then tracking is needed in at least that thread.
//      if (t.isAlive() && !VC.hb(classVC, t.drThreadVC)) {
//        return false;
//      }
//    }
//    return true;
  }



  
  /**
   * Called before this thread may block.
   */
  @Entrypoint
  @Inline
  public static void block() {
    if (Dr.COMMUNICATION) {
      if (RVMThread.getCurrentThread().isDrThread()) {
        if (VM.VerifyAssertions) {
//          DrDebug.lock();
//          DrDebug.twriteln("blocking in");
//          RVMThread.dumpStack();
//          DrDebug.unlock();
          if (!RVMThread.getCurrentThread().enterDR()) {
//            DrDebug.lock();
//            DrDebug.twrite(); VM.sysWriteln(" reentrant block");
//            RVMThread.dumpStack();
//            DrDebug.unlock();
          }
        }
        RVMThread.getCurrentThread().drFibComm.block();
        if (VM.VerifyAssertions) {
          RVMThread.getCurrentThread().exitDR();
        }
      }
    }
  }
  /**
   * Called after this thread continues after potentially blocking.
   */
  @Entrypoint
  @Inline
  public static void unblock() {
    unblock(false);
  }
  @Inline
  public static void unblock(boolean matchBlockSource) {
    if (Dr.COMMUNICATION) {
      if (RVMThread.getCurrentThread().isDrThread()) {
        if (VM.VerifyAssertions) {
          if (!RVMThread.getCurrentThread().enterDR()) {
//            DrDebug.lock();
//            DrDebug.twrite(); VM.sysWriteln(" reentrant unblock");
//            RVMThread.dumpStack();
//            DrDebug.unlock();
          }
        }
        RVMThread.getCurrentThread().drFibComm.unblock(matchBlockSource);
        if (VM.VerifyAssertions) {
          RVMThread.getCurrentThread().exitDR();
//          DrDebug.lock();
//          DrDebug.twriteln("UNblocked in");
//          RVMThread.dumpStack();
//          DrDebug.unlock();
        }
      }
    }
  }
  
  @Inline
  @Unpreemptible
  public static void respond() {
    if (Dr.COMMUNICATION) {
      RVMThread.getCurrentThread().drFibComm.respond();
    }
  }
  
  
  // GC events
    
  public static void prepareGlobal() {
    Dr.readers().prepareGlobalGC();
  }

  public static void releaseGlobal() {
    Dr.readers().releaseGlobalGC();
  }

  public static void preparePerThread(MutatorContext mc) {
    if (VM.VerifyAssertions) RVMThread.getCurrentThread().drGcCount++;
    RVMThread t = (RVMThread)mc;
    if (t.isDrThread()) {
      Dr.readers().preparePerThreadGC(t);
    }
  }

  public static void releasePerThread(MutatorContext mc) {
    RVMThread t = (RVMThread)mc;
    if (t.isDrThread()) {
      Dr.readers().releasePerThreadGC(t);
    }
    if (VM.VerifyAssertions) RVMThread.getCurrentThread().drGcCount++;
  }
  
  
  
  public static long currentGcCount() {
    return RVMThread.getCurrentThread().drGcCount;
  }
  public static void stashGcCount() {
    RVMThread.getCurrentThread().drGcCountStash = RVMThread.getCurrentThread().drGcCount;
  }
  public static void checkGcCount() {
    VM._assert(RVMThread.getCurrentThread().drGcCountStash == RVMThread.getCurrentThread().drGcCount);
  }

}
