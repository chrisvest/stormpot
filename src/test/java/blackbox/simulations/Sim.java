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
package blackbox.simulations;

import org.HdrHistogram.Histogram;
import stormpot.Expiration;
import stormpot.GenericPoolable;
import stormpot.ManagedPool;
import stormpot.MetricsRecorder;
import stormpot.Pool;
import stormpot.PoolBuilder;
import stormpot.Timeout;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static stormpot.AlloKit.$new;
import static stormpot.AlloKit.CountingAllocator;
import static stormpot.AlloKit.alloc;
import static stormpot.AlloKit.allocator;

public abstract class Sim {

  private static final Timeout SHUTDOWN_TIMEOUT = new Timeout(10, TimeUnit.SECONDS);

  protected enum Param {
    size(Integer.TYPE),
    expiration(Expiration.class),
    backgroundExpirationEnabled(Boolean.TYPE),
    preciseLeakDetectionEnabled(Boolean.TYPE),
    metricsRecorder(MetricsRecorder.class),
    threadFactory(ThreadFactory.class);

    private final Class<?> type;

    Param(Class<?> type) {
      this.type = type;
    }

    void set(PoolBuilder<GenericPoolable> builder, Object value) throws Exception {
      String firstNameChar = name().substring(0, 1);
      String restNameChars = name().substring(1);
      String setterName = "set" + firstNameChar.toUpperCase() + restNameChars;
      Method setter = PoolBuilder.class.getMethod(setterName, type);
      setter.invoke(builder, value);
    }
  }

