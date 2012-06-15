package stormpot.benchmark;

import java.util.Properties;

public class Main {
  public static void main(String[] args) throws Exception {
    /*
     * NOTE:
     * Remember to add pass-through properties to the pom.xml file,
     * when adding properties here!
     */
    if (prop("help")) {
      explain("throughput-single", "Single-threaded throughput");
    } else if (prop("throughput-single")) {
      Throughput.main(args);
    } else {
      System.out.println("Run `mvn -Dhelp` for options.");
    }
  }

  private static boolean prop(String prop) {
    Properties props = System.getProperties();
    return Boolean.parseBoolean(props.getProperty(prop, "false"));
  }

  private static void explain(String name, String desc) {
    System.out.printf("Run `mvn -D%s` for a %s benchmark.\n", name, desc);
  }
}
