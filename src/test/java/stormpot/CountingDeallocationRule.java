package stormpot;

import java.util.concurrent.atomic.AtomicInteger;

public class CountingDeallocationRule implements DeallocationRule<Poolable> {
  private final boolean[] replies;
  private final AtomicInteger counter;

  public CountingDeallocationRule(boolean... replies) {
    this.replies = replies;
    counter = new AtomicInteger();
  }

  @Override
  public boolean isInvalid(SlotInfo<? extends Poolable> info) {
    int count = counter.getAndIncrement();
    int index = Math.max(count, replies.length - 1);
    return replies[index];
  }

  public int getCount() {
    return counter.get();
  }
}
