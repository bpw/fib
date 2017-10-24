package org.jikesrvm.compilers.opt;

/**
 * Octet: This enum represents different types of information that are used in
 * RedundantBarrierAnalysis.
 * 
 * @author Meisam
 * 
 */
public enum BarrierType {

  /**
   * Object is NOT protected by any barrier
   * <p>
   * Note: Normally, this should never be used. If barrier information for an
   * object does not exist in the {@code ObjectsAccessFacts} then it means that
   * the type of barrier for that object is {@code BOTTOM}.
   */
  BOTTOM,

  /**
   * Object is protected by read barrier, so adding a new read barrier to it
   * would be redundant.
   */
  READ,

  /**
   * Object is protected by a write barrier, so adding a new read barrier or a
   * write barrier to it would be redundant.
   */
  WRITE,

  /**
   * Object is newly instantiated and is visible just to one thread. Even a safe
   * point cannot cause this object lose its write exclusive access.
   */
  TOP;

  /**
   * Returns true if this barrier is "stronger" than the other given barrier.
   * 
   * @param otherBarrier
   *          the other given barrier
   * @return true if this barrier is higher
   * 
   */
  public boolean isHigher(final BarrierType otherBarrier) {
    return this.compareTo(otherBarrier) >= 0;
  }

  /**
   * Returns true if this barrier is "weaker" than the other given barrier.
   * 
   * @param otherBarrier
   *          other given barrier
   * @return true if this barrier is higher
   * @param otherBarrier
   * @return
   */
  public boolean isLower(final BarrierType otherBarrier) {
    return this.compareTo(otherBarrier) <= 0;
  }

  /**
   * Using the terminology of a lattice.
   * 
   * @param otherBarrier
   * @return
   */
  public BarrierType infimum(final BarrierType otherBarrier) {
    return this.compareTo(otherBarrier) < 0 ? this : otherBarrier;
  }

  /**
   * Using the terminology of a lattice
   * 
   * @param otherBarrier
   * @return
   */
  public BarrierType supremum(final BarrierType otherBarrier) {
    return this.compareTo(otherBarrier) > 0 ? this : otherBarrier;
  }
}
