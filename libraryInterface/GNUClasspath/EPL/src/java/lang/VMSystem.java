/*
 *  This file is part of the Jikes RVM project (http://jikesrvm.org).
 *
 *  This file is licensed to You under the Eclipse Public License (EPL);
 *  You may not use this file except in compliance with the License. You
 *  may obtain a copy of the License at
 *
 *      http://www.opensource.org/licenses/eclipse-1.0.php
 *
 *  See the COPYRIGHT.txt file distributed with this work for information
 *  regarding copyright ownership.
 */
package java.lang;

import java.io.InputStream;
import java.io.PrintStream;
import java.util.List;

import org.jikesrvm.VM;
import org.jikesrvm.objectmodel.ObjectModel;
import org.jikesrvm.runtime.Time;

/**
 * Library support interface of Jikes RVM
 */
public final class VMSystem {

  // Octet: TODO: try to add this
  //static int arraycopySiteID = Site.newSite(Atom.findOrCreateAsciiAtom("VMSystem"), Atom.findOrCreateAsciiAtom("arraycopy"), -1);
  static void arraycopy(Object src, int srcPos, Object dst, int dstPos, int len) {
    /*
    if (Octet.instrAccess()) {
      Instr.arrayReadBarrier(src, 0, arraycopySiteID);
      Instr.arrayWriteBarrier(dst, 0, arraycopySiteID);
    }
    */
    
    VMCommonLibrarySupport.arraycopy(src, srcPos, dst, dstPos, len);
  }

  static int identityHashCode(Object o) {
    return o == null ? 0 : ObjectModel.getObjectHashCode(o);
  }

  static boolean isWordsBigEndian() {
    return !VM.LittleEndian;
  }

  public static long currentTimeMillis() {
    return Time.currentTimeMillis();
  }

  public static long nanoTime() {
    return Time.nanoTime();
  }

  static void setIn(InputStream in) {
    VMCommonLibrarySupport.setSystemStreamField("in", in);
  }

  static void setOut(PrintStream out) {
    VMCommonLibrarySupport.setSystemStreamField("out", out);
  }

  static void setErr(PrintStream err) {
    VMCommonLibrarySupport.setSystemStreamField("err", err);
  }

  // Octet: The VM's versions of these streams, which (unlike System.*) can't be changed by the application.
  public static InputStream in;
  public static PrintStream out;
  public static PrintStream err;

  static InputStream makeStandardInputStream() { return null; }

  static PrintStream makeStandardOutputStream() { return null; }

  static PrintStream makeStandardErrorStream() { return null; }

  /**
   * Get the value of an environment variable.
   */
  static String getenv(String envarName) {
    return VMCommonLibrarySupport.getenv(envarName);
  }

  static native List<?> environ();
}
