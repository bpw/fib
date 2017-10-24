package org.jikesrvm.octet;

import java.io.PrintStream;

import org.jikesrvm.VM;
import org.jikesrvm.classloader.Atom;
import org.jikesrvm.classloader.Context;
import org.jikesrvm.classloader.NormalMethod;
import org.jikesrvm.classloader.RVMMethod;
import org.jikesrvm.compilers.opt.inlining.InlineSequence;
import org.jikesrvm.compilers.opt.ir.Instruction;
import org.jikesrvm.util.HashMapRVM;
import org.jikesrvm.util.HashSetRVM;
import org.vmmagic.pragma.Interruptible;
import org.vmmagic.pragma.Uninterruptible;

/** Octet: represents a static program location */
@Uninterruptible
public final class Site {

  // The key information for a site
  private final Atom className;
  private final Atom methodName;
  private final Atom methodDesc;
  private final int bci;
  private final Site caller; // caller call site (for inlined call sites)

  // Extra information
  private final int line;

  // global variables
  // Octet: LATER: avoid hardcoding this value and making it public
  public static final int MAX_SITES = 1 << 18; // 256K sites
  private static final Site[] sites; // this is the global we'll synchronize on
  private static final HashMapRVM<Site, Integer> siteMap; // only needed if sites are supposed to be unique
  private static int numSites;
  static HashSetRVM<Site> raceSites; // datarace static sites given by Chord

  static {
    if (Octet.getClientAnalysis().needsSites()) {
      sites = new Site[MAX_SITES];
      if (Octet.getClientAnalysis().needsUniqueSites()) {
        siteMap = new HashMapRVM<Site, Integer>(MAX_SITES);
      } else {
        siteMap = null;
      }
    } else {
      sites = null;
      siteMap = null;
    }
    raceSites = null; // Don't allocate now in the bootimage, allocate it only after the VM is running
  }

  @Interruptible
  public static int getSite(Instruction inst) {
    return getSite(inst.position, inst.bcIndex);
  }

  @Interruptible
  public static int getSite(InlineSequence position, int bci) {
    return getSite(position, bci, false);
  }

  @Interruptible
  public static int getSite(InlineSequence position, int bci, boolean ignoreContext) {
    Site caller = null;
    if (position.getCaller() != null &&
        position.getCaller().getMethod().getStaticContext() == Context.APP_CONTEXT) {
      caller = Site.lookupSite(Site.getSite(position.getCaller(), position.bcIndex, ignoreContext));
    }
    return getSite(position.getMethod(), bci, caller, ignoreContext);
  }
  
  @Interruptible
  public static int getSite(RVMMethod method, int bci, Site caller) {
    return getSite(method, bci, caller, false);
  }

  // Optionally allow sites to be VM sites
  @Interruptible
  public static int getSite(RVMMethod method, int bci, Site caller, boolean ignoreContext) {
    if (VM.VerifyAssertions && !ignoreContext) { VM._assert(method.getStaticContext() == Context.APP_CONTEXT); }
    // Site will include some uninstrumented methods that still have application context (like String methods), so the next assertion would fail.
    //if (VM.VerifyAssertions && !ignoreContext) { VM._assert(Octet.shouldInstrumentMethod(method)); }

    // This type cast should be okay 
    int line = ((NormalMethod)method).getLineNumberForBCIndex(bci);
    return getSite(method.getDeclaringClass().getDescriptor(), method.getName(), method.getDescriptor(), bci, line, caller);
  }

  @Interruptible
  public static int getSite(Atom classDesc, Atom methodDesc, Atom signature, int bci, int line, Site caller) {
    Site site = new Site(classDesc, methodDesc, signature, bci, line, caller);
    return registerSite(site);
  }
  
  @Interruptible
  public static void registerRaceSite(Atom className, Atom methodName, Atom methodDesc, int bci, int line, Site caller) {
    Site site = new Site(className, methodName, methodDesc, bci, line, caller);
    registerRace(site);
  }
  
  @Interruptible
  public static void registerRace(Site site) {      
    if (raceSites != null) {           
      raceSites.add(site);            
    }
  }
  
  @Interruptible
  public static boolean isRegisteredRacySite(Atom className, Atom methodName, Atom methodDesc, int bci, int line, Site caller) {      
    Site site = new Site(className, methodName, methodDesc, bci, line, caller);
    if (VM.VerifyAssertions) { VM._assert(raceSites != null); }   
    return raceSites.contains(site);            
  }

  @Interruptible
  private static int registerSite(Site site) {
    if (VM.VerifyAssertions) { VM._assert(sites != null); }
    synchronized (sites) {
      Integer intObj = null;
      if (siteMap != null) {
        intObj = siteMap.get(site);
      }
      int index;
      if (intObj == null) {
        if (VM.VerifyAssertions) {
          if (numSites == MAX_SITES) { VM.sysFail("Octet site array is full"); }
        }
        index = numSites++;
        if (siteMap != null) {
          siteMap.put(site, index);
        }
        sites[index] = site;
        Octet.getClientAnalysis().handleNewSite(site);
      } else {
        index = intObj.intValue();
      }
      return index;
    }
  }
  
