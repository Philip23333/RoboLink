package dev.robolink.gateway;

import dev.robolink.gateway.session.DeviceConnectionPipeline;
import dev.robolink.gateway.session.DeviceCredentials;
import dev.robolink.gateway.session.SessionRegistry;
import dev.robolink.gateway.control.ControlAccess;
import dev.robolink.gateway.control.ControlConfig;
import dev.robolink.gateway.control.ControlRuntime;
import dev.robolink.gateway.control.ControlWebSocketPipeline;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.GlobalEventExecutor;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class GatewayServer implements AutoCloseable {
    private final GatewayConfig config;
    private final DeviceCredentials credentials;
    private final SessionRegistry registry = new SessionRegistry();
    private final ChannelGroup connections = new DefaultChannelGroup("devices", GlobalEventExecutor.INSTANCE, true);
    private NioEventLoopGroup acceptors;
    private NioEventLoopGroup workers;
    private Channel listener;
    private boolean closed;
    private final ControlConfig controlConfig;
    private final ControlAccess controlAccess;
    private ControlRuntime control;
    private Channel controlListener;
    private final AtomicInteger controlConnections = new AtomicInteger();

    public GatewayServer(GatewayConfig config, DeviceCredentials credentials) {
        this(config, credentials, null, null);
    }

    public GatewayServer(GatewayConfig config, DeviceCredentials credentials, ControlConfig controlConfig, ControlAccess controlAccess) {
        this.config = config;
        this.credentials = credentials;
        if ((controlConfig == null) != (controlAccess == null)) { throw new IllegalArgumentException("Control config and access must be provided together"); }
        this.controlConfig = controlConfig;
        this.controlAccess = controlAccess;
    }

    public synchronized GatewayServer start() throws Exception {
        if (closed || listener != null) { throw new IllegalStateException("Gateway cannot be started again"); }
        acceptors = new NioEventLoopGroup(1);
        workers = new NioEventLoopGroup(2);
        try {
            if (controlConfig != null) { control = new ControlRuntime(controlConfig, controlAccess, registry); }
            listener = new ServerBootstrap().group(acceptors, workers).channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            connections.add(channel);
                            DeviceConnectionPipeline.install(channel.pipeline(), config, credentials, registry);
                        }
                    }).bind(config.host(), config.port()).sync().channel();
            if (control != null) {
                controlListener = new ServerBootstrap().group(acceptors, workers).channel(NioServerSocketChannel.class)
                        .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new io.netty.channel.WriteBufferWaterMark(32768, 65536))
                        .childHandler(new ChannelInitializer<SocketChannel>() {
                            @Override
                            protected void initChannel(SocketChannel channel) {
                                connections.add(channel);
                                int count = controlConnections.incrementAndGet();
                                channel.closeFuture().addListener(done -> controlConnections.decrementAndGet());
                                if (count > controlConfig.maxClients()) { channel.close(); return; }
                                ControlWebSocketPipeline.install(channel.pipeline(), controlConfig, control);
                            }
                        }).bind(controlConfig.host(), controlConfig.port()).sync().channel();
            }
            return this;
        } catch (Exception failed) {
            close();
            throw failed;
        }
    }

    public InetSocketAddress localAddress() {
        if (listener == null) { throw new IllegalStateException("Gateway not started"); }
        return (InetSocketAddress) listener.localAddress();
    }

    public SessionRegistry sessions() { return registry; }

    public InetSocketAddress controlAddress() {
        if (controlListener == null) { throw new IllegalStateException("Control listener is disabled"); }
        return (InetSocketAddress) controlListener.localAddress();
    }

    public void awaitClose() throws InterruptedException {
        if (listener == null) { throw new IllegalStateException("Gateway not started"); }
        listener.closeFuture().sync();
    }

    @Override
    public synchronized void close() {
        if (closed) { return; }
        closed = true;
        if (listener != null) { listener.close().syncUninterruptibly(); }
        if (controlListener != null) { controlListener.close().syncUninterruptibly(); }
        connections.close().awaitUninterruptibly();
        if (workers != null) { workers.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly(); }
        if (acceptors != null) { acceptors.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly(); }
        if (control != null) { control.close(); }
    }
}
