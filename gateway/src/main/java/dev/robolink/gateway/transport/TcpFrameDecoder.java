package dev.robolink.gateway.transport;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.CorruptedFrameException;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import java.nio.ByteOrder;

/** Per-connection TCP framing; the four-byte length describes only the body. */
final class TcpFrameDecoder extends LengthFieldBasedFrameDecoder {
    TcpFrameDecoder() {
        super(ByteOrder.BIG_ENDIAN, TcpEnvelopeCodec.MAX_FRAME_BYTES,
                0, TcpEnvelopeCodec.LENGTH_BYTES, 0, TcpEnvelopeCodec.LENGTH_BYTES, true);
    }

    @Override
    protected long getUnadjustedFrameLength(ByteBuf buffer, int offset, int length, ByteOrder order) {
        long bodyLength = super.getUnadjustedFrameLength(buffer, offset, length, order);
        if (bodyLength == 0) {
            throw new CorruptedFrameException("Empty TCP body is not allowed");
        }
        return bodyLength;
    }
}
