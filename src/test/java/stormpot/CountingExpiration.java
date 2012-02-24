package stormpot;

import java.util.concurrent.atomic.AtomicInteger;

public class CountingExpiration implements Expiration<Poolable> {
  private final boolean[] replies;
  private final AtomicInteger counter;

  public CountingExpiration(boolean... replies) {
    this.replies = replies;
    counter = new AtomicInteger();
  }

  @Override
  public boolean hasExpired(SlotInfo<? extends Poolable> info) {
    int count = counter.getAndIncrement();
    int index = Math.max(count, replies.length - 1);
    return replies[index];
  }

  public int getCount() {
    return counter.get();
  }
}
