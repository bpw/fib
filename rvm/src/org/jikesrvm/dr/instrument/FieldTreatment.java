package org.jikesrvm.dr.instrument;

import org.jikesrvm.classloader.FieldReference;
import org.jikesrvm.classloader.RVMField;
import org.jikesrvm.dr.Dr;
import org.jikesrvm.octet.Octet;
import org.vmmagic.pragma.Uninterruptible;

/**
 * Instrumentation decisions about how to treat fields.
 * 
 * TODO:
 * Use Octet/FIB for ownership of volatile metadata too instead of CASing?
 * 
 *
 *
 */
public enum FieldTreatment {

  /** No instrumentation */
  NONE,
  /** Instrumentation for class-init --> static final read sync. */
  STATIC_FINAL,
  /** Instrumentation for volatile access synchronization */
  VOLATILE,
  /** Instrumentation for race checks */
  CHECK;
  
  /**
   * Should this field be checked for races?
   * @param f
   * @return
   */
  @Uninterruptible
  public static boolean check(RVMField f) {
    return Dr.CHECKS
        && Octet.shouldInstrumentFieldAccess(f.getMemberRef().asFieldReference())
        && Octet.shouldInstrumentClass(f.getDeclaringClass().getTypeRef())
        && !f.isFinal()
        && !f.isVolatile()
        && (Dr.CHECK_STATICS || !f.isStatic());
    // FIXME allow escape analysis interposition, etc.
  }
  @Uninterruptible
  public static boolean maybeCheckOrSync(FieldReference f) {
    // NOTE: MemberReference.getType() returns type *containing* the member, not the type of the member.
    return (Dr.CHECKS || Dr.VOLATILES)
        && Octet.shouldInstrumentFieldAccess(f)
        && Octet.shouldInstrumentClass(f.getType());
    // FIXME allow escape analysis interposition, etc.
  }
  /**
   * Should this field be treated as a volatile synchronization device?
   * @param f
   * @return
   */
  @Uninterruptible
  public static boolean vol(RVMField f) {
    return Dr.VOLATILES
        && Octet.shouldInstrumentFieldAccess(f.getMemberRef().asFieldReference())
        && Octet.shouldInstrumentClass(f.getDeclaringClass().getTypeRef())
        && f.isVolatile();
  }
  /**
   * Should this field be treated as sync with class init?
   * @param f
   * @return
   */
  @Uninterruptible
  public static boolean staticFinal(RVMField f) {
    return Dr.SYNC
        && Octet.shouldInstrumentFieldAccess(f.getMemberRef().asFieldReference())
        && Octet.shouldInstrumentClass(f.getDeclaringClass().getTypeRef())
        && f.isFinal()
        && f.isStatic();
  }
  
  /**
   * Should this field be ignored?
   * @param f
   * @return
   */
  @Uninterruptible
  public static boolean none(RVMField f) {
    return !Dr.ON || (f.isFinal() && !f.isStatic())
        || (Octet.shouldInstrumentFieldAccess(f.getMemberRef().asFieldReference())
            && Octet.shouldInstrumentClass(f.getDeclaringClass().getTypeRef())
            && !check(f)
            && !vol(f)
            && staticFinal(f));
  }

  /**
   * How should this field be treated?
   * @param f
   * @return
   */
  @Uninterruptible
  public static FieldTreatment treatment(RVMField f) {
    // FIXME allow escape analysis interposition, etc.
    if (!Dr.ON || (f.isFinal() && !f.isStatic())
        || !Octet.shouldInstrumentFieldAccess(f.getMemberRef().asFieldReference())
        || !Octet.shouldInstrumentClass(f.getDeclaringClass().getTypeRef())) {
      return NONE;
    }
    // Below here, class should be instrumented.
    if (f.isFinal() && f.isStatic()) return Dr.SYNC ? STATIC_FINAL : NONE;
    if (f.isVolatile()) return Dr.VOLATILES ? VOLATILE : NONE;
    return Dr.CHECKS && (Dr.CHECK_STATICS || !f.isStatic()) ? CHECK : NONE;
  }
  
//  @Uninterruptible
//  public static boolean instrument(TypeReference typeRef) {
//    return Octet.shouldInstrumentClass(typeRef);
//  }
//  @Uninterruptible
//  public static boolean instrument(NormalMethod method) {
//    return Fib.ON && Octet.shouldInstrumentMethod(method); // && method.getResolvedContext() != Context.VM_CONTEXT;
//  }
  
}
