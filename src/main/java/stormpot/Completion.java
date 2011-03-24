package stormpot;

import java.util.concurrent.TimeUnit;

public interface Completion {

  void await() throws InterruptedException;
  
  boolean await(long timeout, TimeUnit unit) throws InterruptedException;
  
}
