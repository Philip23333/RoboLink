package dev.robolink.gateway.session;

import dev.robolink.gateway.GatewayConfig;
import dev.robolink.gateway.transport.TcpEnvelopeCodec;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import java.util.concurrent.TimeUnit;

public final class DeviceConnectionPipeline {
    private DeviceConnectionPipeline() {}

    public static void install(ChannelPipeline pipeline, GatewayConfig config,
                               DeviceCredentials credentials, SessionRegistry registry) {
        TcpEnvelopeCodec.install(pipeline);
        var idle = new IdleStateHandler(config.heartbeatTimeoutMs(), 0, 0, TimeUnit.MILLISECONDS);
        var sessionHandler = new DeviceConnectionHandler(config, credentials, registry, idle);
        pipeline.addLast("deviceSession", sessionHandler);
        // Partial, unauthorized, stale and invalid messages never refresh this timer.
        pipeline.addLast("validMessageIdle", idle);
        pipeline.addLast("idleDisconnect", new ChannelInboundHandlerAdapter() {
            @Override
            public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
                if (event instanceof IdleStateEvent state && state.state() == IdleState.READER_IDLE) {
                    sessionHandler.onReadIdle(ctx);
                } else { ctx.fireUserEventTriggered(event); }
            }
        });
    }
}
