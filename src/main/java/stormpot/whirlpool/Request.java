package stormpot.whirlpool;

public class Request {
  private static Request request = new Request();

  public static Request get() {
    return request;
  }

}
