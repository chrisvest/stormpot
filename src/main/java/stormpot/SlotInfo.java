package stormpot;

/**
 * An informative little interface, used by {@link DeallocationRule}s to
 * determine if a slot has expired or is invalid.
 * @author cvh
 * @param <T>
 */
public interface SlotInfo<T extends Poolable> {

  long getAgeMillis();

}
