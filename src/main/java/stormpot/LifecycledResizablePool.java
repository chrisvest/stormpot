package stormpot;

/**
 * A {@link Pool} that is both {@link LifecycledPool life-cycled} and
 * {@link ResizablePool resizable}.
 * 
 * @author Chris Vest &lt;mr.chrisvest@gmail.com&gt;
 * @param <T> The type of {@link Poolable} contained in this pool.
 */
public interface LifecycledResizablePool<T extends Poolable>
extends LifecycledPool<T>, ResizablePool<T> {
}
