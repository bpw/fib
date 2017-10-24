package org.jikesrvm.compilers.opt;

import java.util.HashMap;
import java.util.Set;

import org.jikesrvm.compilers.opt.ir.IR;

/**
 * Implements a lattice for keeping data flow facts for objects accessed inside
 * an {@link IR}
 * 
 * @author Meisam
 */
public class ObjectsAccessFacts implements Cloneable {
  
  private static final int INITAL_CAPACITY = 4;

  /**
   * Maps each object accessed object to {@code RedundantBarrieresLatticeType}
   */
  private HashMap<Object, BarrierType> objectBarrierInfo;

  /**
   * Constructs a new HashMap
   */
  public ObjectsAccessFacts() {
    this.objectBarrierInfo = new HashMap<Object, BarrierType>(INITAL_CAPACITY);
  }

  /**
   * Adds a fact about the barrier that are redundant for a given object
   * 
   * @param accessedObject
   *          The given object
   * @param barrierType
   *          The type of barrier that would be redundant
   */
  public void updateFact(final Object accessedObject,
      final BarrierType barrierType) {
    // The concurrent hash map does not allow null key.
    if (accessedObject != null) {
      if (barrierType == BarrierType.BOTTOM) {
        objectBarrierInfo.remove(accessedObject);
      } else {
        objectBarrierInfo.put(accessedObject, barrierType);
      }
    }
  }
  
  public void updateFact(HashMap<Object, BarrierType> newObjectBarrierInfo, final Object accessedObject,
      final BarrierType barrierType) {
    // The concurrent hash map does not allow null key.
    if (accessedObject != null) {
      if (barrierType == BarrierType.BOTTOM) {
        newObjectBarrierInfo.remove(accessedObject);
      } else {
        newObjectBarrierInfo.put(accessedObject, barrierType);
      }
    }
  }

  /**
   * Returns the fact for the given object.
   * <p>
   * NOTE: This does not return null, it returns BOTTOM instead of null.
   * 
   * @param accessedObject
   *          The given object
   * @return the fact about barriers for the given object
   */
  public BarrierType lookupFact(final Object accessedObject) {
    BarrierType barrierType = objectBarrierInfo.get(accessedObject);
    return (barrierType == null) ? BarrierType.BOTTOM : barrierType;
  }

  // Octet: Later: rename this method to setBottom?
  public void clearFact(final Object accessedObject) {
    objectBarrierInfo.remove(accessedObject);
  }

  /**
   * Sets all facts to BOTTOM
   */
  public void clearAllFacts() {
    objectBarrierInfo.clear();
  }

  /**
   * Keeps all top facts and sets all other facts to BOTTOM.
   */
  public void clearAllNonTopFacts() {
    HashMap<Object, BarrierType> newMap = new HashMap<Object, BarrierType>(INITAL_CAPACITY);

    for (Object object : objectBarrierInfo.keySet()) {
      BarrierType barrierType = objectBarrierInfo.get(object);
      if (barrierType == BarrierType.TOP) {
        newMap.put(object, BarrierType.TOP);
      }
    }
    objectBarrierInfo = newMap;
  }

  /** Get all the objects with non-bottom facts, i.e., all the objects in the facts map. */
  public Set<Object> getObjects() {
    return objectBarrierInfo.keySet();
  }
  
  /**
   * Promotes the barrier of the given object by updating the barrier with the
   * supremum of the object barrier and the given barrier.
   * 
   * @param object
   *          The given object
   * @param promotedBarrier
   *          the given barrier
   */
  public void updateToSupremumBarrier(Object object, BarrierType promotedBarrier) {
    BarrierType barrierType = lookupFact(object);
    if (barrierType.isLower(promotedBarrier)) {
      updateFact(object, promotedBarrier);
    }
  }

  /**
   * Updates the barrier of the given object by updating the barrier with the
   * infimum of the object barrier and the given barrier.
   * 
   * @param object
   *          The given object
   * @param promotedBarrier
   *          the given barrier
   */
  public void updateToInfimumBarrier(Object object, BarrierType relegatedBarrier) {
    BarrierType barrierType = lookupFact(object);
    if (barrierType.isHigher(relegatedBarrier)) {
      updateFact(object, relegatedBarrier);
    }
  }

  public boolean isWriteBarrierRedundant(final Object accessedObject) {
    return lookupFact(accessedObject).isHigher(BarrierType.WRITE);
  }

  public boolean isReadBarrierRedundant(final Object accessedObject) {
    return lookupFact(accessedObject).isHigher(BarrierType.READ);

  }

  /**
   * Meets the facts in this instance with the facts in the given
   * ObjectsAccessFacts
   * 
   * @param otherFacts
   *          the given ObjectsAccessFacts
   */
  public void meet(final ObjectsAccessFacts otherFacts) {
    HashMap<Object, BarrierType> newObjectBarrierInfo = new HashMap<Object, BarrierType>(INITAL_CAPACITY);
    for (Object object : this.objectBarrierInfo.keySet()) {
      BarrierType thisBarrier = this.lookupFact(object);
      BarrierType otherBarrier = otherFacts.lookupFact(object);
      BarrierType infimumBarrier = thisBarrier.infimum(otherBarrier);
      //As we're quierying the hash map here while at the same time we may also update the hash map, to avoid a concurrent update exception, we need to choose concurrentHashMap.
      newObjectBarrierInfo.put(object,infimumBarrier);
    }
    this.objectBarrierInfo = newObjectBarrierInfo;
  }

  /**
   * Joins facts in this instance with the facts in the given
   * {@link ObjectsAccessFacts}
   * 
   * @param otherFacts
   *          the given {@link ObjectsAccessFacts}
   */
  public void join(final ObjectsAccessFacts otherFacts) {
    Set<Object> otherFactKeys = otherFacts.objectBarrierInfo.keySet();

    for (Object object : this.objectBarrierInfo.keySet()) {
      BarrierType thisBarrier = this.objectBarrierInfo.get(object);
      BarrierType otherBarrier = otherFacts.objectBarrierInfo.get(object);

      BarrierType infimumBarrier = thisBarrier.supremum(otherBarrier);
      //depricated! It is unclear the semantic of what will happen enumerating a hash map while at the same inserting something into that hasha map.
      updateFact(otherFactKeys, infimumBarrier);

    }
  }

  @Override
  protected ObjectsAccessFacts clone() {
    try {
      ObjectsAccessFacts clone = (ObjectsAccessFacts) super.clone();
      clone.objectBarrierInfo = new HashMap<Object, BarrierType>(this.objectBarrierInfo);
      return clone;
    } catch (CloneNotSupportedException ex) {
      throw new InternalError();
    }
  }

  @Override
  public String toString() {
    return "[ObjectsAccessFacts: " + objectBarrierInfo.toString() + "]";
  }

  /*
   * (non-Javadoc)
   * 
   * @see java.lang.Object#equals(java.lang.Object)
   */
  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    }

    // Octet: TODO later: Meisam: Mike suggests that I change this to
    // assertions.
    if (o == null) {
      return false;
    }
    if (!(o instanceof ObjectsAccessFacts)) {
      return false;
    }

    ObjectsAccessFacts otherFacts = (ObjectsAccessFacts) o;
    return this.objectBarrierInfo.equals(otherFacts.objectBarrierInfo);
  }

  /*
   * @see java.lang.Object#hashCode()
   */
  @Override
  public int hashCode() {
    return objectBarrierInfo.hashCode();
  }
}