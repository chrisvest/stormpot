package stormpot;

import java.util.concurrent.TimeUnit;

/**
 * A Completion represents some task that is going to be completed at some
 * point in the future, or maybe already has completed. It is similar to
 * {@link java.util.concurrent.Future Future} but without any options for
 * cancellation or returning a result. Indeed, you cannot even precisely tell
 * if the task has already completed, but the await methods will return
 * immediately if that is the case.
 * @author Chris Vest &lt;mr.chrisvest@gmail.com&gt;
 * @see LifecycledPool#shutdown()
 */
public interface Completion {

  void await() throws InterruptedException;
  
  boolean await(long timeout, TimeUnit unit) throws InterruptedException;
  
}
