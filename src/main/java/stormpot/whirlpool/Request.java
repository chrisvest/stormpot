package stormpot.whirlpool;

class Request {
  private static ThreadLocal<Request> requestRef = new ThreadLocal<Request>();

  public static Request get() {
    Request request = requestRef.get();
    if (request == null || !request.active) {
      request = new Request();
      requestRef.set(request);
    }
    return request;
  }

  private boolean active = true;

  public boolean active() {
    return active;
  }

  static void clear() {
    requestRef.set(null);
  }

  public void deactivate() {
    active = false;
  }

}
