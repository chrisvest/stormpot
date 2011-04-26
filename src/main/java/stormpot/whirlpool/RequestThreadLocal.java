package stormpot.whirlpool;

class RequestThreadLocal extends ThreadLocal<Request> {
  @Override
  protected Request initialValue() {
    return new Request();
  }
}
