/*
 * Copyright (c) 2008-2022, Hazelcast, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hazelcast.internal.tpc;


import com.hazelcast.internal.tpc.iobuffer.IOBuffer;
import com.hazelcast.internal.tpc.logging.TpcLogger;
import com.hazelcast.internal.tpc.logging.TpcLoggerLocator;
import com.hazelcast.internal.tpc.util.BoundPriorityQueue;
import com.hazelcast.internal.tpc.util.CircularQueue;
import com.hazelcast.internal.util.ThreadAffinityHelper;
import org.jctools.queues.MpmcArrayQueue;

import java.util.BitSet;
import java.util.PriorityQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import static com.hazelcast.internal.tpc.Eventloop.State.NEW;
import static com.hazelcast.internal.tpc.Eventloop.State.RUNNING;
import static com.hazelcast.internal.tpc.Eventloop.State.SHUTDOWN;
import static com.hazelcast.internal.tpc.Eventloop.State.TERMINATED;
import static java.util.concurrent.atomic.AtomicReferenceFieldUpdater.newUpdater;

/**
 * A EventLoop is a loop that processes events.
 * <p/>
 * There are various forms of events:
 * <ol>
 *     <li>Concurrency tasks: tasks that are offered by other threads</li>
 *     <li>Local tasks: tasks that have been generated by the eventloop itself</li>
 *     <li>Scheduled tasks: tasks that have been scheduled by the eventloop</li>
 *     <li>Tasks from some asynchronous eventing system that interacts with I/O. </li>>
 * </ol>
 */
@SuppressWarnings({"checkstyle:VisibilityModifier", "checkstyle:declarationorder", "rawtypes"})
public abstract class Eventloop implements Executor {

    protected static final AtomicReferenceFieldUpdater<Eventloop, State> STATE
            = newUpdater(Eventloop.class, State.class, "state");

    private static final int INITIAL_ALLOCATOR_CAPACITY = 1 << 10;
    private static final Runnable SHUTDOWN_TASK = () -> {
    };

    /**
     * Allows for objects to be bound to this Eventloop. Useful for the lookup of services and other dependencies.
     */
    public final ConcurrentMap<?, ?> context = new ConcurrentHashMap<>();

    public final CircularQueue<Runnable> localTaskQueue;

    protected final PriorityQueue<ScheduledTask> scheduledTaskQueue;

    protected final TpcLogger logger = TpcLoggerLocator.getLogger(getClass());

    protected final AtomicBoolean wakeupNeeded = new AtomicBoolean(true);
    protected final MpmcArrayQueue concurrentTaskQueue;

    protected final Scheduler scheduler;
    protected final boolean spin;
    final int clockRefreshInterval;

    protected Unsafe unsafe;
    protected final Thread eventloopThread;
    protected volatile State state = NEW;
    protected long earliestDeadlineNanos = -1;

    TpcEngine engine;

    final PromiseAllocator promiseAllocator;
    private final EventloopType type;
    private final BitSet allowedCpus;
    private final CountDownLatch terminationLatch = new CountDownLatch(1);
    private final int batchSize;

    /**
     * Creates a new {@link Eventloop}.
     *
     * @param eventloopBuilder the {@link EventloopBuilder} uses to create this {@link Eventloop}.
     * @throws NullPointerException if eventloopBuilder is null.
     */
    protected Eventloop(EventloopBuilder eventloopBuilder) {
        this.type = eventloopBuilder.type;
        this.clockRefreshInterval = eventloopBuilder.clockRefreshPeriod;
        this.spin = eventloopBuilder.spin;
        this.batchSize = eventloopBuilder.batchSize;
        this.scheduler = eventloopBuilder.schedulerSupplier.get();
        scheduler.init(this);
        this.localTaskQueue = new CircularQueue<>(eventloopBuilder.localTaskQueueCapacity);
        this.concurrentTaskQueue = new MpmcArrayQueue(eventloopBuilder.concurrentTaskQueueCapacity);
        this.scheduledTaskQueue = new BoundPriorityQueue<>(eventloopBuilder.scheduledTaskQueueCapacity);
        this.eventloopThread = eventloopBuilder.threadFactory.newThread(new EventloopTask());
        if (eventloopBuilder.threadNameSupplier != null) {
            eventloopThread.setName(eventloopBuilder.threadNameSupplier.get());
        }

        this.allowedCpus = eventloopBuilder.threadAffinity == null ? null : eventloopBuilder.threadAffinity.nextAllowedCpus();
        this.promiseAllocator = new PromiseAllocator(this, INITIAL_ALLOCATOR_CAPACITY);
    }

    /**
     * Returns the {@link EventloopType} of this {@link Eventloop}.
     * <p>
     * This method is thread-safe.
     *
     * @return the {@link EventloopType} of this {@link Eventloop}. Value will never be null.
     */
    public final EventloopType type() {
        return type;
    }

