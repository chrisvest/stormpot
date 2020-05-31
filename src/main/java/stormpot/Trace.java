/*
 * Copyright © 2011-2019 Chris Vest (mr.chrisvest@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package stormpot;

import java.util.Arrays;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

public final class Trace {
  private static final ThreadLocal<Trace> TRACE = new ThreadLocal<>();
  private static final StackWalker SW = StackWalker.getInstance();
  public static final Function<Stream<StackWalker.StackFrame>, String> LOC = s -> {
    Optional<StackWalker.StackFrame> frame = s.skip(2).findFirst();
    if (frame.isPresent()) {
      var f = frame.get();
      return f.toStackTraceElement().toString();
    } else {
      return "NO FRAME";
    }
  };
  private int len;
  private String[] tag = new String[16];
  private Object[] val = new Object[16];
  private String[] loc = new String[16];

  public static Trace create() {
    Trace trace = TRACE.get();
    if (trace == null) {
      trace = new Trace();
      TRACE.set(trace);
    }
    trace.len = 0;
    return trace;
  }

  public static Trace get() {
    return TRACE.get();
  }

  private void add(String t, Object v) {
    ensureCapacity();
    tag[len] = t;
    val[len] = v;
    loc[len] = SW.walk(LOC);
    len++;
  }

  private void ensureCapacity() {
    if (tag.length == len) {
      int newLength = len * 2;
      tag = Arrays.copyOf(tag, newLength);
      val = Arrays.copyOf(val, newLength);
      loc = Arrays.copyOf(loc, newLength);
    }
  }

  public static void print() {
    Trace trace = TRACE.get();
    System.err.println("Trace:\n" + trace);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < len; i++) {
      sb.append("\tat ").append(loc[i]).append(" ")
          .append(tag[i]).append(" = ").append(val[i]).append('\n');
    }
    return sb.toString();
  }

  public <T> void tlrSlot(T obj) {
    add("tlr_slot", obj);
  }

  public boolean live2claimTlr(boolean v) {
    add("live2claimTlr", v);
    return v;
  }

  public <T extends Poolable> T ret_tlr(T obj) {
    add("ret_tlr", obj);
    return obj;
  }

  public <T extends Poolable> T ret_slow(T slowClaim) {
    add("ret_slow", slowClaim);
    return slowClaim;
  }

  public boolean isUncommonlyInvalid(boolean uncommonlyInvalid) {
    add("uncommonly_invalid", uncommonlyInvalid);
    return uncommonlyInvalid;
  }

  public boolean isShutDown(boolean shutdown) {
    add("is_shut_down", shutdown);
    return shutdown;
  }

  public boolean slotHasPoison(boolean b) {
    add("slot_has_poison", b);
    return b;
  }

  public void slot_poison(Exception poison) {
    add("slot_poison", poison);
  }

  public boolean poisonIsShutDown(boolean b) {
    add("poison_is_shutdown", b);
    return b;
  }

  public void claim2live(Object slot) {
    add("claim2live", slot);
  }

  public boolean isTlr(boolean isTlr) {
    add("is_tlr", isTlr);
    return isTlr;
  }

  public boolean explicitExpirePoison(boolean b) {
    add("explicit_expire_poison", b);
    return b;
  }

  public boolean isClaimed(boolean claimed) {
    add("is_claimed", claimed);
    return claimed;
  }

  public void claim2dead(Object slot) {
    add("claim2dead", slot);
  }

  public void claimTlr2live(Object slot) {
    add("claimTlr2live", slot);
  }

  public boolean hasExpired(boolean hasExpired) {
    add("has_expired", hasExpired);
    return hasExpired;
  }

  public void commonInvlidation(Object slot) {
    add("common_invalidation", slot);
  }

  public void commonInvlidationExc(Object exc) {
    add("common_invalidation_exception", exc);
  }

  public void start(long startNanos) {
    add("start_nanos", startNanos);
  }

  public void timeLeft(long timeoutLeft) {
    add("timeout_left_nanos", timeoutLeft);
  }

  public void newAllocPop(Object slot) {
    add("new_alloc_pop", slot);
  }

  public void pollWait(long pollWait) {
    add("poll_wait", pollWait);
  }

  public void livePoll(Object slot) {
    add("live_poll", slot);
  }

  public boolean live2claim(boolean live2claim) {
    add("live2claim", live2claim);
    return live2claim;
  }

  public boolean isInvalid(boolean invalid) {
    add("is_invalid", invalid);
    return invalid;
  }

  public void disregard(Object slot) {
    add("disregard", slot);
  }
}
