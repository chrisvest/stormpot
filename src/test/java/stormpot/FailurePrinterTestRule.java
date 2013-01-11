package stormpot;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/**
 * A TestRule that ensures that any failing tests have their stack-trace
 * printed to stderr.
 * 
 * This is useful for when the tests are running on a build-server, and you'd
 * like the details of any failure to be printed to the build-log. A nice thing
 * if the build system does not make the build artifacts with the test failures
 * available.
 * 
 * @author cvh
 */
public class FailurePrinterTestRule implements TestRule {
  @Override
  public Statement apply(final Statement base, final Description description) {
    return new Statement() {
      @Override
      public void evaluate() throws Throwable {
        try {
          base.evaluate();
        } catch (Throwable th) {
          th.printStackTrace();
          throw th;
        }
      }
    };
  }
}