    /**
     * Gets the Unsafe instance for this Eventloop.
     * <p>
     * This only be called by the Eventloop thread itself.
     *
     * @return the Unsafe instance.
     */
    public final Unsafe unsafe() {
        return unsafe;
    }

    /**
     * Returns the {Scheduler} for this {@link Eventloop}.
     * <p>
     * This method is thread-safe.
     *
     * @return the {@link Scheduler}.
     */
    public Scheduler scheduler() {
        return scheduler;
    }

    /**
     * Returns the {@link Thread} that runs this {@link Eventloop}.
     * <p>
     * This method is thread-safe.
     *
     * @return the EventloopThread.
     */
    public Thread eventloopThread() {
        return eventloopThread;
    }

    /**
     * Returns the state of the Eventloop.
     * <p>
     * This method is thread-safe.
     *
     * @return the state.
     */
    public final State state() {
        return state;
    }

    /**
     * Opens an TCP/IP (stream) async server socket and ties that socket to the this Eventloop
     * instance. The returned socket assumes IPv4. When support for IPv6 is added, a boolean
     * 'ipv4' flag needs to be added.
     * <p>
     * This method is thread-safe.
     *
     * @return the opened AsyncServerSocket.
     */
    public abstract AsyncServerSocket openTcpAsyncServerSocket();

    /**
     * Opens TCP/IP (stream) based async socket. The returned socket assumes IPv4. When support for
     * IPv6 is added, a boolean 'ipv4' flag needs to be added.
     * <p/>
     * The opened AsyncSocket isn't tied to this Eventloop. After it is opened, it needs to be assigned
     * to a particular eventloop by calling {@link AsyncSocket#activate(Eventloop)}. The reason why
     * this isn't done in 1 go, is that it could be that when the AsyncServerSocket accepts an
     * AsyncSocket, we want to assign that AsyncSocket to a different Eventloop. Otherwise if there
     * would be 1 AsyncServerSocket, connected AsyncSockets can only run on top of the Eventloop of
     * the AsyncServerSocket instead of being distributed over multiple eventloops.
     * <p>
     * This method is thread-safe.
     *
     * @return the opened AsyncSocket.
     */
    public abstract AsyncSocket openTcpAsyncSocket();

    /**
     * Creates the Eventloop specific Unsafe instance.
     *
     * @return the create Unsafe instance.
     */
    protected abstract Unsafe createUnsafe();

    /**
     * Is called before the {@link #eventLoop()} is called.
     * <p>
     * This method can be used to initialize resources.
     * <p>
     * Is called from the eventloop thread.
     */
    protected void beforeEventloop() throws Exception {
    }

    /**
     * Executes the actual eventloop.
     *
     * @throws Exception
     */
    protected abstract void eventLoop() throws Exception;

    /**
     * Is called after the {@link #eventLoop()} is called.
     * <p>
     * This method can be used to cleanup resources.
     * <p>
     * Is called from the eventloop thread.
     */
    protected void afterEventloop() throws Exception {
    }

    /**
     * Starts the eventloop.
     *
     * @throws IllegalStateException if the Eventloop isn't in NEW state.
     */
    public void start() {
        if (!STATE.compareAndSet(Eventloop.this, NEW, RUNNING)) {
            throw new IllegalStateException("Can't start eventLoop, invalid state:" + state);
        }

        eventloopThread.start();
    }

    /**
     * Shuts down the Eventloop.
     * <p>
     * This call can safely be made no matter the state of the Eventloop.
     * <p>
     * This method is thread-safe.
     */
    public final void shutdown() {
        for (; ; ) {
            State oldState = state;
            switch (oldState) {
                case NEW:
                    if (STATE.compareAndSet(this, oldState, TERMINATED)) {
                        terminationLatch.countDown();
                        if (engine != null) {
                            engine.notifyEventloopTerminated();
                        }
                        return;
                    }

                    break;
                case RUNNING:
                    if (STATE.compareAndSet(this, oldState, SHUTDOWN)) {
                        concurrentTaskQueue.add(SHUTDOWN_TASK);
                        wakeup();
                        return;
                    }
                    break;
                default:
                    return;
            }
        }
    }

