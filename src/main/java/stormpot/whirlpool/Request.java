package stormpot.whirlpool;

public class Request {
  private static ThreadLocal<Request> requestRef = new ThreadLocal<Request>();

  public static Request get() {
    Request request = requestRef.get();
    if (request == null) {
      request = new Request();
      requestRef.set(request);
    }
    return request;
  }

  public boolean active() {
    return true;
  }

}
