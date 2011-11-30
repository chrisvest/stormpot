package stormpot;

import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;

import stormpot.qpool.QPoolFixture;

public class JpfRunner {
  public static void main(String[] args) throws Throwable {
	  runOnePoolTest();
//    runAllPoolTests();
  }

  private static void runOnePoolTest() throws Exception {
    PoolFixture fixture = new QPoolFixture();
    PoolTest test = new PoolTest();
    test.setUp();
    test.mustPropagateExceptionsFromAllocateThroughClaim(fixture);
//    test.mustDeallocateAllPoolablesBeforeShutdownTaskReturns(fixture);
  }

  private static void runAllPoolTests() {
    Result result = JUnitCore.runClasses(PoolTest.class);
    report(result);
    System.exit(result.getFailureCount() > 0 ? 1 : 0);
  }

  private static void report(Result result) {
    System.out.printf("Done. Tests: %s. Failures: %s\n",
        result.getRunCount(), result.getFailureCount());
    
    for (Failure failure : result.getFailures()) {
      System.err.printf("Failure: %s\n%s\n%s\n---\n",
          failure.getTestHeader(),
          failure.getDescription(),
          failure.getTrace());
    }
  }
}
