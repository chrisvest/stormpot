/*
 * Copyright (C) 2011-2014 Chris Vest (mr.chrisvest@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package stormpot.simulations;

import org.HdrHistogram.Histogram;
import stormpot.*;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static stormpot.AlloKit.*;

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

    void set(Config<GenericPoolable> config, Object value) throws Exception {
      String firstNameChar = name().substring(0, 1);
      String restNameChars = name().substring(1);
      String setterName = "set" + firstNameChar.toUpperCase() + restNameChars;
      Method setter = Config.class.getMethod(setterName, type);
      setter.invoke(config, value);
    }
  }

  protected enum Output {
    none,
    summary,
    detailed
  }

  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.TYPE)
  protected @interface Simulation {
    long measurementTime() default 10;
    TimeUnit measurementTimeUnit() default TimeUnit.SECONDS;
    Class<? extends Pool>[] pools() default {QueuePool.class, BlazePool.class};
    Output output() default Output.detailed;
  }

  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.FIELD)
  protected @interface Conf {
    Param value();
  }

  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  protected @interface Agent {
    String name() default "";
    long initialDelay() default 0;
    TimeUnit initialDelayUnit() default TimeUnit.MILLISECONDS;
  }

  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  protected @interface Agents {
    Agent[] value();
  }

  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  protected @interface AgentPause {
    String name() default "";
    TimeUnit unit() default TimeUnit.MILLISECONDS;
  }

  @Retention(RetentionPolicy.RUNTIME)
  @Target(ElementType.METHOD)
  protected @interface AllocationCost {
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

  @SuppressWarnings("unchecked")
  public static void main(String[] args) throws Exception {
    long start = System.currentTimeMillis();
    String cmdClass = System.getProperty("sun.java.command").replaceFirst("^.*\\s+", "");
    Class<?> klass = Class.forName(cmdClass);
    Sim sim = (Sim) klass.newInstance();

    // Find all the pool implementation constructors.
    List<Constructor<Pool<GenericPoolable>>> ctors =
        new ArrayList<>();
    Simulation simulation = klass.getAnnotation(Simulation.class);
    if (simulation != null) {
      for (Class<? extends Pool> type : simulation.pools()) {
        Constructor<?> constructor = type.getConstructor(Config.class);
        ctors.add((Constructor<Pool<GenericPoolable>>) constructor);
      }
    } else {
      Constructor<?> constructor = QueuePool.class.getConstructor(Config.class);
      ctors.add((Constructor<Pool<GenericPoolable>>) constructor);
      constructor = BlazePool.class.getConstructor(Config.class);
      ctors.add((Constructor<Pool<GenericPoolable>>) constructor);
    }

    // Go through all fields, constructing a set of configurations.
    // Run each configuration for each pool implementation.

    AtomicBoolean enableAllocationCost = new AtomicBoolean();
    Collection<Config<GenericPoolable>> configurations =
        buildConfigurations(klass, sim, enableAllocationCost);
    for (Config<GenericPoolable> config : configurations) {
      for (Constructor<Pool<GenericPoolable>> ctor : ctors) {
        Output output = simulation == null? Output.detailed : simulation.output();
        simulate(sim, ctor, config, enableAllocationCost, output);
      }
    }
    long elapsedMillis = System.currentTimeMillis() - start;
    System.out.printf("Done, %.3f seconds.%n", elapsedMillis / 1000.0);
    System.exit(0);
  }

  private static Collection<Config<GenericPoolable>> buildConfigurations(
      Class<?> klass, Sim sim, AtomicBoolean enableAllocationCost) throws Exception {
    // Find all fields annotated with @Conf
    // Group them by their Param category
    // Produce a Config for every permutation of each group

    // Building the groups
    EnumMap<Param, List<Object>> groups = new EnumMap<>(Param.class);
    for (Field field : klass.getDeclaredFields()) {
      Conf conf = field.getAnnotation(Conf.class);
      if (conf != null) {
        Param param = conf.value();
        Object value = field.get(sim);
        List<Object> values = groups.get(param);
        if (values == null) {
          values = new LinkedList<>();
          groups.put(param, values);
        }
        values.add(value);
      }
    }

    // Sequence the groups
    Link<Map.Entry<Param, List<Object>>> head = null;
    for (Map.Entry<Param, List<Object>> entry : groups.entrySet()) {
      head = new Link<>(head, entry);
    }

    // Building Config permutations
    Config<GenericPoolable> baseConfig = new Config<>();
    CountingAllocator allocator = buildAllocator(sim, enableAllocationCost);
    baseConfig.setAllocator(allocator);
    List<Config<GenericPoolable>> result = new LinkedList<>();
    addConfigPermutations(head, baseConfig, result);
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
        assert allocationTimeUnit == null: "Found more than one @AllocationCost method";
        allocationTimeUnit = allocationCost.value();
        allocationTimeMethod = method;
      }
    }

    if (allocationTimeMethod == null) {
      return allocator();
    }

    final TimeUnit unit = allocationTimeUnit;
    final Method method = allocationTimeMethod;
    return allocator(alloc(new Action() {
      @Override
      public GenericPoolable apply(Slot slot, GenericPoolable obj) throws Exception {
        if (enableAllocationCost.get()) {
          Number time = (Number) method.invoke(sim);
          unit.sleep(time.longValue());
        }
        return $new.apply(slot, obj);
      }
    }));
  }

  private static void addConfigPermutations(
      Link<Map.Entry<Param, List<Object>>> head,
      Config<GenericPoolable> baseConfig,
      List<Config<GenericPoolable>> result) throws Exception {
    if (head == null) {
      // Base case
      result.add(baseConfig);
    } else {
      // Reduction
      Param param = head.value.getKey();
      List<Object> values = head.value.getValue();

      for (Object value : values) {
        if (value.getClass().isArray()) {
          int length = Array.getLength(value);
          for (int i = 0; i < length; i++) {
            Config<GenericPoolable> mutation = baseConfig.clone();
            param.set(mutation, Array.get(value, i));
            addConfigPermutations(head.tail, mutation, result);
          }
        } else {
          Config<GenericPoolable> mutation = baseConfig.clone();
          param.set(mutation, value);
          addConfigPermutations(head.tail, mutation, result);
        }
      }
    }
  }

  private static void simulate(
      Sim sim,
      Constructor<Pool<GenericPoolable>> ctor,
      Config<GenericPoolable> config,
      AtomicBoolean enableAllocationCost,
      Output output) throws Exception {
    DependencyResolver deps = new DependencyResolver();
    deps.add(config);
    deps.add(config.getAllocator());
    deps.add(config.getExpiration());
    deps.add(config.getMetricsRecorder());
    deps.add(config.getThreadFactory());
    deps.add(output);

    String poolTypeName = ctor.getDeclaringClass().getSimpleName();
    System.out.printf("Simulating %s %s for %s%n",
        sim.getClass().getSimpleName(), describe(config), poolTypeName);

    long measurementTimeMillis = getMeasurementTimeMillis(sim);
    int iterations = 100;
    long warmupTimeMillis = measurementTimeMillis / iterations;
    for (int i = 1; i <= iterations; i++) {
      boolean isWarmup = i < iterations;
      long runTime = isWarmup? warmupTimeMillis : measurementTimeMillis;

      // Reset internal state.
      enableAllocationCost.set(false);
      Pool<GenericPoolable> pool = ctor.newInstance(config);
      sim = sim.getClass().newInstance();
      deps.replace(pool);

      // Wait for the pool to boot up.
      int size = config.getSize();
      List<GenericPoolable> objs = new ArrayList<>();
      for (int j = 0; j < size; j++) {
        objs.add(pool.claim(new Timeout(30, TimeUnit.MINUTES)));
      }
      for (GenericPoolable obj : objs) {
        obj.release();
      }

      // Then run.
      enableAllocationCost.set(true);
      sim.run(deps, runTime, isWarmup? Output.none : output);

      // Finally clean up a bit.
      if (!pool.shutdown().await(SHUTDOWN_TIMEOUT)) {
        ManagedPool managedPool = (ManagedPool) pool;
        System.err.printf(
            "Shutdown timed out! Pool type = %s, config = %s, " +
                "object leaks detected = %s%n",
            poolTypeName, describe(config), managedPool.getLeakedObjectsCount());
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

  private static String describe(Config<GenericPoolable> config) {
    int size = config.getSize();
    Expiration<? super GenericPoolable> expiration = config.getExpiration();
    boolean backgroundExpirationEnabled = config.isBackgroundExpirationEnabled();
    boolean preciseLeakDetectionEnabled = config.isPreciseLeakDetectionEnabled();
    MetricsRecorder metricsRecorder = config.getMetricsRecorder();
    ThreadFactory threadFactory = config.getThreadFactory();
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

    public AgentRunner(
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

    public void printResults() {
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

    public IterationPause(AgentPause agentPause, Sim sim, Method pauseMethod) {
      this.sim = sim;
      this.pauseMethod = pauseMethod;
      this.pauseUnit = agentPause.unit();
    }

    public long pause(long spentTimeMicros) throws Exception {
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

    public void awaitStart() throws InterruptedException {
      startLatch.await();
    }

    public void start() {
      startLatch.countDown();
    }

    public boolean stopped() {
      return stopped;
    }

    public void stop() {
      stopped = true;
    }

    public void done() {
      doneCounter.release();
    }

    public void awaitDone(int doneCounts) throws InterruptedException {
      doneCounter.acquire(doneCounts);
    }
  }

  private static class DependencyResolver {
    private final List<Object> dependencies;

    private DependencyResolver() {
      dependencies = new ArrayList<>();
    }

    public void add(Object obj) {
      if (obj != null) {
        dependencies.add(obj);
      }
    }

    public Object[] resolve(Class<?>[] types) {
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

    public void replace(Object obj) {
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
