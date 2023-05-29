/*
 * Copyright (c) 2008-2023, Hazelcast, Inc. All Rights Reserved.
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

package com.hazelcast.internal.tpcengine;

import java.util.Queue;

import static com.hazelcast.internal.tpcengine.util.Preconditions.checkNotNull;
import static com.hazelcast.internal.tpcengine.util.Preconditions.checkPositive;

public class SchedulingGroupBuilder {

    private final Eventloop eventloop;
    private String name;
    private int shares;
    private Queue<Object> queue;
    private boolean concurrent;

    public SchedulingGroupBuilder(Eventloop eventloop) {
        this.eventloop = eventloop;
    }

    public SchedulingGroupBuilder setName(String name) {
        this.name = checkNotNull(name, name);
        return this;
    }

    public SchedulingGroupBuilder setShares(int shares) {
        this.shares = checkPositive(shares, "shares");
        return this;
    }

    public SchedulingGroupBuilder setConcurrent(boolean concurrent) {
        this.concurrent = concurrent;
        return this;
    }

    public SchedulingGroupBuilder setQueue(Queue<Object> queue) {
        this.queue = checkNotNull(queue, "queue");
        return this;
    }

    public SchedulingGroupHandle build() {
        // todo: name check
        // todo: already build check
        // todo: loop active check

        SchedulingGroup taskQueue =eventloop.taskQueueAllocator.allocate();
        taskQueue.queue = queue;
        if(taskQueue.queue == null){
            throw new RuntimeException();
        }
        taskQueue.concurrent = concurrent;
        taskQueue.shares = shares;
        taskQueue.name = name;
        taskQueue.eventloop = eventloop;
        taskQueue.state = SchedulingGroup.STATE_BLOCKED;

        if (concurrent) {
            eventloop.blockedConcurrentSchedGroups.add(taskQueue);
        }

        return new SchedulingGroupHandle(taskQueue);
    }

    private SchedulingGroup[] add(SchedulingGroup taskQueue, SchedulingGroup[] oldArray) {
        SchedulingGroup[] newArray = new SchedulingGroup[oldArray.length + 1];
        System.arraycopy(oldArray, 0, newArray, 0, oldArray.length);
        newArray[oldArray.length] = taskQueue;
        return newArray;
    }
}
