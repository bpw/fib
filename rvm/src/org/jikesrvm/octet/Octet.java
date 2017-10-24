// Octet: centralized location for various settings and functionality
// Octet: TODO: Should we remove trailing spaces in our source (according to http://jikesrvm.org/Coding+Style)?

package org.jikesrvm.octet;

import org.jikesrvm.Constants;
import org.jikesrvm.VM;
import org.jikesrvm.classloader.Atom;
import org.jikesrvm.classloader.Context;
import org.jikesrvm.classloader.FieldReference;
import org.jikesrvm.classloader.NormalMethod;
import org.jikesrvm.classloader.RVMClass;
import org.jikesrvm.classloader.RVMMethod;
import org.jikesrvm.classloader.TypeReference;
import org.jikesrvm.config.BaseConfig;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public final class Octet implements Constants {
  
  /** Get the actual build configuration */
  @Inline @Pure
  public static final BaseConfig getConfig() {
    return VM.octetConfig;
  }
  
  private static final ClientAnalysis clientAnalysis = getConfig().constructClientAnalysis();
  
  @Inline @Pure
  public static final ClientAnalysis getClientAnalysis() {
    return clientAnalysis;
  }
  
  /** How much debugging info to print? */
  public static final int verbosity = VM.VerifyAssertions ? 2 : 1;

  // Optimizations or lack thereof
  
  @Inline
  public static final boolean useEscapeAnalysis() {
    // Octet: LATER: Jikes escape analysis seems to very broken
    return false;
    //return !VM.octetNoEscapeAnalysis;
  }

  // Debugging and tuning
  
  /** When waiting, how many spin loops to do before switching to pthread yield? */
  @Inline static final int waitSpinCount() { return VM.octetWaitSpinCount; }
  
  /** When waiting, how many yields to do before switching to a pthread condition wait? */
  @Inline static final int waitYieldCount() { return VM.octetWaitYieldCount; }
  
  // Decisions about which code, objects, and fields to instrument
  
  /** Is the site a racy site based on static race detection? */
  @Interruptible @Inline
  public static final boolean shouldInstrumentCheckRace(RVMMethod method, int bci) {
    // Octet: TODO: Most libraries are instrumented during bootimage build time. However, we don't check for racy accesses
    // during bootimage build, the racy accesses are populated in the hash set later. This is done so that the hash 
    // set containing racy sites can grow. An alternative would be to allow the hash set to grow even when the VM is not
    // running. Moreover, we are also not absolutely sure about the thoroughness of Chord's output with all library 
    // packages. Hence we conservatively instrument all library classes when the VM is not yet running.
    if (!VM.runningVM) {
      return true;
    }
    return Site.isRegisteredRacySite(method.getDeclaringClass().getDescriptor(), method.getName(), method.getDescriptor(), bci, 0, null);
  }

  /** Instrument a method? */
  @Inline @Pure
  public static final boolean shouldInstrumentMethod(RVMMethod method) {
    return method.getStaticContext() == Context.APP_CONTEXT &&
           // Octet: LATER: it doesn't really make sense to do static cloning of the libraries if getConfig().instrumentLibraries() == false
           (getConfig().instrumentLibraries() || !Context.isLibraryPrefix(method.getDeclaringClass().getTypeRef())) &&
           !isExcludedPrefix(method) &&
           getClientAnalysis().isSelectedInstrMethod((NormalMethod)method);
  }

  public static boolean isExcludedPrefix(RVMMethod method) {
    return method.getDeclaringClass().getDescriptor().isPrefix(EXCLUDED_PREFIXES);
  }

  /** Instrument a class? */
  @Inline @Pure
  public static final boolean shouldInstrumentClass(TypeReference tRef) {
    return (getConfig().instrumentLibraries() || !Context.isLibraryPrefix(tRef)) &&
           !Context.isVMPrefix(tRef) &&
           !tRef.getName().isPrefix(EXCLUDED_PREFIXES);
  }

  public static boolean isExcludedPrefix(RVMClass clas) {
    return clas.getDescriptor().isPrefix(EXCLUDED_PREFIXES);
  }


  /** Instrument an access to a field? */
  @Inline @Pure
  public static final boolean shouldInstrumentFieldAccess(FieldReference fieldRef) {
    TypeReference typeRef = fieldRef.getType();
    return (Context.isApplicationPrefix(typeRef) ||
            // Octet: TODO: Even if library instrumentation is disabled, should we instrument accesses to library objects?
            (Context.isLibraryPrefix(typeRef) && getConfig().instrumentLibraries())) &&
            !typeRef.getName().isPrefix(EXCLUDED_PREFIXES);
  }

  /** Add metadata for a field? Applies to static fields only. */
  @Inline @Pure
  public static final boolean shouldAddMetadataForField(FieldReference fieldRef) {
    return shouldInstrumentFieldAccess(fieldRef);
  }

  private static final byte[][] EXCLUDED_PREFIXES = {
    // Instances of the following types are immutable (so are some others, but according to the statistics for DaCapo, the others aren't a big problem)
    // Octet: TODO: However, it's still possible that there could be methods (e.g., static methods) that access shared, mutable data.
    // Need to check more carefully.  It's really that just these classes' data should be considered immutable.
    // For example, some String methods read from a StringBuffer, which may need to trigger an Octet slow path.
    // So this needs to be fixed.
    "Ljava/lang/String".getBytes(),
    "Ljava/lang/Integer".getBytes(),
    "Ljava/lang/Character".getBytes(),
//    
//    "Lgnu/classpath/Jikes".getBytes(), // FIB: added due to reentrancy issues
//    "Lgnu/classpath/VM".getBytes(), // FIB: added due to reentrancy issues
//    "Ljava/lang/Jikes".getBytes(), // FIB: added due to reentrancy issues
////    "Ljava/lang/ThreadLocal".getBytes(), // FIB: added due to reentrancy issues
//    "Ljava/lang/VM".getBytes(), // FIB: added due to reentrancy issues
//    "Ljava/lang/reflect/Jikes".getBytes(), // FIB: added due to reentrancy issues
//    "Ljava/lang/reflect/VM".getBytes(), // FIB: added due to reentrancy issues
//    "Ljava/io/VM".getBytes(), // FIB: added due to reentrancy issues
//    "Ljava/nio/Jikes".getBytes(), // FIB: added due to reentrancy issues
//    "Ljava/nio/VM".getBytes(), // FIB: added due to reentrancy issues
//    "Ljava/security/VM".getBytes(), // FIB: added due to reentrancy issues
//    "Ljava/util/Properties".getBytes(), // FIB: added due to reentrancy issues
//    "Ljava/util/VM".getBytes(), // FIB: added due to reentrancy issues
  };
  
//  static {
//    if (VM.VerifyAssertions) {
//      VM._assert(Atom.findOrCreateUnicodeAtom("Ljava/util/Properties;").isPrefix(EXCLUDED_PREFIXES));
//    }
//  }

  /** Tolerate exceptions inside barriers, which are uninterruptible (and would normally cause the VM to fail). */
  @Inline
  public static final boolean tolerateExceptionsInUninterruptibleCode() {
    return getConfig().insertBarriers();
  }
  
  /** Boot-time activities. */
  @Interruptible
  public static final void boot() {
    if (Octet.getConfig().enableStaticRaceDetection()) {
      LoadDataRaces.readRaces();
    }
    Octet.getClientAnalysis().boot();
  }

}
