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

package com.hazelcast.internal.tpc.nio;

import com.hazelcast.internal.tpc.AsyncSocket;
import com.hazelcast.internal.tpc.AsyncSocketBuilder;
import com.hazelcast.internal.tpc.Option;
import com.hazelcast.internal.tpc.ReadHandler;
import com.hazelcast.internal.tpc.SSLEngineFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.SocketChannel;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import static com.hazelcast.internal.tpc.util.ExceptionUtil.sneakyThrow;
import static com.hazelcast.internal.tpc.util.Preconditions.checkNotNull;
import static com.hazelcast.internal.tpc.util.Preconditions.checkPositive;

/**
 * A {@link AsyncSocketBuilder} specific to the {@link NioAsyncSocket}.
 */
public class NioAsyncSocketBuilder implements AsyncSocketBuilder {

    static final Executor DEFAULT_TLS_EXECUTOR = Executors.newSingleThreadExecutor();

    static final int DEFAULT_UNFLUSHED_BUFS_CAPACITY = 2 << 16;

    final NioReactor reactor;
    final SocketChannel socketChannel;
    final NioAcceptRequest acceptRequest;
    final boolean clientSide;
    boolean regularSchedule = true;
    boolean writeThrough;
    boolean directBuffers = true;
    int unflushedBufsCapacity = DEFAULT_UNFLUSHED_BUFS_CAPACITY;
    ReadHandler readHandler;
    NioAsyncSocketOptions options;
    SSLEngineFactory sslEngineFactory;
    private boolean build;
    Executor tlsExecutor = DEFAULT_TLS_EXECUTOR;

    NioAsyncSocketBuilder(NioReactor reactor, NioAcceptRequest acceptRequest) {
        try {
            this.reactor = reactor;
            this.acceptRequest = acceptRequest;
            if (acceptRequest == null) {
                this.socketChannel = SocketChannel.open();
                this.clientSide = true;
            } else {
                this.socketChannel = acceptRequest.socketChannel;
                this.clientSide = false;
            }
            this.socketChannel.configureBlocking(false);
            this.options = new NioAsyncSocketOptions(socketChannel);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public AsyncSocketBuilder setSSLEngineFactory(SSLEngineFactory sslEngineFactory) {
        verifyNotBuild();

        this.sslEngineFactory = checkNotNull(sslEngineFactory, "sslEngineFactory");
        return this;
    }

    @Override
    public AsyncSocketBuilder setTlsExecutor(Executor tlsExecutor) {
        verifyNotBuild();

        this.tlsExecutor = checkNotNull(tlsExecutor, "tlsExecutor");
        return this;
    }

    @Override
    public <T> NioAsyncSocketBuilder set(Option<T> option, T value) {
        verifyNotBuild();

        options.set(option, value);
        return this;
    }

    public NioAsyncSocketBuilder setDirectBuffers(boolean directBuffers) {
        verifyNotBuild();

        this.directBuffers = directBuffers;
        return this;
    }

    public NioAsyncSocketBuilder setUnflushedBufsCapacity(int unflushedBufsCapacity) {
        verifyNotBuild();

        this.unflushedBufsCapacity = checkPositive(unflushedBufsCapacity, "unflushedBufsCapacity");
        return this;
    }

    public NioAsyncSocketBuilder setRegularSchedule(boolean regularSchedule) {
        verifyNotBuild();

        this.regularSchedule = regularSchedule;
        return this;
    }

    public NioAsyncSocketBuilder setWriteThrough(boolean writeThrough) {
        verifyNotBuild();

        this.writeThrough = writeThrough;
        return this;
    }

    /**
     * Sets the read handler. Should be called before this AsyncSocket is started.
     *
     * @param readHandler the ReadHandler
     * @return this
     * @throws NullPointerException if readHandler is null.
     */
    public final NioAsyncSocketBuilder setReadHandler(ReadHandler readHandler) {
        verifyNotBuild();

        this.readHandler = checkNotNull(readHandler);
        return this;
    }

    @Override
    public AsyncSocket build() {
        verifyNotBuild();

        build = true;

        if (readHandler == null) {
            throw new IllegalStateException("readHandler is not configured.");
        }

        if (Thread.currentThread() == reactor.eventloopThread()) {
            return sslEngineFactory == null
                    ? new NioAsyncSocket(this)
                    : new TlsNioAsyncSocket(this);
        } else {
            CompletableFuture<AsyncSocket> future = new CompletableFuture<>();
            reactor.execute(() -> {
                try {
                    AsyncSocket asyncSocket = sslEngineFactory == null
                            ? new NioAsyncSocket(NioAsyncSocketBuilder.this)
                            : new TlsNioAsyncSocket(NioAsyncSocketBuilder.this);

                    future.complete(asyncSocket);
                } catch (Throwable e) {
                    future.completeExceptionally(e);
                    throw sneakyThrow(e);
                }
            });

            return future.join();
        }
    }

    private void verifyNotBuild() {
        if (build) {
            throw new IllegalStateException("Can't call build twice on the same AsyncSocketBuilder");
        }
    }
}