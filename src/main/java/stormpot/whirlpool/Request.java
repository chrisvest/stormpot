package stormpot.whirlpool;


class Request {
  private static ThreadLocal<Request> requestRef = new ThreadLocal<Request>() {
    @Override
    protected Request initialValue() {
      return new Request();
    }
  };

  public static Request get() {
    return requestRef.get();
  }

  boolean active = false;
  WSlot requestOp;
  WSlot response;
  Request next;
  int passCount;
  Thread thread;
}
