package org.jikesrvm.esc;

import org.vmmagic.pragma.Uninterruptible;

@Uninterruptible
public class EscapeClient {

  /**
   * Called every time an object escapes.
   * @param object
   */
  @Uninterruptible
  public void escape(Object object) { }
}