  private Site(Atom className, Atom methodName, Atom methodDesc, int bci, int line, Site caller) {
    this.className = className;
    this.methodName = methodName;
    this.methodDesc = methodDesc;
    this.bci = bci;
    this.line = line;
    this.caller = caller;
  }
  
  public static final Site lookupSite(int siteID) {
    if (VM.VerifyAssertions) { VM._assert(Octet.getClientAnalysis().needsSites()); }
    return sites[siteID];
  }
  
  public Site getCaller() {
    return caller;
  }
  
  public int getLine() {
    return line;
  }

  /** Check whether a method and bci site match this site -- not include a possible caller. */
  public boolean matchesExcludingCaller(RVMMethod method, int bci) {
    return this.className == method.getDeclaringClass().getDescriptor() &&
           this.methodName == method.getName() &&
           this.methodDesc == method.getDescriptor() &&
           this.bci == bci;
  }

  // equals and hashCode are really important -- their absence created a subtle bug!
  // Note that we are not using line in equals, since it is not part of the unique definition of a site.
  @Interruptible
  @Override
  public boolean equals(Object o) {
    Site otherSite = (Site)o;
    return this.className == otherSite.className &&
           this.methodName == otherSite.methodName &&
           this.methodDesc == otherSite.methodDesc &&
           this.bci == otherSite.bci &&
           (this.caller == otherSite.caller ||
            (this.caller != null && otherSite.caller != null && this.caller.equals(otherSite.caller)));
  }
  
  @Interruptible
  @Override
  public int hashCode() {
    return className.hashCode() + methodName.hashCode() + methodDesc.hashCode() + bci +
           (caller == null ? 0 : caller.hashCode());
  }
  
  @Interruptible
  @Override
  public String toString() {
    String s = className + "." + methodName + " " + methodDesc + ":" + line + "(" + bci + ")";
    if (caller != null) {
        s += " called by " + caller;
    }
    return s;
  }

  /** Assumes siteMap != null, i.e., unique sites */
  @Interruptible
  public static void printAllSitesMultipleLines(PrintStream ps) {
    synchronized (sites) {
      for (Site site : sites) {
        if (site == null) {
          break;
        }
        ps.println(site.className);
        ps.println(site.methodName);
        ps.println(site.methodDesc);
        ps.println(site.bci);
        ps.println(site.line);
        if (VM.VerifyAssertions) { VM._assert(siteMap != null); }
        int callerID = -1;
        if (site.caller != null) {
          callerID = siteMap.get(site.caller);
        }
        ps.println(callerID); // print the caller site ID (-1 if none)
      }
    }
  }
  
  /** Print unique site information, one item per line.  Doesn't enforce atomicity of printing since it uses
      separate sysWrite() calls. */
  /*
  public void sysWriteMultipleLines() {
    VM.sysWriteln(className);
    VM.sysWriteln(methodName);
    VM.sysWriteln(methodDesc);
    VM.sysWriteln(bci);
    VM.sysWriteln(line);
    if (caller != null) {
      ??
    }
  }
  */

  /** Pretty-print the site, including the line number.  Doesn't actually enforce atomicity of printing since it
      uses separate sysWrite() calls. */
  public void sysWrite() {
    VM.sysWrite(className);
    VM.sysWrite(".");
    VM.sysWrite(methodName);
    VM.sysWrite(" ");
    VM.sysWrite(methodDesc);
    VM.sysWrite(":");
    VM.sysWrite(line);
    VM.sysWrite("(");
    VM.sysWrite(bci);
    VM.sysWrite(")");
    if (caller != null) {
      VM.sysWrite(" called by ");
      caller.sysWrite();
    }
  }
  
  public void sysWriteln() {
    sysWrite();
    VM.sysWriteln();
  }
  
  @Interruptible
  public static void clearSites() {
    for (int i = 0; i < numSites; i++) {
      sites[i] = null;
    }
    if (siteMap != null) {
      siteMap.removeAll();
    }
    //siteMap = new HashMapRVM<Site, Integer>(MAX_SITES);
    numSites = 0;
    checkSites(false);
    if (VM.VerifyAssertions) { VM._assert(siteMap == null || siteMap.size() == 0); }
  }
  
  @Interruptible
  public static void checkSites(boolean print) {
    if (print || VM.VerifyAssertions) {
      for (int siteID = 0; siteID < numSites; siteID++) {
        Site site = lookupSite(siteID);
        if (print) {
          VM.sysWrite("Site ", siteID, ": ");
          site.sysWrite();
          VM.sysWriteln();
        }
        if (VM.VerifyAssertions) { VM._assert(siteMap == null || siteMap.get(site) == siteID); }
      }
      if (VM.VerifyAssertions) { VM._assert(siteMap == null || siteMap.size() == numSites); }
    }
  }
}
