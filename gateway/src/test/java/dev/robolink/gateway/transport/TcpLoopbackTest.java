package dev.robolink.gateway.transport;

import static org.junit.jupiter.api.Assertions.*;
import static dev.robolink.gateway.transport.TcpEnvelopeCodecTest.*;

import dev.robolink.protocol.v1.Envelope;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import java.io.DataInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Real localhost TCP sockets; echo is a test fixture, not a gateway business feature. */
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class TcpLoopbackTest {
    private NioEventLoopGroup acceptors;
    private NioEventLoopGroup workers;
    private Channel server;

    @BeforeEach
    void startLocalTestServer() throws InterruptedException {
        acceptors = new NioEventLoopGroup(1);
        workers = new NioEventLoopGroup(1);
        server = new ServerBootstrap().group(acceptors, workers).channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel channel) {
                        TcpEnvelopeCodec.install(channel.pipeline());
                        channel.pipeline().addLast(new SimpleChannelInboundHandler<Envelope>() {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, Envelope message) {
                                ctx.writeAndFlush(message);
                            }
                        });
                    }
                }).bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0)).sync().channel();
    }

    @AfterEach
    void stopLocalTestServer() {
        if (server != null) { server.close().syncUninterruptibly(); }
        if (workers != null) { workers.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly(); }
        if (acceptors != null) { acceptors.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly(); }
    }

    private Socket connect() throws Exception {
        var socket = new Socket();
        socket.connect(server.localAddress(), 3000);
        socket.setSoTimeout(3000);
        socket.setTcpNoDelay(true);
        return socket;
    }

    @Test
    void receivesThreeEnvelopesAndRepliesWithProperFrames() throws Exception {
        try (var socket = connect()) {
            var output = socket.getOutputStream();
            byte[] first = frame(telemetry(1));
            output.write(first, 0, 2);
            output.flush();
            output.write(first, 2, 5);
            output.flush();
            output.write(join(Arrays.copyOfRange(first, 7, first.length), frame(telemetry(2)), frame(telemetry(3))));
            output.flush();
            var input = new DataInputStream(socket.getInputStream());
            for (int seq = 1; seq <= 3; seq++) {
                int length = input.readInt();
                assertTrue(length > 0 && length <= 65536);
                byte[] body = new byte[length];
                input.readFully(body);
                assertEquals(telemetry(seq), Envelope.parseFrom(body));
            }
        }
    }

    @Test
    void oversizedHeaderClosesPeerWithoutWaitingForBody() throws Exception {
        try (var socket = connect()) {
            socket.getOutputStream().write(ByteBuffer.allocate(4).putInt(65537).array());
            socket.getOutputStream().flush();
            assertEquals(-1, socket.getInputStream().read());
        }
    }
}
