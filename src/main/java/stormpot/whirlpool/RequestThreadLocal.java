package stormpot.whirlpool;

public class RequestThreadLocal extends ThreadLocal<Request> {
  @Override
  protected Request initialValue() {
    return new Request();
  }
}
