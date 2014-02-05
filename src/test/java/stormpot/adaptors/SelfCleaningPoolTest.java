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
package stormpot.adaptors;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import stormpot.PoolException;
import stormpot.Poolable;
import stormpot.Timeout;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.*;

/**
 * SelfCleaningPoolTest
 *
 * @author Simon.Gibbs
 */
@RunWith(MockitoJUnitRunner.class)
public class SelfCleaningPoolTest {

    public static final int POOL_SIZE = 5;
    @Mock
    stormpot.LifecycledResizablePool<Poolable> mockPool;

    Timeout exampleTimeout = new Timeout(10, TimeUnit.MILLISECONDS);
    RuntimeException exampleException = new RuntimeException("Mock exception");

    private SelfCleaningPool<Poolable> cleaningPool;

    private static ScheduledExecutorService threadPool = Executors.newScheduledThreadPool(2);

    @Before
    public void setUpFailingPool() throws InterruptedException {

        // a self-cleaning pool, configured to work faster than normal
        cleaningPool = new SelfCleaningPool<Poolable>(mockPool,threadPool,1,RuntimeException.class);

        when(mockPool.getTargetSize()).thenReturn(POOL_SIZE);
        when(mockPool.claim(any(Timeout.class))).thenThrow(new PoolException("Example Poison",exampleException));
    }

    @AfterClass
    public static void stopWorkerThreads() {
        threadPool.shutdown();
    }

    @Test
    public void shouldClaimTargetSizeConnectionsShortlyAfterARecoverableException() throws InterruptedException {

        try {
            cleaningPool.claim(exampleTimeout);
            fail("Expected exception");
        } catch (PoolException e) {
            assertEquals(e.getCause(),exampleException);
        }

        Thread.sleep(1100); // the SIT is configured to dredge quickly

        verify(mockPool,times(POOL_SIZE+1)).claim(any(Timeout.class));

    }

    @Test
    public void shouldNotClaimTargetSizeConnectionsShortlyAfterANonRecoverableException() throws InterruptedException {
    	// a self-cleaning pool, configured to work faster than normal
        cleaningPool = new SelfCleaningPool<Poolable>(mockPool,threadPool,1,IllegalArgumentException.class);
        try {
            cleaningPool.claim(exampleTimeout); // will throw RuntimeException
            fail("Expected exception");
        } catch (PoolException e) {
            assertEquals(e.getCause(),exampleException);
        }

        Thread.sleep(1100); // the SIT is configured to dredge quickly

        verify(mockPool,times(1)).claim(any(Timeout.class)); // just the one claim above

    }

    @Test
    public void shouldNotDredgeMoreThanOncePerDredgePeriod() throws InterruptedException {

        // Request very many claims
        int veryMany = POOL_SIZE*4;
        for(int i=0;i<veryMany;i++) {
            try {
                cleaningPool.claim(exampleTimeout);
                fail("Expected exception");
            } catch (PoolException e) {
                assertEquals(e.getCause(),exampleException);
            }
        }

        Thread.sleep(1100);

        // Expect the number we requested, and a number from the dredger equal to POOL_SIZE
        verify(mockPool,times(veryMany+POOL_SIZE)).claim(any(Timeout.class));

    }

}