    /**
     * Awaits for the termination of the Eventloop with the given timeout.
     *
     * @param timeout the timeout
     * @param unit    the TimeUnit
     * @return true if the Eventloop is terminated.
     * @throws InterruptedException if the thread was interrupted while waiting.
     */
    public final boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        terminationLatch.await(timeout, unit);
        return state == TERMINATED;
    }

    /**
     * Wakes up the {@link Eventloop} when it is blocked and needs to be woken up.
     */
    protected abstract void wakeup();

    @Override
    public void execute(Runnable command) {
        if (!offer(command)) {
            throw new RejectedExecutionException("Task " + command.toString()
                    + " rejected from " + this);
        }
    }

    /**
     * Offers a task to be executed on this {@link Eventloop}.
     *
     * @param task the task to execute.
     * @return true if the task was accepted, false otherwise.
     * @throws NullPointerException if task is null.
     */
    public final boolean offer(Runnable task) {
        if (Thread.currentThread() == eventloopThread) {
            return localTaskQueue.offer(task);
        } else if (concurrentTaskQueue.offer(task)) {
            wakeup();
            return true;
        } else {
            return false;
        }
    }

    /**
     * Offers an {@link IOBuffer} to be processed by this {@link Eventloop}.
     *
     * @param buff the {@link IOBuffer} to process.
     * @return true if the buffer was accepted, false otherwise.
     * @throws NullPointerException if buff is null.
     */
    public final boolean offer(IOBuffer buff) {
        //todo: Don't want to add localRunQueue optimization like the offer(Runnable)?

        if (concurrentTaskQueue.offer(buff)) {
            wakeup();
            return true;
        } else {
            return false;
        }
    }

    protected final boolean runScheduledTasks() {
        for (int k = 0; k < batchSize; k++) {
            ScheduledTask scheduledTask = scheduledTaskQueue.peek();
            if (scheduledTask == null) {
                return false;
            }

            if (scheduledTask.deadlineNanos > unsafe.nanoClock.nanoTime()) {
                // Task should not yet be executed.
                earliestDeadlineNanos = scheduledTask.deadlineNanos;
                // we are done since all other tasks have a larger deadline.
                return false;
            }

            scheduledTaskQueue.poll();
            earliestDeadlineNanos = -1;
            try {
                scheduledTask.run();
            } catch (Exception e) {
                logger.warning(e);
            }
        }

        return !scheduledTaskQueue.isEmpty();
    }

    protected final boolean runLocalTasks() {
        for (int k = 0; k < batchSize; k++) {
            Runnable task = localTaskQueue.poll();
            if (task == null) {
                // there are no more tasks.
                return false;
            } else {
                // there is a task, so lets execute it.
                try {
                    task.run();
                } catch (Exception e) {
                    logger.warning(e);
                }
            }
        }

        return !localTaskQueue.isEmpty();
    }

    protected boolean runConcurrentTasks() {
        for (int k = 0; k < batchSize; k++) {
            Object task = concurrentTaskQueue.poll();
            if (task == null) {
                // there are no more tasks
                return false;
            } else if (task instanceof Runnable) {
                // there is a task, so lets execute it.
                try {
                    ((Runnable) task).run();
                } catch (Exception e) {
                    logger.warning(e);
                }
            } else if (task instanceof IOBuffer) {
                scheduler.schedule((IOBuffer) task);
            } else {
                throw new RuntimeException("Unrecognized type:" + task.getClass());
            }
        }

        return !concurrentTaskQueue.isEmpty();
    }

    /**
     * The {@link Runnable} containing the actual eventloop logic and is executed by by the eventloop {@link Thread}.
     */
    private final class EventloopTask implements Runnable {

        @Override
        public void run() {
            try {
                configureAffinity();

                try {
                    try {
                        unsafe = createUnsafe();
                        beforeEventloop();
                        eventLoop();
                    } finally {
                        afterEventloop();
                    }
                } catch (Throwable e) {
                    logger.severe(e);
                } finally {
                    state = TERMINATED;

                    terminationLatch.countDown();

                    if (engine != null) {
                        engine.notifyEventloopTerminated();
                    }

                    if (logger.isInfoEnabled()) {
                        logger.info(Thread.currentThread().getName() + " terminated");
                    }
                }
            } catch (Throwable e) {
                // log whatever wasn't caught so that we don't swallow throwables.
                logger.severe(e);
            }
        }

        private void configureAffinity() {
            if (allowedCpus != null) {
                ThreadAffinityHelper.setAffinity(allowedCpus);
                BitSet actualCpus = ThreadAffinityHelper.getAffinity();
                if (!actualCpus.equals(allowedCpus)) {
                    logger.warning(Thread.currentThread().getName() + " affinity was not applied successfully. "
                            + "Expected CPUs:" + allowedCpus + ". Actual CPUs:" + actualCpus);
                } else {
                    if (logger.isFineEnabled()) {
                        logger.fine(Thread.currentThread().getName() + " has affinity for CPUs:" + allowedCpus);
                    }
                }
            }
        }
    }

    /**
     * The state of the {@link Eventloop}.
     */
    public enum State {
        NEW,
        RUNNING,
        SHUTDOWN,
        TERMINATED
    }
}
