package stormpot;

import java.util.concurrent.atomic.AtomicInteger;

public class CountingDeallocationRule implements DeallocationRule {
  private final boolean[] replies;
  private final AtomicInteger counter;

  public CountingDeallocationRule(boolean... replies) {
    this.replies = replies;
    counter = new AtomicInteger();
  }

  @Override
  public <T extends Poolable> boolean isInvalid(SlotInfo<T> info) {
    int count = counter.getAndIncrement();
    int index = Math.max(count, replies.length - 1);
    return replies[index];
  }

  public int getCount() {
    return counter.get();
  }
}
