package com.hazelcast.spi.impl.engine.nio;

import com.hazelcast.internal.networking.nio.SelectorOptimizer;
import com.hazelcast.logging.ILogger;
import com.hazelcast.spi.impl.engine.Channel;
import com.hazelcast.spi.impl.engine.Reactor;
import com.hazelcast.spi.impl.engine.Scheduler;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NioReactor extends Reactor {
    final Selector selector;
    private final AtomicBoolean wakeupNeeded = new AtomicBoolean(true);

    public NioReactor(int idx, String name, ILogger logger, Scheduler scheduler, boolean spin) {
        super(idx, name, logger, scheduler, spin);
        this.selector = SelectorOptimizer.newSelector(logger);
    }

    @Override
    public void wakeup() {
        if (spin || Thread.currentThread() == this) {
            return;
        }

        if (wakeupNeeded.get() && wakeupNeeded.compareAndSet(true, false)) {
            selector.wakeup();
        }
    }

    @Override
    protected void eventLoop() throws Exception {
        while (running) {
            runTasks();

            boolean moreWork = scheduler.tick();

            flushDirtyChannels();

            int keyCount;
            if (spin || moreWork) {
                keyCount = selector.selectNow();
            } else {
                wakeupNeeded.set(true);
                if (publicRunQueue.isEmpty()) {
                    keyCount = selector.select();
                } else {
                    keyCount = selector.selectNow();
                }
                wakeupNeeded.set(false);
            }

            if (keyCount > 0) {
                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();

                    ((NioSelectedKeyListener) key.attachment()).handle(key);
                }
            }
        }
    }

    public void accept(NioServerChannel serverChannel) throws IOException {
        serverChannel.configure(this);
        schedule(serverChannel::accept);
    }

    @Override
    public Future<Channel> connect(Channel c, SocketAddress address) {
        NioChannel channel = (NioChannel) c;

        CompletableFuture<Channel> future = new CompletableFuture<>();
        try {

            System.out.println("ConnectRequest address:" + address);

            SocketChannel socketChannel = SocketChannel.open();

            channel.configure(this, socketChannel, c.socketConfig);

            socketChannel.connect(address);
            socketChannel.configureBlocking(false);

            schedule(() -> {
                try {
                    channel.onConnectionEstablished();
                    registeredChannels.add(channel);
                    logger.info("Socket listening at " + address);
                    future.complete(channel);
                } catch (Exception e) {
                    future.completeExceptionally(e);
                }
            });
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
        return future;
    }
}