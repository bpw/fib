package org.jikesrvm.dr.metadata.maps;

import org.vmmagic.pragma.Inline;
import org.vmmagic.pragma.Uninterruptible;
import org.vmmagic.unboxed.Word;
import org.vmmagic.unboxed.WordArray;

@Uninterruptible
public class EmptyEpochMapper extends EpochMapper {

  @Override
  public WordArray create() {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public void set(WordArray readers, int tid, Word epoch) {
    // TODO Auto-generated method stub

  }

  @Override
  public Word get(WordArray map, int tid) {
    // TODO Auto-generated method stub
    return Word.zero();
  }

  @Inline
  @Override
  public boolean attempt(WordArray map, int tid, Word oldEpoch, Word newEpoch) {
    return false;
  }

  @Override
  public boolean attemptReserve(WordArray map, int tid, Word old) {
    // TODO Auto-generated method stub
    return false;
  }

}
