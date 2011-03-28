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

  /**
   * Causes the current thread to wait until the completion is finished,
   * or the thread is {@link Thread#interrupt() interrupted}.
   * <p>
   * If the task represented by this completion has already completed,
   * the method returns immediately.
   * <p>
   * If the current thread already has its interrupted status set upon entry
   * to this method, or the thread is interrupted while waiting, then
   * {@link InterruptedException} is thrown and the current threads interrupted
   * status is cleared.
   * @throws InterruptedException if the current thread is interrupted while
   * waiting.
   */
  void await() throws InterruptedException;
  
  /**
   * Causes the current thread to wait until the completion is finished,
   * or the thread is {@link Thread#interrupt() interrupted}, or the specified
   * waiting time elapses.
   * <p>
   * If the task represented by this completion has already competed,
   * the method immediately returns <code>true</code>.
   * <p>
   * If the current thread already has its interrupted status set upon entry
   * to this method, or the thread is interrupted while waiting, then
   * {@link InterruptedException} is thrown and the current threads interrupted
   * status is cleared.
   * <p>
   * If the specified waiting time elapses, then the method returns
   * <code>false</code>.
   * @param timeout the maximum time to wait.
   * @param unit the unit of the <code>timeout</code> argument.
   * @return <code>true</code> if the task represented by this completion
   * completed within the specified waiting time, or was already complete upon
   * entry to this method; or <code>false</code> if the specified waiting time
   * elapsed before the task finished.
   * @throws InterruptedException if the current thread is interrupted while
   * waiting.
   */
  boolean await(long timeout, TimeUnit unit) throws InterruptedException;
}
