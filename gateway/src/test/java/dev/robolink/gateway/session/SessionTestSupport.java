package dev.robolink.gateway.session;

import static org.junit.jupiter.api.Assertions.*;

import dev.robolink.gateway.GatewayConfig;
import dev.robolink.protocol.v1.Envelope;
import dev.robolink.protocol.v1.Heartbeat;
import dev.robolink.protocol.v1.Pose;
import dev.robolink.protocol.v1.Register;
import dev.robolink.protocol.v1.RobotState;
import dev.robolink.protocol.v1.Telemetry;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.net.Socket;
import java.nio.ByteBuffer;

final class SessionTestSupport {
    static final String TEST_CREDENTIAL = "public-test-fixture-only";
    static final GatewayConfig CONFIG = new GatewayConfig("127.0.0.1", 0, 5000, 3000, 10000);

    static Envelope registration(String id) {
        return Envelope.newBuilder().setProtocolVersion(1).setRobotId(id)
                .setRegister(Register.newBuilder().setCredential(TEST_CREDENTIAL)).build();
    }

    static Envelope heartbeat(String id, String sessionId, long seq) {
        return Envelope.newBuilder().setProtocolVersion(1).setRobotId(id).setSessionId(sessionId).setSeq(seq)
                .setHeartbeat(Heartbeat.getDefaultInstance()).build();
    }

    static Envelope telemetry(String id, String sessionId, long seq) {
        return heartbeat(id, sessionId, seq).toBuilder().setTelemetry(Telemetry.newBuilder()
                .setBatteryPercent(87).setPose(Pose.newBuilder().setXM(1.25).setYM(-2.5).setYawRad(0.5))
                .setState(RobotState.ROBOT_STATE_IDLE)).build();
    }

    static byte[] frame(Envelope message) {
        byte[] body = message.toByteArray();
        return ByteBuffer.allocate(4 + body.length).putInt(body.length).put(body).array();
    }

    static void feed(EmbeddedChannel channel, Envelope message) {
        channel.writeInbound(Unpooled.wrappedBuffer(frame(message)));
    }

    static Envelope readReply(EmbeddedChannel channel) throws Exception {
        var bytes = new ByteArrayOutputStream();
        ByteBuf part;
        while ((part = channel.readOutbound()) != null) {
            try {
                byte[] chunk = new byte[part.readableBytes()];
                part.readBytes(chunk);
                bytes.writeBytes(chunk);
            } finally { part.release(); }
        }
        byte[] wire = bytes.toByteArray();
        assertTrue(wire.length >= 4, "Expected a framed reply");
        assertEquals(wire.length - 4, ByteBuffer.wrap(wire).getInt());
        return Envelope.parser().parseFrom(wire, 4, wire.length - 4);
    }

    static void send(Socket socket, Envelope message) throws Exception {
        socket.getOutputStream().write(frame(message));
        socket.getOutputStream().flush();
    }

    static Envelope readReply(Socket socket) throws Exception {
        var input = new DataInputStream(socket.getInputStream());
        int length = input.readInt();
        assertTrue(length > 0 && length <= 65536);
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return Envelope.parseFrom(bytes);
    }
}
