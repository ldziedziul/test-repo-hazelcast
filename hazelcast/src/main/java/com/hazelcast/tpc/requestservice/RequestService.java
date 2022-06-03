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

package com.hazelcast.tpc.requestservice;

import com.hazelcast.cluster.Address;
import com.hazelcast.instance.EndpointQualifier;
import com.hazelcast.internal.serialization.InternalSerializationService;
import com.hazelcast.internal.server.ServerConnectionManager;
import com.hazelcast.internal.server.tcp.TcpServerConnection;
import com.hazelcast.logging.ILogger;
import com.hazelcast.spi.impl.NodeEngineImpl;
import com.hazelcast.tpc.engine.AsyncSocket;
import com.hazelcast.tpc.engine.Engine;
import com.hazelcast.tpc.engine.Eventloop;
import com.hazelcast.tpc.engine.EventloopType;
import com.hazelcast.tpc.engine.ReadHandler;
import com.hazelcast.tpc.engine.Scheduler;
import com.hazelcast.tpc.engine.SyncSocket;
import com.hazelcast.tpc.engine.epoll.EpollAsyncServerSocket;
import com.hazelcast.tpc.engine.epoll.EpollEventloop;
import com.hazelcast.tpc.engine.epoll.EpollReadHandler;
import com.hazelcast.tpc.engine.frame.ParallelFrameAllocator;
import com.hazelcast.tpc.engine.frame.Frame;
import com.hazelcast.tpc.engine.frame.FrameAllocator;
import com.hazelcast.tpc.engine.frame.SerialFrameAllocator;
import com.hazelcast.tpc.engine.frame.UnpooledFrameAllocator;
import com.hazelcast.tpc.engine.nio.NioAsyncServerSocket;
import com.hazelcast.tpc.engine.nio.NioAsyncSocket;
import com.hazelcast.tpc.engine.nio.NioEventloop;
import com.hazelcast.tpc.engine.nio.NioAsyncReadHandler;
import com.hazelcast.table.impl.PipelineImpl;
import com.hazelcast.table.impl.TableManager;
import com.hazelcast.tpc.engine.epoll.EpollAsyncSocket;
import com.hazelcast.tpc.engine.iouring.IOUringAsyncServerSocket;
import com.hazelcast.tpc.engine.iouring.IOUringAsyncSocket;
import com.hazelcast.tpc.engine.iouring.IOUringEventloop;
import com.hazelcast.tpc.engine.iouring.IOUringAsyncReadHandler;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static com.hazelcast.internal.util.HashUtil.hashToIndex;
import static com.hazelcast.tpc.engine.frame.Frame.OFFSET_REQ_CALL_ID;


/**
 * The RequestService is an application of the Engine.
 *
 * The Reactor is very specific to requests/responses. It isn't a flexible framework unlike Seastar.
 *
 * Mapping from partition to CPU is easy; just a simple mod.
 *
 * RSS: How can we align:
 * - the CPU receiving data from some TCP/IP-connection.
 * - and pinning the same CPU to the RX-queue that processes that TCP/IP-connection
 * So how can we make sure that all TCP/IP-connections for that CPU are processed by the same CPU processing the IRQ.
 *
 * And how can we make sure that for example we want to isolate a few CPUs for the RSS part, but then
 * forward to the CPU that owns the TCP/IP-connection
 *
 * So it appears that Seastar is using the toeplitz hash
 * https://github.com/scylladb/seastar/issues/654
 *
 * So we have a list of channels to some machine.
 *
 * And we determine for each of the channel the toeplitz hash based on src/dst port/ip;
 *
 * So this would determine which channels are mapped to some CPU.
 *
 * So how do we go from partition to a channel?
 */
public class RequestService {

