package org.jikesrvm.esc;

import org.jikesrvm.Constants;
import org.jikesrvm.VM;
import org.jikesrvm.mm.mminterface.MemoryManager;
import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.pragma.UninterruptibleNoWarn;
import org.vmmagic.unboxed.Address;

@Uninterruptible
public final class LinkedListAddress implements Constants {

  private int count = 0;
  private Node head = null;
  private Node tail = null;

  private static class Node {
    Address entry;
    Node next;
    Node (Address entry) {
      this.entry = entry;
    }
  }

  @Inline
  @UninterruptibleNoWarn
  public boolean add(final Address entry) {
    MemoryManager.startAllocatingInUninterruptibleCode();
    final Node element = new Node(entry);
    MemoryManager.stopAllocatingInUninterruptibleCode();
    element.next = null;
    if (head == null) {
      if (VM.VerifyAssertions) VM._assert(tail == null);
      head = element;
    } else {
      tail.next = element;
    }
    tail = element;
    count++;
    return true;
  }

  @Inline
  public void clear() {
    head = tail = null;
    count = 0;
  }

  @Inline
  public boolean isEmpty() {
    return count == 0;
  }

  @Inline
  public int size() {
    return count;
  }

  public Address get(int index) {
    /* Special-case getting the head of the list for speed */
    if (index == 0 && head != null) {
      return head.entry;
    }

    /* bounds check */
    if (index < 0 || index >= size()) {
      VM.sysFail("LinkedListAddress: index for get() out of bound!");
    }

    Node cursor = head;
    for (int i = 0; i < index; i++) {
      cursor = cursor.next;
    }
    return cursor.entry;
  }

  public Address remove(int index) {
    /* bounds check */
    if (index < 0 || index >= size()) {
      VM.sysFail("LinkedListAddress: index for remove() out of bound!");
    }

    Node cursor = head;
    Node prev = null;
    int i = 0;
    while (i < index) {
      prev = cursor;
      cursor = cursor.next;
      i++;
    }

    if (i == 0) {
      head = cursor.next;
    } else {
      prev.next = cursor.next;
    }

    if (cursor.next == null) {
      if (VM.VerifyAssertions) VM._assert(cursor == tail);
      tail = prev;
    }

    count--;
    return cursor.entry;
  }

  @Inline
  public Address popHead() {
    if (VM.VerifyAssertions) VM._assert(count > 0);
    Address rt = head.entry;
    count--;
    head = head.next;
    if (head == null) {
      if (VM.VerifyAssertions) VM._assert(count == 0);
      tail = null;
    }
    return rt;
  }

}
