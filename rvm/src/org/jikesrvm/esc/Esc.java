// centralized location for various settings and functionality

package org.jikesrvm.esc;

import org.jikesrvm.Constants;
import org.jikesrvm.VM;
import org.jikesrvm.classloader.Context;
import org.jikesrvm.classloader.FieldReference;
import org.jikesrvm.classloader.NormalMethod;
import org.jikesrvm.classloader.RVMField;
import org.jikesrvm.classloader.RVMMethod;
import org.jikesrvm.classloader.TypeReference;
import org.jikesrvm.config.BaseConfig;
import org.jikesrvm.octet.Octet;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.pragma.Pure;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public final class Esc implements Constants {
  
  // Man: Esc.getConfig() is equivalent to Octet.getConfig(), it's just an alias.
  /** Get the actual build configuration */
  @Inline @Pure
  public static final BaseConfig getConfig() {
    return VM.octetConfig;
  }
  
  /** How much debugging info to print? */
  public static final int verbosity = VM.VerifyAssertions ? 2 : 1;

  // Decisions about which code, objects, and fields to instrument

  /** Instrument a method? */
  @Inline @Pure
  public static final boolean shouldInstrumentMethod(RVMMethod method) {
    return (getConfig().escapeInstrumentVM() || (method.getStaticContext() == Context.APP_CONTEXT)) &&
           // Octet: LATER: it doesn't really make sense to do static cloning of the libraries if getConfig().instrumentLibraries() == false
           (getConfig().instrumentLibraries() || !Context.isLibraryPrefix(method.getDeclaringClass().getTypeRef())) &&
           !method.getDeclaringClass().getDescriptor().isPrefix(EXCLUDED_PREFIXES);
  }

  /** Instrument a class? */
  @Inline @Pure
  public static final boolean shouldInstrumentClass(TypeReference tRef) {
    return (getConfig().instrumentLibraries() || !Context.isLibraryPrefix(tRef)) &&
           (getConfig().escapeInstrumentVM() || !Context.isVMPrefix(tRef)) &&
           !tRef.getName().isPrefix(EXCLUDED_PREFIXES);
  }

  /** Instrument an access to a field? */
  @Inline @Pure
  public static final boolean shouldInstrumentFieldAccess(FieldReference fieldRef) {
    TypeReference typeRef = fieldRef.getType();
    RVMField field = fieldRef.getResolvedField();
    return (getConfig().escapeInstrumentVM() || !Context.isVMPrefix(typeRef)) &&
           (getConfig().instrumentLibraries() || !Context.isLibraryPrefix(typeRef)) &&
           // Escape: Reference stores for those immutable objects should not be instrumented
           !typeRef.getName().isPrefix(EXCLUDED_PREFIXES) &&
           // Escape: Enumeration types are not instrumented, they are immutable
           (field == null || !field.isEnumConstant());
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
  };

  /** Tolerate exceptions inside barriers, which are uninterruptible (and would normally cause the VM to fail). */
//  @Inline
//  public static final boolean tolerateExceptionsInUninterruptibleCode() {
//    return getConfig().escapeInsertBarriers();
//  }
  
  @Inline @Pure
  public static final boolean shouldTraceObject(TypeReference tRef) {
    // Man: As Mike pointed out, we should not use this method during tracing at runtime. It's probably more expensive than just trace that field. 
    return !tRef.getName().isPrefix(EXCLUDED_PREFIXES) &&
        (getConfig().instrumentLibraries() || !Context.isLibraryPrefix(tRef)) &&
        !Context.isVMPrefix(tRef);
  }
  
  private static final EscapeClient escapeClient = Octet.getConfig().constructEscapeClient();
  @Inline @Pure
  public static final EscapeClient getEscapeClient() {
    return escapeClient;
  }
}
