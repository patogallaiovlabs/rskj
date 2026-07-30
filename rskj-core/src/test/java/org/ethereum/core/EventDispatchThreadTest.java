/*
 * This file is part of RskJ
 * Copyright (C) 2017 RSK Labs Ltd.
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

class EventDispatchThreadTest {

    @AfterEach
    void tearDown() throws InterruptedException {
        EventDispatchThread.resetPanicProcessorForTests();
        Assertions.assertTrue(EventDispatchThread.awaitQuiescence(5, TimeUnit.SECONDS));
    }

    @Test
    void queueIsBoundedWhenProducerOutrunsConsumer() throws InterruptedException {
        int capacity = EventDispatchThread.getQueueCapacity();

        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);

        EventDispatchThread.invokeLater(() -> {
            workerStarted.countDown();
            awaitLatch(releaseWorker);
        });

        Assertions.assertTrue(workerStarted.await(2, TimeUnit.SECONDS));

        for (int i = 0; i < capacity + 200; i++) {
            EventDispatchThread.invokeLater(() -> {
            });
        }

        Assertions.assertTrue(EventDispatchThread.getQueueSize() <= capacity);

        releaseWorker.countDown();
        Assertions.assertTrue(EventDispatchThread.awaitQuiescence(5, TimeUnit.SECONDS));
    }

    @Test
    void callerRunsPolicyAppliesWhenQueueIsFull() throws InterruptedException {
        int capacity = EventDispatchThread.getQueueCapacity();

        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);

        EventDispatchThread.invokeLater(() -> {
            workerStarted.countDown();
            awaitLatch(releaseWorker);
        });

        Assertions.assertTrue(workerStarted.await(2, TimeUnit.SECONDS));

        for (int i = 0; i < capacity; i++) {
            EventDispatchThread.invokeLater(() -> {
            });
        }

        AtomicReference<String> executedByThread = new AtomicReference<>();
        String callerThread = Thread.currentThread().getName();

        EventDispatchThread.invokeLater(() -> executedByThread.set(Thread.currentThread().getName()));

        Assertions.assertEquals(callerThread, executedByThread.get());
        Assertions.assertEquals(capacity, EventDispatchThread.getQueueSize());

        releaseWorker.countDown();
        Assertions.assertTrue(EventDispatchThread.awaitQuiescence(5, TimeUnit.SECONDS));
    }

    @Test
    void invokeLaterCatchesExceptionAndCallsPanicProcessor() throws InterruptedException {
        PanicProcessor panicProcessor = mock(PanicProcessor.class);
        EventDispatchThread.setPanicProcessorForTests(panicProcessor);

        EventDispatchThread.invokeLater(() -> {
            throw new IllegalStateException("boom");
        });

        verify(panicProcessor, timeout(2_000)).panic(eq("thread"), contains("boom"));
        Assertions.assertTrue(EventDispatchThread.awaitQuiescence(5, TimeUnit.SECONDS));
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            boolean completed = latch.await(5, TimeUnit.SECONDS);
            Assertions.assertTrue(completed);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}


