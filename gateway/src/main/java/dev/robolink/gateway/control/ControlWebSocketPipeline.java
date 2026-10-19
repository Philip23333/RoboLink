package dev.robolink.gateway.control;

import dev.robolink.protocol.v1.Envelope;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;

public final class ControlWebSocketPipeline {
    private ControlWebSocketPipeline() {}

    public static void install(ChannelPipeline pipeline, ControlConfig config, ControlRuntime runtime) {
        pipeline.addLast(new HttpServerCodec());
        pipeline.addLast(new HttpObjectAggregator(8192));
        pipeline.addLast(new SimpleChannelInboundHandler<FullHttpRequest>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
                String origin = request.headers().get(HttpHeaderNames.ORIGIN);
                if (!request.decoderResult().isSuccess() || !request.method().equals(HttpMethod.GET) || !request.uri().equals("/control")
                        || origin == null || !config.origins().contains(origin)) {
                    var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.FORBIDDEN);
                    response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
                    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
                } else { ctx.fireChannelRead(request.retain()); }
            }
        });
        pipeline.addLast(new WebSocketServerProtocolHandler(WebSocketServerProtocolConfig.newBuilder()
                .websocketPath("/control").allowExtensions(false).maxFramePayloadLength(65536)
                .handshakeTimeoutMillis(config.authTimeoutMs()).build()));
        pipeline.addLast(new WebSocketFrameAggregator(65536));
        pipeline.addLast(new SimpleChannelInboundHandler<WebSocketFrame>() {
            private ControlRuntime.Peer peer;
            private io.netty.util.concurrent.ScheduledFuture<?> handshakeDeadline;

            private void scheduleHandshakeDeadline(ChannelHandlerContext ctx) {
                if (handshakeDeadline == null) {
                    handshakeDeadline = ctx.executor().schedule(() -> {
                        if (peer == null) { ctx.close(); }
                    }, config.authTimeoutMs(), java.util.concurrent.TimeUnit.MILLISECONDS);
                }
            }

            @Override
            public void handlerAdded(ChannelHandlerContext ctx) {
                if (ctx.channel().isActive()) { scheduleHandshakeDeadline(ctx); }
            }

            @Override
            public void channelActive(ChannelHandlerContext ctx) {
                scheduleHandshakeDeadline(ctx);
                ctx.fireChannelActive();
            }

            @Override
            public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
                if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
                    handshakeDeadline.cancel(false);
                    peer = runtime.connected(new ControlSink() {
                        public boolean active() { return ctx.channel().isActive(); }
                        public boolean writable() { return ctx.channel().isWritable(); }
                        public void close() { ctx.close(); }
                        public void write(Envelope message, java.util.function.Consumer<Boolean> completed) {
                            ctx.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(message.toByteArray())))
                                    .addListener(future -> completed.accept(future.isSuccess()));
                        }
                    });
                } else { ctx.fireUserEventTriggered(event); }
            }

            @Override
            protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) throws Exception {
                if (peer == null || !(frame instanceof BinaryWebSocketFrame) || frame.content().readableBytes() == 0) {
                    ctx.close(); return;
                }
                byte[] bytes = new byte[frame.content().readableBytes()];
                frame.content().getBytes(frame.content().readerIndex(), bytes);
                runtime.receive(peer, Envelope.parseFrom(bytes));
            }

            @Override
            public void channelInactive(ChannelHandlerContext ctx) {
                if (handshakeDeadline != null) { handshakeDeadline.cancel(false); }
                if (peer != null) { runtime.disconnected(peer); }
                ctx.fireChannelInactive();
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) { ctx.close(); }
        });
    }
}
