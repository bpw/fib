package org.jikesrvm.esc;

import org.jikesrvm.Callbacks;
import org.jikesrvm.Callbacks.ExitMonitor;
import org.jikesrvm.util.HashMapRVM;
import org.jikesrvm.util.IdentityHashMapRVM;
import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class EscapeStats extends org.jikesrvm.octet.Stats {

  static final IdentityHashMapRVM<Object, Object> failedObjects = Esc.getConfig().escapeCollectAssertFailure() ?
      new IdentityHashMapRVM<Object, Object>(65536) : null;
  private static final boolean stats = Esc.getConfig().escapeStats();
  
  // public static final UnsyncCounter Alloc_BootImage_Internal = new UnsyncCounter("Alloc_BootImage_Internal", stats);
  public static final ThreadSafeCounter Alloc_BootImage = new ThreadSafeCounter("Alloc_BootImage", true, stats);
  public static final ThreadSafeCounter Alloc_Runtime = new ThreadSafeCounter("Alloc_Runtime", true, stats);   // all object allocated at runtime
  public static final ThreadSafeCounter Alloc_Runtime_inHarness = new ThreadSafeCounter("Alloc_Runtime_inHarness", false, stats);
  // Man: VM objects could be allocated before entering harness, but escaped in harness. So we just count all objects, no matter in harness or not.
  public static final ThreadSafeCounter Alloc_Runtime_VM = new ThreadSafeCounter("Alloc_Runtime_VM", true, stats);
  public static final ThreadSafeCounter Alloc_Runtime_NonVM = new ThreadSafeCounter("Alloc_Runtime_NonVM", true, stats);
  // The following two types of object are immediately counted as escaped after allocation.
  public static final ThreadSafeCounter Alloc_Runtime_Reflect = new ThreadSafeCounter("Alloc_Runtime_Reflect", true, stats);
  public static final ThreadSafeCounter Alloc_Runtime_VMContext = new ThreadSafeCounter("Alloc_Runtime_VMContext", true, stats);
  
  static final ThreadSafeCounter Escaped_Runtime = new ThreadSafeCounter("Escaped_Runtime", true, stats);
  static final ThreadSafeCounter Escaped_Runtime_inHarness = new ThreadSafeCounter("Escaped_Runtime_inHarness", false, stats);
  static final ThreadSafeCounter Escaped_Runtime_VM = new ThreadSafeCounter("Escaped_Runtime_VM", true, stats);
  static final ThreadSafeCounter Escaped_Runtime_NonVM = new ThreadSafeCounter("Escaped_Runtime_NonVM", true, stats);

  public static final ThreadSafeCounter ReadObject_Escaped = new ThreadSafeCounter("ReadObject_Escaped", false, stats);
  public static final ThreadSafeCounter ReadObject_Not_Escaped = new ThreadSafeCounter("ReadObject_Not_Escaped", false, stats);
  public static final ThreadSafeCounter WriteObject_Escaped = new ThreadSafeCounter("WriteObject_Escaped", false, stats);
  public static final ThreadSafeCounter WriteObject_Not_Escaped = new ThreadSafeCounter("WriteObject_Not_Escaped", false, stats);
  
  static final UnsyncHistogram logTracingIter = new UnsyncHistogram("logTracingIter", true, 1L<<32, stats);
  
  static final ThreadSafeSumCounter Alloc = new ThreadSafeSumCounter("Alloc", stats, Alloc_BootImage, Alloc_Runtime);
  static final ThreadSafeSumCounter Escaped = new ThreadSafeSumCounter("Escaped", stats, Alloc_BootImage, Escaped_Runtime);
  
  static final ThreadSafeRatioCounter Escaped_Ratio = new ThreadSafeRatioCounter("Escaped_Ratio", Escaped, Alloc, stats);
  static final ThreadSafeRatioCounter Escaped_Ratio_inHarness = new ThreadSafeRatioCounter("Escaped_Ratio_inHarness", Escaped_Runtime_inHarness, Alloc_Runtime_inHarness, stats);
  static final ThreadSafeRatioCounter Escaped_Ratio_NonVM = new ThreadSafeRatioCounter("Escaped_Ratio_NonVM", Escaped_Runtime_NonVM, Alloc_Runtime_NonVM, stats);
  static final ThreadSafeSumCounter Access_Escaped = new ThreadSafeSumCounter("Access_Escaped", stats, ReadObject_Escaped, WriteObject_Escaped);
  static final ThreadSafeSumCounter Total_Access = new ThreadSafeSumCounter("Total_Access", stats, Access_Escaped, ReadObject_Not_Escaped, WriteObject_Not_Escaped);
  static final ThreadSafeRatioCounter Escaped_Access_Ratio = new ThreadSafeRatioCounter("Escaped_Access_Ratio", Access_Escaped, Total_Access, stats);
  
  static {
    Callbacks.addExitMonitor(new ExitMonitor() {
      //@Override
      public void notifyExit(int value) {
        printAssertionFailingObjects();
      }

      private void printAssertionFailingObjects() {
        if (Esc.getConfig().escapeCollectAssertFailure()) {
          HashMapRVM<Class<?>, Integer> typeCount = new HashMapRVM<Class<?>, Integer> ();
          for (Object o : failedObjects.keys()) {
            // System.out.println(o.getClass().toString());
            Class<?> c = o.getClass();
            Integer count = typeCount.get(c);
            if (count != null) {
              typeCount.put(c, count + 1);
            } else {
              typeCount.put(c, 1);
            }
          }
          System.out.println();
          System.out.println("Printing assertion failing types...");
          for (Class<?> c : typeCount.keys()) {
            System.out.print(c.toString());
            System.out.println(":  " + typeCount.get(c));
          }
          System.out.println("Total assertion failing objects: " + failedObjects.size());
          System.out.println("Total assertion failing types: " + typeCount.size());
        }
      }
    });
  }
}
