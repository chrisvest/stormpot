package stormpot;

public class PoolException extends RuntimeException {

  private static final long serialVersionUID = -1908093409167496640L;

  public PoolException() {
  }

  public PoolException(String message) {
    super(message);
  }

  public PoolException(Throwable cause) {
    super(cause);
  }

  public PoolException(String message, Throwable cause) {
    super(message, cause);
  }
}
