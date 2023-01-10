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


import static com.hazelcast.internal.tpc.util.Preconditions.checkNotNull;

@SuppressWarnings("rawtypes")
public final class FutAllocator {

    private final Eventloop eventloop;
    private final Fut[] array;
    private int index = -1;

    public FutAllocator(Eventloop eventloop, int capacity) {
        this.eventloop = checkNotNull(eventloop);
        this.array = new Fut[capacity];
    }

    public int size() {
        return index + 1;
    }

    Fut allocate() {
        if (index == -1) {
            Fut fut = new Fut(eventloop);
            fut.allocator = this;
            return fut;
        }

        Fut fut = array[index];
        array[index] = null;
        index--;
        fut.refCount = 1;
        return fut;
    }

    void free(Fut e) {
        checkNotNull(e);

        if (index <= array.length - 1) {
            index++;
            array[index] = e;
        }
    }
}