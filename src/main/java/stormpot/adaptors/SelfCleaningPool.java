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

import stormpot.Completion;
import stormpot.LifecycledResizablePool;
import stormpot.PoolException;
import stormpot.Poolable;
import stormpot.Timeout;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * <p>Removes bad Poolable's from the pool in the event the <code>pool.claim()</code> method begins
 * to throw exceptions. For systems with infrequent high-value transactions, this allows clients
 * to quickly move on from outages that cause the whole pool to become poisoned.</p>
 *
 * <p>Cleaning is performed by claiming and releasing Poolables such that any cached exceptions
 * are removed from Slots (see {@link stormpot.bpool.BSlot#poison})</p>
 *
 * <p>Excess load and log noise are avoided by scheduling an asynchronous "dredge" not more than every
 * {@link SelfCleaningPool#DREDGE_PERIOD DREDGE_PERIOD} seconds, and by white listing recoverable exception
 * types.</p>
 *
 * <p>Exceptions, once cleared, are logged and discarded.</p>
 *
 * @author Simon Gibbs
 */
public class SelfCleaningPool<T extends Poolable> implements LifecycledResizablePool<T> {


    private static final Logger LOGGER = Logger.getLogger(SelfCleaningPool.class.getName());

    private static final long DREDGE_PERIOD = 30;

    private final LifecycledResizablePool<T> implementation;
	private final ScheduledExecutorService executorService;
	private final Set<Class<? extends Throwable>> recoverableExceptions;
    private final long dredgeDelay;

    private boolean dredging = false;

	private final Runnable dredgePool = new Runnable() {

		private final Timeout claimTimeout = new Timeout(500,TimeUnit.MILLISECONDS);

		@Override
		public void run() {

			for(int i =0; i<implementation.getTargetSize();i++) {
				T poolable = null;
				try {
					poolable = implementation.claim(claimTimeout);
				} catch (InterruptedException ie) {
                    LOGGER.info("Dredging interrupted at poolable " + i);
                    dredging = false;
                    break;
				} catch(Throwable t) {
					LOGGER.log(Level.WARNING, "Pool is still producing exceptions", t);
				} finally {
					if(poolable!=null) {
						poolable.release();
					}
				}
			}
			dredging = false;
		}
	};

	/**
	 * <p>Adapts a {@link stormpot.LifecycledResizablePool} to add self-cleaning behavior when expected exception
	 * types are thrown from <code>implementation.claim(to)</code></p>
     *
     * <p>In order to avoid the inheritance of {@link InheritableThreadLocal} state from client threads,
     * the worker thread used for clean up tasks will be spawned immediately.</p>
     *
	 * @param implementation the adapted {@link stormpot.LifecycledResizablePool} implementation
     * @param dredgeDelay number of seconds to wait before dredging out bad connections
	 * @param recoverableExceptionTypes white listed exception types that will be logged and discarded
	 */
	@SafeVarargs
	public SelfCleaningPool(LifecycledResizablePool<T> implementation, ScheduledExecutorService executorService, long dredgeDelay, Class<? extends Throwable>... recoverableExceptionTypes) {
		this.implementation = implementation;
		this.executorService = executorService;

                /* Submit a task to force the executorService to spawn it's threads now.
         * See JavaDoc for rationale and links.
         */
        this.executorService.execute(new Runnable() {
            @Override
            public void run() {
                // just some helpful logging, an empty Runnable would have done.
                LOGGER.fine("Pool cleanup thread initialised");
            }
        });

		this.recoverableExceptions = new HashSet<Class<? extends Throwable>>( recoverableExceptionTypes.length);
		for(Class<? extends Throwable> type : recoverableExceptionTypes) {
			recoverableExceptions.add(type);
		}
        this.dredgeDelay = dredgeDelay;
	}

    @SafeVarargs
    public SelfCleaningPool(LifecycledResizablePool<T> implementation, ScheduledExecutorService executorService, Class<? extends Throwable>... recoverableExceptionTypes) {
        this(implementation, executorService, DREDGE_PERIOD, recoverableExceptionTypes);
    }

	@Override
	public Completion shutdown() {
        return implementation.shutdown();
	}

	@Override
	public void setTargetSize(int i) {
		implementation.setTargetSize(i);
	}

	@Override
	public int getTargetSize() {
		return implementation.getTargetSize();
	}

	@Override
	public T claim(Timeout timeout) throws PoolException, InterruptedException {
		try {
			return implementation.claim(timeout);
		} catch (PoolException pe) {
			if(!dredging && expectedException(pe)) {
				dredging = true;
				executorService.schedule(dredgePool, dredgeDelay, TimeUnit.SECONDS);
                LOGGER.log(Level.INFO,"Dredging scheduled for recoverable exception",pe);
			}
			throw pe;
		}
	}

	private boolean expectedException(PoolException pe) {
		Set<Throwable> causes = findPossibleRootCauses(pe);
		for(Throwable possibleCause : causes) {
			Class<? extends Throwable> causeClass = possibleCause.getClass();
			if(recoverableExceptions.contains(causeClass)) {
		 		return true;
			}
		}
		return false;
	}

	private Set<Throwable> findPossibleRootCauses(Throwable proximateCause) {
		Set<Throwable> causes = new HashSet<Throwable>(2);
		causes.add(proximateCause);
		causes.add(proximateCause.getCause());

		return causes;
	}
}
