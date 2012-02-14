package stormpot;

/**
 * The deallocation rule is used to determine if a given slot has expired, or
 * otherwise become invalid.
 * @author cvh
 *
 */
public interface DeallocationRule {
  <T extends Poolable> boolean isInvalid(SlotInfo<T> info);
}
