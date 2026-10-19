package dev.robolink.gateway.transport;

import dev.robolink.protocol.v1.Envelope;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.EncoderException;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.codec.protobuf.ProtobufDecoder;
import io.netty.handler.codec.protobuf.ProtobufEncoder;
import io.netty.util.ReferenceCountUtil;
import java.nio.ByteOrder;
import java.nio.channels.ClosedChannelException;

/** Installs a fresh codec on each connection; business handlers are added after it. */
public final class TcpEnvelopeCodec {
    public static final int PROTOCOL_VERSION = 1;
    public static final int LENGTH_BYTES = 4;
    public static final int MAX_BODY_BYTES = 64 * 1024;
    public static final int MAX_FRAME_BYTES = LENGTH_BYTES + MAX_BODY_BYTES;

    private TcpEnvelopeCodec() {}

    public static void install(ChannelPipeline pipeline) {
        pipeline.addLast("tcpFrameDecoder", new TcpFrameDecoder());
        pipeline.addLast("protobufDecoder", new ProtobufDecoder(Envelope.getDefaultInstance()));
        // Outbound handlers run in reverse pipeline order: Envelope -> body -> header + body.
        pipeline.addLast("tcpLengthEncoder", new LengthFieldPrepender(ByteOrder.BIG_ENDIAN, LENGTH_BYTES, 0, false));
        pipeline.addLast("protobufEncoder", new ProtobufEncoder());
        pipeline.addLast("envelopeGuard", new EnvelopeGuard());
        pipeline.addLast("protocolFailure", new ProtocolFailureHandler());
    }

    private static void validate(Envelope message) {
        if (message.getProtocolVersion() != PROTOCOL_VERSION) {
            throw new CorruptedFrameException("Unsupported protocol version");
        }
        if (message.getPayloadCase() == Envelope.PayloadCase.PAYLOAD_NOT_SET) {
            throw new CorruptedFrameException("Envelope payload is required");
        }
        if (message.getSerializedSize() > MAX_BODY_BYTES) {
            throw new CorruptedFrameException("Envelope body exceeds limit");
        }
    }

    private static final class EnvelopeGuard extends ChannelDuplexHandler {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object message) {
            // A decoder may already have queued more frames when a preceding frame fails.
            if (!ctx.channel().isActive()) {
                ReferenceCountUtil.release(message);
                return;
            }
            if (!(message instanceof Envelope envelope)) {
                ReferenceCountUtil.release(message);
                throw new CorruptedFrameException("Expected an Envelope");
            }
            validate(envelope);
            ctx.fireChannelRead(envelope);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object message, ChannelPromise promise) {
            if (!ctx.channel().isActive()) {
                ReferenceCountUtil.release(message);
                promise.tryFailure(new ClosedChannelException());
                return;
            }
            try {
                if (!(message instanceof Envelope envelope)) {
                    throw new EncoderException("Expected an Envelope");
                }
                validate(envelope);
            } catch (RuntimeException invalid) {
                ReferenceCountUtil.release(message);
                promise.tryFailure(new EncoderException("Outbound Envelope rejected", invalid));
                ctx.close();
                return;
            }
            ctx.write(message, promise);
        }
    }

    private static final class ProtocolFailureHandler extends ChannelInboundHandlerAdapter {
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            // Close without echoing or logging raw payloads, which may contain credentials.
            ctx.close();
        }
    }
}
