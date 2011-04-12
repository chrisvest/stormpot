package stormpot.whirlpool;

public class Request {
  private static ThreadLocal<Request> request = new ThreadLocal<Request>() {
    @Override
    protected Request initialValue() {
      return new Request();
    }
  };

  public static Request get() {
    return request.get();
  }

}
