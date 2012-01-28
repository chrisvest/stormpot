package stormpot;

public interface ResizablePool<T extends Poolable> extends Pool<T> {

  void setTargetSize(int size);

  int getTargetSize();

}