    public final NodeEngineImpl nodeEngine;
    public final InternalSerializationService ss;
    public final ILogger logger;
    private final Address thisAddress;
    private final int socketCount;
    private final SocketConfig socketConfig;
    private final boolean poolRequests;
    private final boolean poolLocalResponses;
    private final boolean poolRemoteResponses;
    private final boolean writeThrough;
    private final int responseThreadCount;
    private final boolean responseThreadSpin;
    private final int requestTimeoutMs;
    private final boolean regularSchedule;
    private final ResponseHandler responseHandler;
    private volatile ServerConnectionManager connectionManager;
    public volatile boolean shuttingdown = false;
    public final Managers managers;
    private int[] partitionIdToChannel;
    private final RequestRegistry requestRegistry;
    private Engine engine;
    private int concurrentRequestLimit;
    private final Map<Eventloop, Supplier<? extends ReadHandler>> readHandlerSuppliers = new HashMap<>();

    public RequestService(NodeEngineImpl nodeEngine) {
        this.nodeEngine = nodeEngine;
        this.logger = nodeEngine.getLogger(RequestService.class);
        this.ss = (InternalSerializationService) nodeEngine.getSerializationService();
        this.responseThreadCount = Integer.parseInt(java.lang.System.getProperty("reactor.responsethread.count", "1"));
        this.responseThreadSpin = Boolean.parseBoolean(java.lang.System.getProperty("reactor.responsethread.spin", "false"));
        this.writeThrough = Boolean.parseBoolean(java.lang.System.getProperty("reactor.write-through", "false"));
        this.regularSchedule = Boolean.parseBoolean(java.lang.System.getProperty("reactor.regular-schedule", "true"));
        this.poolRequests = Boolean.parseBoolean(java.lang.System.getProperty("reactor.pool-requests", "true"));
        this.poolLocalResponses = Boolean.parseBoolean(java.lang.System.getProperty("reactor.pool-local-responses", "true"));
        this.poolRemoteResponses = Boolean.parseBoolean(java.lang.System.getProperty("reactor.pool-remote-responses", "false"));
        this.concurrentRequestLimit = Integer.parseInt(java.lang.System.getProperty("reactor.concurrent-request-limit", "-1"));
        this.requestTimeoutMs = Integer.parseInt(java.lang.System.getProperty("reactor.request.timeoutMs", "23000"));
        this.socketCount = Integer.parseInt(java.lang.System.getProperty("reactor.channels", "" + Runtime.getRuntime().availableProcessors()));

        printEventloopInfo();

        this.requestRegistry = new RequestRegistry(concurrentRequestLimit);
        this.responseHandler = new ResponseHandler(responseThreadCount, responseThreadSpin, requestRegistry);
        this.thisAddress = nodeEngine.getThisAddress();
        this.engine = newEngine();

        this.socketConfig = new SocketConfig();
        this.managers = new Managers();
        //hack
        managers.tableManager = new TableManager(271);

        this.partitionIdToChannel = new int[271];
        for (int k = 0; k < 271; k++) {
            partitionIdToChannel[k] = hashToIndex(k, socketCount);
        }
    }

    public Engine getEngine() {
        return engine;
    }

    public int getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    @NotNull
    private Engine newEngine() {
        Engine.Configuration configuration = new Engine.Configuration();
        configuration.setEventloopBasename("Eventloop:[" + thisAddress.getHost() + ":" + thisAddress.getPort() + "]:");
        configuration.setSchedulerSupplier(new Supplier<Scheduler>() {
            @Override
            public Scheduler get() {
                FrameAllocator remoteResponseFrameAllocator = new ParallelFrameAllocator(128, true);
                FrameAllocator localResponseFrameAllocator = new SerialFrameAllocator(128, true);

                return new OpScheduler(32768,
                        Integer.MAX_VALUE,
                        managers,
                        localResponseFrameAllocator,
                        remoteResponseFrameAllocator);
            }
        });
        Engine engine = new Engine(configuration);
        engine.printConfig();


        if (socketCount % engine.eventloopCount() != 0) {
            throw new IllegalStateException("socket count is not multiple of reactor count");
        }

        return engine;
    }

