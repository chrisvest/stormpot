package stormpot;

/**
 * The deallocation rule is used to determine if a given slot has expired, or
 * otherwise become invalid.
 * @author cvh
 */
public interface DeallocationRule<T extends Poolable> {
  /**
   * Test whether the slot and poolable object, represented by the given
   * {@link SlotInfo} object, is still valid, or if the pool should
   * deallocate it and allocate a replacement.
   * @param info An informative representative of the slot being tested.
   * @return <code>true</code> if the slot and poolable in question should be
   * deallocated, <code>false</code> if it is valid and elegible for claiming.
   */
  boolean isInvalid(SlotInfo<? extends T> info);
}
