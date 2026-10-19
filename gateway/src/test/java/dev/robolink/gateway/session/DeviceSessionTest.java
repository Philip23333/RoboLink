package dev.robolink.gateway.session;

import static org.junit.jupiter.api.Assertions.*;
import static dev.robolink.gateway.session.SessionTestSupport.*;

import dev.robolink.gateway.GatewayConfig;
import dev.robolink.protocol.v1.Envelope;
import dev.robolink.protocol.v1.HeartbeatReply;
import dev.robolink.protocol.v1.Register;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.IdleStateEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DeviceSessionTest {
    private final SessionRegistry registry = new SessionRegistry();
    private final DeviceCredentials credentials = DeviceCredentials.of(Map.of("RB-001", TEST_CREDENTIAL, "RB-002", "other-test-credential"));
    private final List<EmbeddedChannel> channels = new ArrayList<>();

    private EmbeddedChannel connection() { return connection(CONFIG, null); }

    private EmbeddedChannel connection(GatewayConfig config, DeferredClose delay) {
        var channel = new EmbeddedChannel();
        if (delay != null) { channel.pipeline().addLast(delay); }
        DeviceConnectionPipeline.install(channel.pipeline(), config, credentials, registry);
        channels.add(channel);
        return channel;
    }

    private String register(EmbeddedChannel channel) throws Exception {
        feed(channel, registration("RB-001"));
        return readReply(channel).getRegisterResult().getAssignedSessionId();
    }

    @AfterEach
    void closeConnections() {
        channels.forEach(channel -> {
            var deferred = channel.pipeline().get(DeferredClose.class);
            if (deferred != null) { deferred.release(); }
            channel.finishAndReleaseAll();
        });
    }

    @Test
    void registrationBindsIdentityAndDoesNotForwardCredential() throws Exception {
        var channel = connection();
        feed(channel, registration("RB-001"));
        var reply = readReply(channel);
        assertTrue(reply.getRegisterResult().getAccepted());
        assertEquals(3000, reply.getRegisterResult().getHeartbeatIntervalMs());
        assertEquals(10000, reply.getRegisterResult().getHeartbeatTimeoutMs());
        assertEquals(reply.getSessionId(), reply.getRegisterResult().getAssignedSessionId());
        assertFalse(reply.getSessionId().isEmpty());
        assertEquals("RB-001", reply.getRobotId());
        assertEquals(reply.getSessionId(), registry.find("RB-001").orElseThrow().sessionId());
        assertEquals(1, registry.onlineCount());
        assertNull(channel.readInbound());
    }

    @Test
    void badCredentialDoesNotReplaceExistingConnection() throws Exception {
        var current = connection();
        String session = register(current);
        var intruder = connection();
        feed(intruder, registration("RB-001").toBuilder().setRegister(Register.newBuilder().setCredential("wrong")).build());
        assertFalse(readReply(intruder).getRegisterResult().getAccepted());
        assertFalse(intruder.isActive());
        assertTrue(current.isActive());
        assertEquals(session, registry.find("RB-001").orElseThrow().sessionId());
    }

    @Test
    void unknownDeviceAndMalformedIdAreRejected() throws Exception {
        for (String id : new String[]{"RB-999", "", "RB-001\nforged"}) {
            var channel = connection();
            feed(channel, registration(id));
            assertFalse(readReply(channel).getRegisterResult().getAccepted());
            assertFalse(channel.isActive());
        }
        assertEquals(0, registry.onlineCount());
    }

    @Test
    void anotherDevicesCredentialCannotAuthenticate() throws Exception {
        var channel = connection();
        feed(channel, registration("RB-002"));
        assertFalse(readReply(channel).getRegisterResult().getAccepted());
        assertEquals(0, registry.onlineCount());
    }

    @Test
    void registrationCannotSupplyOldSessionId() throws Exception {
        var channel = connection();
        feed(channel, registration("RB-001").toBuilder().setSessionId("old-session").build());
        assertFalse(readReply(channel).getRegisterResult().getAccepted());
        assertFalse(channel.isActive());
    }

    @Test
    void telemetryBeforeRegistrationClosesConnection() {
        var channel = connection();
        feed(channel, telemetry("RB-001", "forged", 1));
        assertFalse(channel.isActive());
        assertEquals(0, registry.onlineCount());
    }

    @Test
    void registrationDeadlineIsNotExtendedByPartialBytes() {
        var channel = connection();
        byte[] bytes = frame(registration("RB-001"));
        channel.advanceTimeBy(4, TimeUnit.SECONDS);
        channel.writeInbound(Unpooled.wrappedBuffer(Arrays.copyOf(bytes, 2)));
        channel.advanceTimeBy(2, TimeUnit.SECONDS);
        channel.runScheduledPendingTasks();
        assertFalse(channel.isActive());
        assertEquals(0, registry.onlineCount());
    }

    @Test
    void successfulRegistrationCancelsRegistrationDeadline() throws Exception {
        var channel = connection();
        register(channel);
        channel.advanceTimeBy(6, TimeUnit.SECONDS);
        channel.runScheduledPendingTasks();
        assertTrue(channel.isActive());
        assertEquals(1, registry.onlineCount());
    }

    @Test
    void heartbeatEchoesDeviceSequenceInAcknowledgement() throws Exception {
        var channel = connection();
        String session = register(channel);
        feed(channel, heartbeat("RB-001", session, 5));
        var reply = readReply(channel);
        assertEquals(5, reply.getHeartbeatReply().getAcknowledgedSeq());
        assertEquals(session, reply.getSessionId());
        assertEquals(2, reply.getSeq());
        assertTrue(channel.isActive());
    }

    @Test
    void telemetryStoresLatestPoseAndHeartbeatPreservesIt() throws Exception {
        var channel = connection();
        String session = register(channel);
        var status = telemetry("RB-001", session, 1);
        feed(channel, status);
        feed(channel, heartbeat("RB-001", session, 2));
        readReply(channel);
        var snapshot = registry.onlineSnapshots().getFirst();
        assertEquals(status.getTelemetry(), snapshot.getLatestTelemetry());
        assertEquals(1, snapshot.getTelemetrySeq());
        assertTrue(snapshot.getOnline());
        assertEquals(session, snapshot.getSessionId());
    }

    @Test
    void duplicateAndOlderSequenceDoNotUpdateStateOrLiveness() throws Exception {
        var channel = connection();
        String session = register(channel);
        feed(channel, telemetry("RB-001", session, 10));
        var expected = registry.find("RB-001").orElseThrow().snapshot();
        for (int seq : new int[]{10, 9}) {
            feed(channel, telemetry("RB-001", session, seq).toBuilder()
                    .setTelemetry(expected.getLatestTelemetry().toBuilder().setBatteryPercent(1)).build());
        }
        assertSame(expected, registry.find("RB-001").orElseThrow().snapshot());
        assertTrue(channel.isActive());
    }

    @Test
    void unsignedSequencesCrossSignedLongBoundaryWithoutWrapping() throws Exception {
        var channel = connection();
        feed(channel, registration("RB-001").toBuilder().setSeq(Long.MAX_VALUE).build());
        String session = readReply(channel).getSessionId();
        feed(channel, heartbeat("RB-001", session, Long.MIN_VALUE));
        assertEquals(Long.MIN_VALUE, readReply(channel).getHeartbeatReply().getAcknowledgedSeq());
        feed(channel, heartbeat("RB-001", session, 1));
        assertNull(channel.readOutbound());
    }

    @Test
    void spoofedRobotIdentityClosesAndRemovesSession() throws Exception {
        var channel = connection();
        String session = register(channel);
        feed(channel, heartbeat("RB-002", session, 1));
        assertFalse(channel.isActive());
        assertTrue(registry.find("RB-001").isEmpty());
    }

    @Test
    void spoofedSessionIdClosesConnection() throws Exception {
        var channel = connection();
        register(channel);
        feed(channel, heartbeat("RB-001", "wrong-session", 1));
        assertFalse(channel.isActive());
        assertEquals(0, registry.onlineCount());
    }

    @Test
    void deviceCannotSendServerOnlyMessageTypes() throws Exception {
        var channel = connection();
        String session = register(channel);
        feed(channel, heartbeat("RB-001", session, 1).toBuilder()
                .setHeartbeatReply(HeartbeatReply.getDefaultInstance()).build());
        assertFalse(channel.isActive());
    }

    @Test
    void invalidTelemetryClosesWithoutPersistingIt() throws Exception {
        var channel = connection();
        String session = register(channel);
        var message = telemetry("RB-001", session, 1);
        feed(channel, message.toBuilder().setTelemetry(message.getTelemetry().toBuilder()
                .setPose(message.getTelemetry().getPose().toBuilder().setXM(Double.NaN))).build());
        assertFalse(channel.isActive());
        assertEquals(0, registry.onlineCount());
    }

    @Test
    void repeatedRegistrationOnSameConnectionIsRejected() throws Exception {
        var channel = connection();
        register(channel);
        feed(channel, registration("RB-001"));
        assertFalse(channel.isActive());
        assertEquals(0, registry.onlineCount());
    }

    @Test
    void replacementUsesFreshSessionAndResetsSequence() throws Exception {
        var old = connection();
        String oldSession = register(old);
        feed(old, heartbeat("RB-001", oldSession, 100));
        readReply(old);
        var replacement = connection();
        String current = register(replacement);
        assertNotEquals(oldSession, current);
        assertFalse(old.isActive());
        assertEquals(1, registry.onlineCount());
        feed(replacement, heartbeat("RB-001", current, 1));
        assertEquals(1, readReply(replacement).getHeartbeatReply().getAcknowledgedSeq());
    }

    @Test
    void delayedOldCleanupCannotRemoveNewSessionOrWriteOldTelemetry() throws Exception {
        var delay = new DeferredClose();
        var old = connection(CONFIG, delay);
        String oldSession = register(old);
        var replacement = connection();
        String current = register(replacement);
        assertTrue(old.isActive(), "old close is intentionally delayed by the test");
        feed(old, telemetry("RB-001", oldSession, 1));
        assertFalse(registry.find("RB-001").orElseThrow().snapshot().hasLatestTelemetry());
        delay.release();
        assertFalse(old.isActive());
        assertEquals(current, registry.find("RB-001").orElseThrow().sessionId());
        feed(replacement, heartbeat("RB-001", current, 1));
        assertTrue(readReply(replacement).hasHeartbeatReply());
    }

    @Test
    void idleEventClosesRegisteredConnectionAndCleansIndex() throws Exception {
        var channel = connection();
        register(channel);
        channel.pipeline().fireUserEventTriggered(IdleStateEvent.READER_IDLE_STATE_EVENT);
        assertFalse(channel.isActive());
        assertEquals(0, registry.onlineCount());
    }

    @Test
    void preRegistrationIdleEventDoesNotReplaceAbsoluteRegistrationDeadline() {
        var channel = connection();
        channel.pipeline().fireUserEventTriggered(IdleStateEvent.READER_IDLE_STATE_EVENT);
        assertTrue(channel.isActive());
        channel.advanceTimeBy(6, TimeUnit.SECONDS);
        channel.runScheduledPendingTasks();
        assertFalse(channel.isActive());
    }

    @Test
    void disconnectRemovesCurrentSession() throws Exception {
        var channel = connection();
        register(channel);
        channel.close();
        assertEquals(0, registry.onlineCount());
    }

    private static final class DeferredClose extends ChannelOutboundHandlerAdapter {
        private final List<Runnable> pending = new ArrayList<>();
        private boolean released;

        @Override
        public void close(ChannelHandlerContext ctx, ChannelPromise promise) {
            if (released) { ctx.close(promise); }
            else { pending.add(() -> ctx.close(promise)); }
        }

        void release() {
            released = true;
            pending.forEach(Runnable::run);
            pending.clear();
        }
    }
}