    private void configureNio() {
        for (int k = 0; k < engine.eventloopCount(); k++) {
            NioEventloop eventloop = (NioEventloop) engine.eventloop(k);

            Supplier<NioAsyncReadHandler> readHandlerSupplier = () -> {
                RequestNioReadHandler readHandler = new RequestNioReadHandler();
                readHandler.opScheduler = (OpScheduler) eventloop.scheduler();
                readHandler.responseHandler = responseHandler;
                readHandler.requestFrameAllocator = poolRequests
                        ? new SerialFrameAllocator(128, true)
                        : new UnpooledFrameAllocator();
                readHandler.remoteResponseFrameAllocator = poolRemoteResponses
                        ? new ParallelFrameAllocator(128, true)
                        : new UnpooledFrameAllocator();
                return readHandler;
            };
            readHandlerSuppliers.put(eventloop, readHandlerSupplier);

            try {
                int port = toPort(thisAddress, k);
                NioAsyncServerSocket serverSocket = NioAsyncServerSocket.open(eventloop);
                serverSocket.receiveBufferSize(socketConfig.receiveBufferSize);
                serverSocket.reuseAddress(true);
                serverSocket.bind(new InetSocketAddress(thisAddress.getInetAddress(), port));
                serverSocket.accept(socket -> {
                    socket.readHandler(readHandlerSuppliers.get(eventloop).get());
                    socket.setWriteThrough(writeThrough);
                    socket.setRegularSchedule(regularSchedule);
                    socket.sendBufferSize(socketConfig.sendBufferSize);
                    socket.receiveBufferSize(socketConfig.receiveBufferSize);
                    socket.tcpNoDelay(socketConfig.tcpNoDelay);
                    socket.activate(eventloop);
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private void configureIOUring() {
        for (int k = 0; k < engine.eventloopCount(); k++) {
            IOUringEventloop eventloop = (IOUringEventloop) engine.eventloop(k);
            try {
                Supplier<IOUringAsyncReadHandler> readHandlerSupplier = () -> {
                    RequestIOUringReadHandler readHandler = new RequestIOUringReadHandler();
                    readHandler.opScheduler = (OpScheduler) eventloop.scheduler();
                    readHandler.responseHandler = responseHandler;
                    readHandler.requestFrameAllocator = poolRequests
                            ? new SerialFrameAllocator(128, true)
                            : new UnpooledFrameAllocator();
                    readHandler.remoteResponseFrameAllocator = poolRemoteResponses
                            ? new ParallelFrameAllocator(128, true)
                            : new UnpooledFrameAllocator();
                    return readHandler;
                };
                readHandlerSuppliers.put(eventloop, readHandlerSupplier);

                int port = toPort(thisAddress, k);

                IOUringAsyncServerSocket serverSocket = IOUringAsyncServerSocket.open(eventloop);
                serverSocket.receiveBufferSize(socketConfig.receiveBufferSize);
                serverSocket.reuseAddress(true);
                serverSocket.bind(new InetSocketAddress(thisAddress.getInetAddress(), port));
                serverSocket.listen(10);
                serverSocket.accept(socket -> {
                    socket.readHandler(readHandlerSuppliers.get(eventloop).get());
                    socket.sendBufferSize(socketConfig.sendBufferSize);
                    socket.receiveBufferSize(socketConfig.receiveBufferSize);
                    socket.tcpNoDelay(socketConfig.tcpNoDelay);
                    socket.activate(eventloop);
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private void configureEpoll() {
        for (int k = 0; k < engine.eventloopCount(); k++) {
            EpollEventloop eventloop = (EpollEventloop) engine.eventloop(k);
            try {
                Supplier<EpollReadHandler> readHandlerSupplier = () -> {
                    RequestEpollReadHandler readHandler = new RequestEpollReadHandler();
                    readHandler.opScheduler = (OpScheduler) eventloop.scheduler();
                    readHandler.responseHandler = responseHandler;
                    readHandler.requestFrameAllocator = poolRequests
                            ? new SerialFrameAllocator(128, true)
                            : new UnpooledFrameAllocator();
                    readHandler.remoteResponseFrameAllocator = poolRemoteResponses
                            ? new ParallelFrameAllocator(128, true)
                            : new UnpooledFrameAllocator();
                    return readHandler;
                };
                readHandlerSuppliers.put(eventloop, readHandlerSupplier);

                int port = toPort(thisAddress, k);

                EpollAsyncServerSocket serverSocket = EpollAsyncServerSocket.open(eventloop);
                serverSocket.receiveBufferSize(socketConfig.receiveBufferSize);
                serverSocket.reuseAddress(true);
                serverSocket.bind(new InetSocketAddress(thisAddress.getInetAddress(), port));
                serverSocket.listen(10);
                serverSocket.accept(socket -> {
                    socket.readHandler(readHandlerSuppliers.get(eventloop).get());
                    socket.sendBufferSize(socketConfig.sendBufferSize);
                    socket.receiveBufferSize(socketConfig.receiveBufferSize);
                    socket.tcpNoDelay(socketConfig.tcpNoDelay);
                    socket.activate(eventloop);
                });
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private void printEventloopInfo() {
        java.lang.System.out.println("reactor.responsethread.count:" + responseThreadCount);
        java.lang.System.out.println("reactor.write-through:" + writeThrough);
        java.lang.System.out.println("reactor.channels:" + socketCount);
        java.lang.System.out.println("reactor.pool-requests:" + poolRequests);
        java.lang.System.out.println("reactor.pool-local-responses:" + poolLocalResponses);
        java.lang.System.out.println("reactor.pool-remote-responses:" + poolRemoteResponses);
        java.lang.System.out.println("reactor.cpu-affinity:" + java.lang.System.getProperty("reactor.cpu-affinity"));
    }

    public int toPort(Address address, int socketId) {
        return (address.getPort() - 5701) * 100 + 11000 + socketId % engine.eventloopCount();
    }

    public int partitionIdToChannel(int partitionId) {
        return hashToIndex(partitionId, socketCount);
    }

    public void start() {
        logger.info("Starting RequestService");
        engine.start();

        EventloopType eventloopType = engine.eventloopType();
        switch (eventloopType) {
            case NIO:
                configureNio();
                break;
            case EPOLL:
                configureEpoll();
                break;
            case IOURING:
                configureIOUring();
                break;
            default:
                throw new RuntimeException();
        }

        responseHandler.start();
    }

    private void ensureActive() {
        if (shuttingdown) {
            throw new RuntimeException("Can't make invocation, frontend shutting down");
        }
    }

    public void shutdown() {
        System.out.println("RequestService shutdown");

        shuttingdown = true;

        engine.shutdown();

        requestRegistry.shutdown();

        responseHandler.shutdown();

        System.out.println("RequestService terminated");
    }


    public CompletableFuture invokeOnPartition(Frame request, int partitionId) {
        ensureActive();

        if (partitionId < 0) {
            throw new RuntimeException("Negative partition id not supported:" + partitionId);
        }

        Address address = nodeEngine.getPartitionService().getPartitionOwner(partitionId);
        CompletableFuture future = request.future;
        if (address.equals(thisAddress)) {
            // todo: hack with the assignment of a partition to a local cpu.
            engine.eventloopForHash(partitionIdToChannel(partitionId)).execute(request);
        } else {
            AsyncSocket socket = getConnection(address).sockets[partitionIdToChannel[partitionId]];

            // we need to acquire the frame because storage will release it once written
            // and we need to keep the frame around for the response.
            request.acquire();
            RequestRegistry.Requests requests = requestRegistry.getRequestsOrCreate(socket.remoteAddress());
            long callId = requests.nextCallId();
            request.putLong(OFFSET_REQ_CALL_ID, callId);
            //System.out.println("request.refCount:"+request.refCount());
            requests.map.put(callId, request);
            socket.writeAndFlush(request);
        }

        return future;
    }

    public CompletableFuture invoke(Frame request, AsyncSocket socket) {
        ensureActive();

        CompletableFuture future = request.future;
        // we need to acquire the frame because storage will release it once written
        // and we need to keep the frame around for the response.
        request.acquire();
        RequestRegistry.Requests requests = requestRegistry.getRequestsOrCreate(socket.remoteAddress());
        long callId = requests.nextCallId();
        request.putLong(OFFSET_REQ_CALL_ID, callId);
        //System.out.println("request.refCount:"+request.refCount());
        requests.map.put(callId, request);
        socket.writeAndFlush(request);
        return future;
    }

    public void invokeOnPartition(PipelineImpl pipeline) {
        ensureActive();

        List<Frame> requestList = pipeline.getRequests();
        if (requestList.isEmpty()) {
            return;
        }

        int partitionId = pipeline.getPartitionId();
        Address address = nodeEngine.getPartitionService().getPartitionOwner(partitionId);
        if (address.equals(thisAddress)) {
            engine.eventloopForHash(partitionId).execute(requestList);
        } else {
            SyncSocket socket = null;//getConnection(address).sockets[partitionIdToChannel[partitionId]];
            RequestRegistry.Requests requests = requestRegistry.getRequestsOrCreate(socket.remoteAddress());

            long c = requests.nextCallId(requestList.size());

            int k = 0;
            for (Frame request : requestList) {
                request.acquire();
                long callId = c - k;
                requests.map.put(callId, request);
                request.putLong(OFFSET_REQ_CALL_ID, callId);
                k--;
            }

            //socket.writeAll(requestList);
            socket.flush();
        }
    }

    private TcpServerConnection getConnection(Address address) {
        if (connectionManager == null) {
            connectionManager = nodeEngine.getNode().getServer().getConnectionManager(EndpointQualifier.MEMBER);
        }

        TcpServerConnection connection = (TcpServerConnection) connectionManager.get(address);
        if (connection == null) {
            connectionManager.getOrConnect(address);
            for (int k = 0; k < 60; k++) {
                try {
                    System.out.println("Waiting for connection: " + address);
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                }
                connection = (TcpServerConnection) connectionManager.get(address);
                if (connection != null) {
                    break;
                }
            }

            if (connection == null) {
                throw new RuntimeException("Could not connect to : " + address);
            }
        }

        if (connection.sockets == null) {
            synchronized (connection) {
                if (connection.sockets == null) {
                    AsyncSocket[] sockets = new AsyncSocket[socketCount];

                    for (int socketIndex = 0; socketIndex < sockets.length; socketIndex++) {
                        SocketAddress eventloopAddress = new InetSocketAddress(address.getHost(), toPort(address, socketIndex));
                        sockets[socketIndex] = connect(eventloopAddress, socketIndex);
                    }

                    connection.sockets = sockets;
                }
            }
        }

        return connection;
    }

    public AsyncSocket connect(SocketAddress address, int channelIndex) {
        Eventloop eventloop = engine.eventloopForHash(channelIndex);

        AsyncSocket socket;
        switch (engine.eventloopType()) {
            case NIO:
                NioAsyncSocket nioSocket = NioAsyncSocket.open();
                nioSocket.setWriteThrough(writeThrough);
                nioSocket.setRegularSchedule(regularSchedule);
                socket = nioSocket;
                break;
            case IOURING:
                socket = IOUringAsyncSocket.open();
                break;
            case EPOLL:
                socket = EpollAsyncSocket.open();
                break;
            default:
                throw new RuntimeException();
        }

        socket.readHandler(readHandlerSuppliers.get(eventloop).get());
        socket.sendBufferSize(socketConfig.sendBufferSize);
        socket.receiveBufferSize(socketConfig.receiveBufferSize);
        socket.tcpNoDelay(socketConfig.tcpNoDelay);
        socket.activate(eventloop);

        CompletableFuture future = socket.connect(address);
        future.join();
        System.out.println("AsyncSocket " + address + " connected");
        return socket;
    }

}