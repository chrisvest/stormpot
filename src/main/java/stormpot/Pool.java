package stormpot;

public interface Pool<T extends Poolable> {
  T claim();
}
