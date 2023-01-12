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


import org.junit.After;
import org.junit.Test;


import java.util.concurrent.TimeUnit;

import static com.hazelcast.internal.tpc.TpcEngine.State.SHUTDOWN;
import static com.hazelcast.internal.tpc.TpcEngine.State.TERMINATED;
import static com.hazelcast.internal.tpc.TpcTestSupport.sleepMillis;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;


public class TpcEngineTest {

    private TpcEngine engine;

    @After
    public void after() {
        if (engine != null) {
            engine.shutdown();
        }
    }

    @Test
    public void test() {
        Configuration configuration = new Configuration();
        int eventloopCount = 5;
        configuration.setEventloopCount(eventloopCount);

        engine = new TpcEngine(configuration);

        assertEquals(5, engine.eventloops().length);
        assertEquals(eventloopCount, engine.eventloopCount());
        assertEquals(EventloopType.NIO, engine.eventloopType());
    }

    // ===================== start =======================

    @Test
    public void start_whenNew() {
        engine = new TpcEngine();
        engine.start();
        assertEquals(TpcEngine.State.RUNNING, engine.state());
    }

    @Test(expected = IllegalStateException.class)
    public void start_whenRunning() {
        engine = new TpcEngine();
        engine.start();
        engine.start();
    }

    // ================= shut down =======================

    @Test
    public void shutdown_whenNew() {
        engine = new TpcEngine();
        engine.shutdown();
        assertEquals(TERMINATED, engine.state());
    }

    @Test
    public void shutdown_whenRunning() throws InterruptedException {
        engine = new TpcEngine();
        engine.start();
        engine.eventloop(0).offer(() -> {
            sleepMillis(1000);
        });
        engine.shutdown();
        assertEquals(SHUTDOWN, engine.state());
        assertTrue(engine.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(TERMINATED, engine.state());
    }

    @Test
    public void shutdown_whenShutdown() throws InterruptedException {
        engine = new TpcEngine();
        engine.start();
        engine.eventloop(0).offer(() -> {
            sleepMillis(1000);
        });
        engine.shutdown();

        engine.shutdown();
        assertEquals(SHUTDOWN, engine.state());
        assertTrue(engine.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(TERMINATED, engine.state());
    }

    @Test
    public void shutdown_whenTerminated() {
        engine = new TpcEngine();
        engine.shutdown();

        engine.shutdown();
        assertEquals(TERMINATED, engine.state());
    }
}