  protected enum Output {
    none,
    summary,
    detailed
  }

  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.TYPE)
  @interface Simulation {
    long measurementTime() default 10;
    TimeUnit measurementTimeUnit() default TimeUnit.SECONDS;
    Output output() default Output.detailed;
  }

  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.FIELD)
  @interface Conf {
    Param value();
  }

  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  @interface Agent {
    String name() default "";
    long initialDelay() default 0;
    TimeUnit initialDelayUnit() default TimeUnit.MILLISECONDS;
  }

  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  @interface Agents {
    Agent[] value();
  }

  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  @interface AgentPause {
    String name() default "";
    TimeUnit unit() default TimeUnit.MILLISECONDS;
  }

  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  @interface AllocationCost {
    TimeUnit value() default TimeUnit.MILLISECONDS;
  }

  private static class Link<T> {
    final Link<T> tail;
    final T value;

    private Link(Link<T> tail, T value) {
      this.tail = tail;
      this.value = value;
    }
  }

  public static void main(String[] args) throws Exception {
    long start = System.nanoTime();
    String cmdClass = System.getProperty("sun.java.command").replaceFirst("^.*\\s+", "");
    Class<?> klass = Class.forName(cmdClass);
    Sim sim = (Sim) klass.getConstructor().newInstance();
    Simulation simulation = klass.getAnnotation(Simulation.class);

    // Go through all fields, constructing a set of configurations.
    // Run each configuration for each pool implementation.

    AtomicBoolean enableAllocationCost = new AtomicBoolean();
    Collection<PoolBuilder<GenericPoolable>> configurations =
        buildConfigurations(klass, sim, enableAllocationCost);
    for (PoolBuilder<GenericPoolable> builder : configurations) {
      Output output = simulation == null ? Output.detailed : simulation.output();
      simulate(sim, builder, enableAllocationCost, output);
    }
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    System.out.printf("Done, %.3f seconds.%n", elapsedMillis / 1000.0);
    System.exit(0);
  }

  private static Collection<PoolBuilder<GenericPoolable>> buildConfigurations(
      Class<?> klass, Sim sim, AtomicBoolean enableAllocationCost) throws Exception {
    // Find all fields annotated with @Conf
    // Group them by their Param category
    // Produce a PoolBuilder for every permutation of each group

    // Building the groups
    EnumMap<Param, List<Object>> groups = new EnumMap<>(Param.class);
    for (Field field : klass.getDeclaredFields()) {
      Conf conf = field.getAnnotation(Conf.class);
      if (conf != null) {
        Param param = conf.value();
        Object value = field.get(sim);
        List<Object> values = groups.computeIfAbsent(param, k -> new LinkedList<>());
        values.add(value);
      }
    }

    // Sequence the groups
    Link<Map.Entry<Param, List<Object>>> head = null;
    for (Map.Entry<Param, List<Object>> entry : groups.entrySet()) {
      head = new Link<>(head, entry);
    }

    // Building PoolBuilder permutations
    CountingAllocator allocator = buildAllocator(sim, enableAllocationCost);
    List<PoolBuilder<GenericPoolable>> result = new LinkedList<>();
    addPoolBuilderPermutations(head, Pool.from(allocator), result);
    return result;
  }

  private static CountingAllocator buildAllocator(
      final Sim sim,
      final AtomicBoolean enableAllocationCost) {
    Class<?> klass = sim.getClass();
    TimeUnit allocationTimeUnit = null;
    Method allocationTimeMethod = null;
    for (Method method : klass.getDeclaredMethods()) {
      AllocationCost allocationCost = method.getAnnotation(AllocationCost.class);
      if (allocationCost != null) {
        assert allocationTimeUnit == null : "Found more than one @AllocationCost method";
        allocationTimeUnit = allocationCost.value();
        allocationTimeMethod = method;
      }
    }

    if (allocationTimeMethod == null) {
      return allocator();
    }

    final TimeUnit unit = allocationTimeUnit;
    final Method method = allocationTimeMethod;
    return allocator(alloc((slot, obj) -> {
      if (enableAllocationCost.get()) {
        Number time = (Number) method.invoke(sim);
        unit.sleep(time.longValue());
      }
      return $new.apply(slot, obj);
    }));
  }

  private static void addPoolBuilderPermutations(
      Link<Map.Entry<Param, List<Object>>> head,
      PoolBuilder<GenericPoolable> builder,
      List<PoolBuilder<GenericPoolable>> result) throws Exception {
    if (head == null) {
      // Base case
      result.add(builder);
    } else {
      // Reduction
      Param param = head.value.getKey();
      List<Object> values = head.value.getValue();

      for (Object value : values) {
        if (value.getClass().isArray()) {
          int length = Array.getLength(value);
          for (int i = 0; i < length; i++) {
            PoolBuilder<GenericPoolable> mutation = builder.clone();
            param.set(mutation, Array.get(value, i));
            addPoolBuilderPermutations(head.tail, mutation, result);
          }
        } else {
          PoolBuilder<GenericPoolable> mutation = builder.clone();
          param.set(mutation, value);
          addPoolBuilderPermutations(head.tail, mutation, result);
        }
      }
    }
  }

  private static void simulate(
      Sim sim,
      PoolBuilder<GenericPoolable> builder,
      AtomicBoolean enableAllocationCost,
      Output output) throws Exception {
    DependencyResolver deps = new DependencyResolver();
    deps.add(builder);
    deps.add(builder.getAllocator());
    deps.add(builder.getExpiration());
    deps.add(builder.getMetricsRecorder());
    deps.add(builder.getThreadFactory());
    deps.add(output);

    System.out.printf("Simulating %s %s%n",
        sim.getClass().getSimpleName(), describe(builder));

    long measurementTimeMillis = getMeasurementTimeMillis(sim);
    int iterations = 100;
    long warmupTimeMillis = measurementTimeMillis / iterations;
    for (int i = 1; i <= iterations; i++) {
      boolean isWarmup = i < iterations;
      long runTime = isWarmup ? warmupTimeMillis : measurementTimeMillis;

      // Reset internal state.
      enableAllocationCost.set(false);
      Pool<GenericPoolable> pool = builder.build();
      sim = sim.getClass().getConstructor().newInstance();
      deps.replace(pool);

      // Wait for the pool to boot up.
      int size = builder.getSize();
      List<GenericPoolable> objs = new ArrayList<>();
      for (int j = 0; j < size; j++) {
        objs.add(pool.claim(new Timeout(30, TimeUnit.MINUTES)));
      }
      for (GenericPoolable obj : objs) {
        obj.release();
      }

      // Then run.
      enableAllocationCost.set(true);
      sim.run(deps, runTime, isWarmup ? Output.none : output);

      // Finally clean up a bit.
      if (!pool.shutdown().await(SHUTDOWN_TIMEOUT)) {
        ManagedPool managedPool = (ManagedPool) pool;
        System.err.printf(
            "Shutdown timed out! Config = %s, object leaks detected = %s%n",
            describe(builder), managedPool.getLeakedObjectsCount());
      }
    }
  }

  private static long getMeasurementTimeMillis(Sim sim) {
    Class<?> klass = sim.getClass();
    Simulation simulation = klass.getAnnotation(Simulation.class);
    if (simulation != null) {
      long time = simulation.measurementTime();
      TimeUnit unit = simulation.measurementTimeUnit();
      return unit.toMillis(time);
    }
    return 10000;
  }

  private static String describe(PoolBuilder<GenericPoolable> builder) {
    int size = builder.getSize();
    Expiration<? super GenericPoolable> expiration = builder.getExpiration();
    boolean backgroundExpirationEnabled = builder.isBackgroundExpirationEnabled();
    boolean preciseLeakDetectionEnabled = builder.isPreciseLeakDetectionEnabled();
    MetricsRecorder metricsRecorder = builder.getMetricsRecorder();
    ThreadFactory threadFactory = builder.getThreadFactory();
    return String.format(
        "{%n\tsize = %s" +
            "%n\texpiration = %s%n\t" +
            "backgroundExpirationEnabled = %s%n\t" +
            "preciseLeakDetectionEnabled = %s%n\t" +
            "metricsRecorder = %s%n\t" +
            "threadFactory = %s%n}",
        size, expiration, backgroundExpirationEnabled,
        preciseLeakDetectionEnabled, metricsRecorder, threadFactory);
  }

  private void run(
      DependencyResolver deps,
      long runTimeMillis,
      Output output) throws InterruptedException {
    // Find all agents and their corresponding configuration fields, and create
    // a thread for each.
    ControlSignal controlSignal = new ControlSignal();

    // Agents are defined by the @Agent or @Agents annotations.
    // Their timings are defined by the @AgentPause annotation, linked by their name.
    Method[] methods = getClass().getMethods();
    List<AgentRunner> agents = new ArrayList<>();
    Map<String, IterationPause> iterationPauseIndex = new HashMap<>();
    for (Method method : methods) {
      AgentPause agentPause = method.getAnnotation(AgentPause.class);
      if (agentPause != null) {
        IterationPause pause = new IterationPause(agentPause, this, method);
        iterationPauseIndex.put(agentPause.name(), pause);
      }
    }

    for (Method method : methods) {
      Agent agent = method.getAnnotation(Agent.class);
      if (agent != null) {
        startAgent(deps, controlSignal, agents, iterationPauseIndex, method, agent);
      }
      Agents agentsAnnotation = method.getAnnotation(Agents.class);
      if (agentsAnnotation != null) {
        for (Agent subAgent : agentsAnnotation.value()) {
          startAgent(deps, controlSignal, agents, iterationPauseIndex, method, subAgent);
        }
      }
    }

    if (agents.isEmpty()) {
      System.err.println("No public @Agent or @Agents annotated methods found!");
      return;
    }

    // Then start them all, and run them for as long as need be.
    controlSignal.start();
    Thread.sleep(runTimeMillis);
    controlSignal.stop();
    controlSignal.awaitDone(agents.size());

    // Finally print their results, if need be.
    if (output != Output.none) {
      Histogram sum = newHistogram();
      for (AgentRunner agent : agents) {
        sum.add(agent.histogram);
        if (output == Output.detailed) {
          agent.printResults();
        }
      }
      if (agents.size() > 1 || output == Output.summary) {
        System.out.println("Latency results sum:");
        printHistogram(sum);
        System.out.println();
      }
    }
  }

  private void startAgent(
      DependencyResolver deps,
      ControlSignal controlSignal,
      List<AgentRunner> agents,
      Map<String, IterationPause> iterationPauseIndex,
      Method method,
      Agent agent) {
    IterationPause iterationPause = iterationPauseIndex.get(agent.name());
    AgentRunner runner = new AgentRunner(
        controlSignal, deps, agent, this, method, iterationPause);
    Thread thread = new Thread(runner, "Agent[" + agent.name() + "]");
    thread.start();
    agents.add(runner);
  }

  private static class AgentRunner implements Runnable {
    private final ControlSignal controlSignal;
    private final long initialDelayTime;
    private final TimeUnit initialDelayUnit;
    private final String agentName;
    private final Sim sim;
    private final Method agentMethod;
    private final IterationPause iterationPause;
    private final Histogram histogram;
    private final Object[] argumentList;

    AgentRunner(
        ControlSignal controlSignal,
        DependencyResolver deps,
        Agent agent,
        Sim sim,
        Method agentMethod,
        IterationPause iterationPause) {
      this.controlSignal = controlSignal;
      initialDelayTime = agent.initialDelay();
      initialDelayUnit = agent.initialDelayUnit();
      agentName = agent.name();
      this.sim = sim;
      this.agentMethod = agentMethod;
      this.iterationPause = iterationPause;
      histogram = newHistogram();
      argumentList = deps.resolve(agentMethod.getParameterTypes());
    }

    @Override
    public void run() {
      try {
        runUnsafe();
      } catch (Exception e) {
        throw new RuntimeException("Agent[" + agentName + "] failed", e);
      }
    }

    private void runUnsafe() throws Exception {
      controlSignal.awaitStart();
      if (initialDelayUnit != null) {
        initialDelayUnit.sleep(initialDelayTime);
      }

      long lastPauseTime = 0;
      while (!controlSignal.stopped()) {
        long start = System.nanoTime();
        callAgentMethod();
        long elapsed = System.nanoTime() - start;
        long micros = elapsed / 1000;
        histogram.recordValueWithExpectedInterval(micros, lastPauseTime);
        lastPauseTime = iterationPause.pause(micros);
      }
      controlSignal.done();
    }

    private void callAgentMethod() {
      try {
        agentMethod.invoke(sim, argumentList);
      } catch (Exception e) {
        throw new RuntimeException("Agent[" + agentName + "] method invocation failure", e);
      }
    }

    void printResults() {
      System.out.printf("Latency results for Agent[%s]:%n", agentName);
      printHistogram(histogram);
      System.out.println();
    }
  }

  private static Histogram newHistogram() {
    return new Histogram(TimeUnit.MINUTES.toMicros(10), 3);
  }

  private static void printHistogram(Histogram histogram) {
    histogram.outputPercentileDistribution(System.out, 1, 1000.0);
  }

  private static class IterationPause {
    private final Sim sim;
    private final Method pauseMethod;
    private final TimeUnit pauseUnit;

    IterationPause(AgentPause agentPause, Sim sim, Method pauseMethod) {
      this.sim = sim;
      this.pauseMethod = pauseMethod;
      this.pauseUnit = agentPause.unit();
    }

    long pause(long spentTimeMicros) throws Exception {
      Number result = (Number) pauseMethod.invoke(sim);
      long pauseTime = result.longValue();
      long upauseTime = pauseUnit.toMicros(pauseTime);
      TimeUnit.MICROSECONDS.sleep(upauseTime - spentTimeMicros);
      return upauseTime;
    }
  }

  private static class ControlSignal {
    private final CountDownLatch startLatch;
    private final Semaphore doneCounter;
    private volatile boolean stopped;

    private ControlSignal() {
      startLatch = new CountDownLatch(1);
      doneCounter = new Semaphore(0);
    }

    void awaitStart() throws InterruptedException {
      startLatch.await();
    }

    void start() {
      startLatch.countDown();
    }

    boolean stopped() {
      return stopped;
    }

    void stop() {
      stopped = true;
    }

    void done() {
      doneCounter.release();
    }

    void awaitDone(int doneCounts) throws InterruptedException {
      doneCounter.acquire(doneCounts);
    }
  }

  private static class DependencyResolver {
    private final List<Object> dependencies;

    private DependencyResolver() {
      dependencies = new ArrayList<>();
    }

    void add(Object obj) {
      if (obj != null) {
        dependencies.add(obj);
      }
    }

    Object[] resolve(Class<?>[] types) {
      Object[] values = new Object[types.length];
      for (int i = 0; i < values.length; i++) {
        values[i] = resolve(types[i]);
      }
      return values;
    }

    private <T> T resolve(Class<T> type) {
      for (Object dependency : dependencies) {
        if (type.isInstance(dependency)) {
          return type.cast(dependency);
        }
      }
      return null;
    }

    void replace(Object obj) {
      int size = dependencies.size();
      for (int i = 0; i < size; i++) {
        if (dependencies.get(i).getClass() == obj.getClass()) {
          dependencies.set(i, obj);
          return;
        }
      }
      add(obj);
    }
  }
}
