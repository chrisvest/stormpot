package stormpot;

public interface LifecycledPool<T extends Poolable> extends Pool<T> {

  void shutdown();

}
