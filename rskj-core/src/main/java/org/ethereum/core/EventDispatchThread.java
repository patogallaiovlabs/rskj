/*
 * This file is part of RskJ
 * Copyright (C) 2017 RSK Labs Ltd.
 * (derived from ethereumJ library, Copyright (c) 2016 <ether.camp>)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.ethereum.core;

import co.rsk.panic.PanicProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The class intended to serve as an 'Event Bus' where all EthereumJ events are
 * dispatched asynchronously from component to component or from components to
 * the user event handlers.
 *
 * This made for decoupling different components which are intended to work
 * asynchronously and to avoid complex synchronisation and deadlocks between them
 *
 * Created by Anton Nashatyrev on 29.12.2015.
 */
public class EventDispatchThread {
    private static final Logger logger = LoggerFactory.getLogger("blockchain");
    private static final String THREAD_NAME = "event-dispatch-thread";
    private static final int QUEUE_CAPACITY = 2_048;
    private static final int WARN_QUEUE_SIZE = 1_536;
    private static final long WARN_LOG_INTERVAL_MILLIS = 10_000L;

    private static volatile PanicProcessor panicProcessor = new PanicProcessor();
    private static final AtomicLong lastWarnLogMillis = new AtomicLong(0L);

    private static final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            runnable -> new Thread(runnable, THREAD_NAME),
            new ThreadPoolExecutor.CallerRunsPolicy()
    );

    private EventDispatchThread() {
        // utility class can't be instantiated
    }

    public static void invokeLater(final Runnable r) {
        Objects.requireNonNull(r, "runnable must not be null");

        int queueSize = executor.getQueue().size();
        long nowMillis = System.currentTimeMillis();
        long lastWarnMillis = lastWarnLogMillis.get();
        if (queueSize >= WARN_QUEUE_SIZE && nowMillis - lastWarnMillis >= WARN_LOG_INTERVAL_MILLIS && lastWarnLogMillis.compareAndSet(lastWarnMillis, nowMillis)) {
            logger.warn(
                    "EventDispatchThread queue is large: queueSize={}, capacity={}, active={}, completed={}, taskCount={}",
                    queueSize,
                    QUEUE_CAPACITY,
                    executor.getActiveCount(),
                    executor.getCompletedTaskCount(),
                    executor.getTaskCount()
            );
        }

        executor.execute(() -> {
            try {
                r.run();
            } catch (Exception e) {
                logger.error("EDT task exception", e);
                panicProcessor.panic("thread", String.format("EDT task exception %s", e.getMessage()));
            }
        });
    }

    public static int getQueueSize() {
        return executor.getQueue().size();
    }

    public static int getQueueCapacity() {
        return QUEUE_CAPACITY;
    }

    static boolean awaitQuiescence(long timeout, TimeUnit unit) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        invokeLater(latch::countDown);
        return latch.await(timeout, unit);
    }

    static void setPanicProcessorForTests(PanicProcessor processor) {
        panicProcessor = Objects.requireNonNull(processor, "processor must not be null");
    }

    static void resetPanicProcessorForTests() {
        panicProcessor = new PanicProcessor();
    }
}
