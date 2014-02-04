package stormpot;

import org.junit.Before;
import org.junit.Test;
import stormpot.bpool.BlazePool;
import stormpot.qpool.QueuePool;

import java.util.HashSet;
import java.util.Set;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;

/**
 * TimingProblemsTest
 *
 * @author Simon.Gibbs
 */
public class TimingProblemsTest {


    private static class SlowAllocator implements Allocator<GenericPoolable> {

        private final long allocationTime;

        SlowAllocator(long allocationTime) {
            this.allocationTime = allocationTime;
        }

        @Override
        public GenericPoolable allocate(Slot slot) throws Exception {
            Thread.sleep(allocationTime);
            return new GenericPoolable(slot);
        }

        @Override
        public void deallocate(GenericPoolable poolable) throws Exception {
            // nothing to do
        }
    }

    Set<Thread> initialThreadSet = null;

    @Before
    public void takeThreadsSnapshot() {
        initialThreadSet = Thread.getAllStackTraces().keySet();
    }


    @Test(timeout = 1000)
    public void blazePoolShouldShutdownImmediatelyEvenIfItIsStillAllocatingPoolables() throws InterruptedException {


        Config<GenericPoolable> config = new Config<GenericPoolable>().setAllocator(new SlowAllocator(20));

        BlazePool<GenericPoolable> pool = new BlazePool<GenericPoolable>(config);
        pool.setTargetSize(100);

        Thread allocatorThread = findNewAllocatorThread("blazepool-allocator");
        assertNotNull(allocatorThread);

        pool.shutdown();

        Thread.sleep(20);

        assertThat(allocatorThread.getState(),is(Thread.State.TERMINATED));


    }

    @Test(timeout = 1000)
    public void queuePoolShouldShutdownImmediatelyEvenIfItIsStillAllocatingPoolables() throws InterruptedException {

        Config<GenericPoolable> config = new Config<GenericPoolable>().setAllocator(new SlowAllocator(20));

        QueuePool<GenericPoolable> pool = new QueuePool<GenericPoolable>(config);
        pool.setTargetSize(100);

        Thread allocatorThread = findNewAllocatorThread("qpool-allocator");
        assertNotNull(allocatorThread);

        pool.shutdown();

        Thread.sleep(20);

        assertThat(allocatorThread.getState(),is(Thread.State.TERMINATED));


    }

    private Thread findNewAllocatorThread(String namedLike) {
        Set<Thread> newThreads = new HashSet<Thread>(Thread.getAllStackTraces().keySet());
        newThreads.removeAll(initialThreadSet);

        for(Thread candidate : newThreads) {
            if(candidate.getName().startsWith(namedLike)) {
               return candidate;
            }
        }

        return null;
    }




}
