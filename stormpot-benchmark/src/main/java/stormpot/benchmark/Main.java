package stormpot.benchmark;

import java.util.Properties;

public class Main {
  public static void main(String[] args) throws Exception {
    /*
     * NOTE:
     * Remember to add pass-through properties to the pom.xml file,
     * when adding properties here!
     */
    Benchmark benchmark = null;
    if (prop("help")) {
      explain("throughput-single", "Single-threaded throughput");
      return;
    }
    if (prop("throughput-single")) {
      benchmark = new SingleThreadedThroughput();
    }
    
    if (benchmark != null) {
      benchmark.run();
    } else {
      System.out.println("Run `mvn -Dhelp` for options.");
    }
    System.exit(0);
  }

  private static boolean prop(String prop) {
    Properties props = System.getProperties();
    return Boolean.parseBoolean(props.getProperty(prop, "false"));
  }

  private static void explain(String name, String desc) {
    System.out.printf("Run `mvn -D%s` for a %s benchmark.\n", name, desc);
  }
}
