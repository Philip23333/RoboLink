package dev.robolink.gateway.transport;

import static org.junit.jupiter.api.Assertions.*;

import dev.robolink.protocol.v1.Envelope;
import dev.robolink.protocol.v1.Heartbeat;
import dev.robolink.protocol.v1.Pose;
import dev.robolink.protocol.v1.RobotState;
import dev.robolink.protocol.v1.Telemetry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.EncoderException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class TcpEnvelopeCodecTest {
    static Envelope telemetry(long seq) {
        return Envelope.newBuilder().setProtocolVersion(1).setRobotId("RB-001")
                .setSessionId("test-session").setSeq(seq).setTimestampMs(1792400000000L)
                .setTelemetry(Telemetry.newBuilder().setBatteryPercent(87)
                        .setPose(Pose.newBuilder().setXM(1.25).setYM(-2.5).setYawRad(0.5))
                        .setState(RobotState.ROBOT_STATE_IDLE)).build();
    }

    // Independent wire construction: tests must not use the production encoder to test decoding.
    static byte[] frame(Envelope message) {
        return rawFrame(message.toByteArray());
    }

    static byte[] rawFrame(byte[] body) {
        return ByteBuffer.allocate(4 + body.length).putInt(body.length).put(body).array();
    }

    static byte[] join(byte[]... parts) {
        var output = new ByteArrayOutputStream();
        for (var part : parts) { output.writeBytes(part); }
        return output.toByteArray();
    }

    private static EmbeddedChannel channel() {
        var channel = new EmbeddedChannel();
        TcpEnvelopeCodec.install(channel.pipeline());
        return channel;
    }

    private static byte[] drainOutbound(EmbeddedChannel channel) {
        var output = new ByteArrayOutputStream();
        ByteBuf buffer;
        while ((buffer = channel.readOutbound()) != null) {
            try {
                byte[] bytes = new byte[buffer.readableBytes()];
                buffer.readBytes(bytes);
                output.writeBytes(bytes);
            } finally { buffer.release(); }
        }
        return output.toByteArray();
    }

    private static Envelope sizedMessage(int bodyBytes) {
        // 2 bytes version + 4 bytes string tag/length + 2 bytes empty heartbeat.
        var message = Envelope.newBuilder().setProtocolVersion(1)
                .setRobotId("r".repeat(bodyBytes - 8))
                .setHeartbeat(Heartbeat.getDefaultInstance()).build();
        assertEquals(bodyBytes, message.getSerializedSize());
        return message;
    }

    private static void assertRejected(byte[] bytes) {
        var channel = channel();
        try {
            channel.writeInbound(Unpooled.wrappedBuffer(bytes));
            assertFalse(channel.isActive(), "invalid input must close the connection");
            assertNull(channel.readInbound(), "invalid input must not reach business handlers");
            assertNull(channel.readOutbound(), "must not reply with raw invalid data");
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test
    void decodesCompleteMessage() {
        var channel = channel();
        try {
            assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(frame(telemetry(1)))));
            assertEquals(telemetry(1), channel.readInbound());
            assertNull(channel.readInbound());
            assertTrue(channel.isActive());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test
    void waitsForEveryPossibleTwoChunkSplit() {
        byte[] bytes = frame(telemetry(1));
        for (int split = 1; split < bytes.length; split++) {
            var channel = channel();
            try {
                assertFalse(channel.writeInbound(Unpooled.wrappedBuffer(Arrays.copyOfRange(bytes, 0, split))));
                assertNull(channel.readInbound());
                assertTrue(channel.writeInbound(Unpooled.wrappedBuffer(Arrays.copyOfRange(bytes, split, bytes.length))));
                assertEquals(telemetry(1), channel.readInbound());
                assertNull(channel.readInbound());
            } finally { channel.finishAndReleaseAll(); }
        }
    }

    @Test
    void acceptsOneByteAtATime() {
        var channel = channel();
        try {
            byte[] bytes = frame(telemetry(2));
            for (int i = 0; i < bytes.length; i++) {
                assertEquals(i == bytes.length - 1,
                        channel.writeInbound(Unpooled.wrappedBuffer(new byte[]{bytes[i]})));
            }
            assertEquals(telemetry(2), channel.readInbound());
            assertNull(channel.readInbound());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test
    void decodesThreeCoalescedMessagesInOrder() {
        var channel = channel();
        try {
            channel.writeInbound(Unpooled.wrappedBuffer(join(frame(telemetry(1)), frame(telemetry(2)), frame(telemetry(3)))));
            for (int i = 1; i <= 3; i++) { assertEquals(telemetry(i), channel.readInbound()); }
            assertNull(channel.readInbound());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test
    void retainsPartialNextMessageAfterACompleteOne() {
        var channel = channel();
        try {
            byte[] next = frame(telemetry(2));
            channel.writeInbound(Unpooled.wrappedBuffer(join(frame(telemetry(1)), Arrays.copyOf(next, 7))));
            assertEquals(telemetry(1), channel.readInbound());
            assertNull(channel.readInbound());
            channel.writeInbound(Unpooled.wrappedBuffer(Arrays.copyOfRange(next, 7, next.length)));
            assertEquals(telemetry(2), channel.readInbound());
            assertNull(channel.readInbound());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test
    void seededRandomSplitsAndCoalescingPreserveAllMessages() {
        List<Envelope> expected = new ArrayList<>();
        var stream = new ByteArrayOutputStream();
        for (int seq = 1; seq <= 100; seq++) {
            expected.add(telemetry(seq));
            stream.writeBytes(frame(telemetry(seq)));
        }
        byte[] bytes = stream.toByteArray();
        for (int seed = 0; seed < 20; seed++) {
            var random = new Random(seed);
            var channel = channel();
            try {
                int offset = 0;
                while (offset < bytes.length) {
                    int end = Math.min(offset + 1 + random.nextInt(300), bytes.length);
                    channel.writeInbound(Unpooled.wrappedBuffer(Arrays.copyOfRange(bytes, offset, end)));
                    offset = end;
                }
                for (var message : expected) { assertEquals(message, channel.readInbound(), "seed=" + seed); }
                assertNull(channel.readInbound());
            } finally { channel.finishAndReleaseAll(); }
        }
    }

    @Test
    void connectionsDoNotSharePartialFrames() {
        var first = channel();
        var second = channel();
        try {
            byte[] bytes = frame(telemetry(1));
            first.writeInbound(Unpooled.wrappedBuffer(Arrays.copyOf(bytes, 3)));
            second.writeInbound(Unpooled.wrappedBuffer(frame(telemetry(2))));
            assertEquals(telemetry(2), second.readInbound());
            assertNull(first.readInbound());
            first.writeInbound(Unpooled.wrappedBuffer(Arrays.copyOfRange(bytes, 3, bytes.length)));
            assertEquals(telemetry(1), first.readInbound());
        } finally { first.finishAndReleaseAll(); second.finishAndReleaseAll(); }
    }

    @Test
    void outboundHasFourByteBigEndianBodyLength() {
        var channel = channel();
        try {
            channel.writeOutbound(telemetry(1));
            byte[] bytes = drainOutbound(channel);
            assertEquals(telemetry(1).getSerializedSize(), ByteBuffer.wrap(bytes).getInt());
            assertArrayEquals(frame(telemetry(1)), bytes);
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test
    void acceptsExact64KiBBodyInBothDirections() {
        var channel = channel();
        try {
            var message = sizedMessage(65536);
            channel.writeInbound(Unpooled.wrappedBuffer(frame(message)));
            assertEquals(message, channel.readInbound());
            channel.writeOutbound(message);
            byte[] bytes = drainOutbound(channel);
            assertEquals(65540, bytes.length);
            assertArrayEquals(frame(message), bytes);
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test
    void rejectsZeroLength() { assertRejected(new byte[4]); }

    @Test
    void rejectsOversizedLengthBeforeBodyArrives() {
        assertRejected(ByteBuffer.allocate(4).putInt(65537).array());
    }

    @Test
    void treatsHighBitLengthsAsUnsignedAndRejectsThem() {
        assertRejected(ByteBuffer.allocate(4).putInt(0x80000000).array());
        assertRejected(ByteBuffer.allocate(4).putInt(0xffffffff).array());
    }

    @Test
    void rejectsMalformedProtobuf() { assertRejected(rawFrame(new byte[]{(byte) 0x80})); }

    @Test
    void rejectsUnsupportedVersion() {
        assertRejected(frame(telemetry(1).toBuilder().setProtocolVersion(2).build()));
    }

    @Test
    void rejectsMissingPayload() {
        assertRejected(frame(telemetry(1).toBuilder().clearPayload().build()));
    }

    @Test
    void doesNotDeliverValidMessageAfterBadProtobufInSameRead() {
        assertRejected(join(rawFrame(new byte[]{(byte) 0x80}), frame(telemetry(2))));
    }

    @Test
    void doesNotDeliverValidMessageAfterBadEnvelopeInSameRead() {
        assertRejected(join(frame(telemetry(1).toBuilder().setProtocolVersion(2).build()), frame(telemetry(2))));
    }

    @Test
    void closingMidFrameDiscardsAndReleasesPartialData() {
        for (int length : new int[]{2, 7}) {
            var channel = channel();
            ByteBuf partial = Unpooled.wrappedBuffer(Arrays.copyOf(frame(telemetry(1)), length));
            try {
                channel.writeInbound(partial);
                assertNull(channel.readInbound());
                assertFalse(channel.finish());
                assertNull(channel.readInbound());
                assertEquals(0, partial.refCnt());
            } finally { channel.finishAndReleaseAll(); }
        }
    }

    @Test
    void rejectsOversizedOutboundMessageWithoutWritingBytes() {
        var channel = channel();
        try {
            assertThrows(EncoderException.class, () -> channel.writeOutbound(sizedMessage(65537)));
            assertFalse(channel.isActive());
            assertNull(channel.readOutbound());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test
    void rejectsInvalidOutboundEnvelopeWithoutWritingBytes() {
        var channel = channel();
        try {
            assertThrows(EncoderException.class, () -> channel.writeOutbound(Envelope.getDefaultInstance()));
            assertFalse(channel.isActive());
            assertNull(channel.readOutbound());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test
    void rejectsRawOutboundBytesAndReleasesThem() {
        var channel = channel();
        ByteBuf raw = Unpooled.buffer().writeByte(1);
        try {
            assertThrows(EncoderException.class, () -> channel.writeOutbound(raw));
            assertEquals(0, raw.refCnt());
            assertFalse(channel.isActive());
            assertNull(channel.readOutbound());
        } finally { channel.finishAndReleaseAll(); }
    }
}
