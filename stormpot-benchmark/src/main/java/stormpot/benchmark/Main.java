package stormpot.benchmark;

public class Main {
  public static void main(String[] args) throws Exception {
    /*
     * NOTE:
     * Remember to add pass-through properties to the pom.xml file,
     * when adding properties here!
     */
    Benchmark benchmark = null;
    if (prop("help")) {
      System.out.print(
          "#######################################\n" +
          "# How to use the benchmark tool:\n");
      explain("throughput-single", "Single-threaded throughput");
      explain("throughput-multi", "Multi-threaded throughput");
      System.out.print("#\n# Additionally, the following options may be " +
      		"given to any benchmark:\n");
      explainAuxiliary("pools=queue,stack,generic",
          "Comma-separated list of pools to benchmark.");
      explainAuxiliary("pool.size=10",
          "Number of objects to initialise the pools with.");
      explainAuxiliary("trial.time=500",
          "For how long to run the benchmarks, in milliseconds.");
      explainAuxiliary("obj.ttl=300000",
          "For how long the objects in the pools should be kept live.");
      explainAuxiliary("report.msg=\"...\"",
          "The results reporting message format.");
      explainAuxiliary("thread.count=<core-count>",
          "The number of worker threads used in multi-threaded benchmarks.");
      System.out.print(
          "#######################################\n");
      return;
    }
    if (prop("throughput-single")) {
      benchmark = new SingleThreadedThroughputBenchmark();
    } else if (prop("throughput-multi")) {
      benchmark = new MultiThreadedThroughputBenchmark();
    }
    
    if (benchmark != null) {
      benchmark.run();
    } else {
      System.out.print(
          "#######################################\n" +
          "#### Run `mvn -Phelp` for options. ####\n" +
          "#######################################\n");
    }
    System.exit(0);
  }

  private static boolean prop(String prop) {
    return Boolean.getBoolean(prop);
  }

  private static void explain(String name, String desc) {
    System.out.printf("#   Run `mvn -D%s` for a %s benchmark.\n", name, desc);
  }

  private static void explainAuxiliary(String name, String desc) {
    System.out.printf("#   -D%s\n#\t%s\n", name, desc);
  }
}
