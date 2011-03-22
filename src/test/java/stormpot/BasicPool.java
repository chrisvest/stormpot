package stormpot;

public class BasicPool implements Pool {

  public BasicPool(Config config) {
    // TODO Auto-generated constructor stub
  }

  public Poolable claim() {
    return new Poolable() {
      public void release() {
      }
    };
  }

}
