package dev.robolink.gateway.control;

import static org.junit.jupiter.api.Assertions.*;

import dev.robolink.gateway.GatewayConfig;
import dev.robolink.gateway.session.DeviceConnectionPipeline;
import dev.robolink.gateway.session.DeviceCredentials;
import dev.robolink.gateway.session.SessionRegistry;
import dev.robolink.protocol.v1.*;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(12)
class ControlRuntimeTest {
    private final SessionRegistry sessions = new SessionRegistry();
    private ControlRuntime runtime;
    private final List<Device> devices = new ArrayList<>();

    private ControlRuntime start(int ackMs, int resultMs, int maxPending, int maxHistory, int ttlMs) {
        runtime = new ControlRuntime(new ControlConfig("127.0.0.1", 0, Set.of("http://localhost:5173"),
                ackMs, resultMs, 5000, maxPending, maxHistory, ttlMs, 8),
                ControlAccess.of(Map.of("operator", "operator-test", "limited", "limited-test"),
                        Map.of("operator", Set.of("RB-001", "RB-002"), "limited", Set.of("RB-001"))), sessions);
        return runtime;
    }

    private Client client() throws Exception { return client("operator", "operator-test"); }
    private Client client(String id, String token) throws Exception {
        if (runtime == null) { start(1500, 2000, 8, 32, 60000); }
        var client = new Client();
        client.send(Envelope.newBuilder().setProtocolVersion(1).setControlAuthenticate(ControlAuthenticate.newBuilder()
                .setControllerId(id).setCredential(token)).build());
        assertTrue(client.next(Envelope::hasControlAuthResult).getControlAuthResult().getAccepted());
        return client;
    }

    private Device device(String id) throws Exception {
        var device = new Device(id);
        devices.add(device);
        return device;
    }

    private static Envelope move(String id, String robot) {
        return Envelope.newBuilder().setProtocolVersion(1).setRobotId(robot).setCommandRequest(CommandRequest.newBuilder()
                .setCommandId(id).setAction(Action.ACTION_MOVE_FORWARD)
                .setMotion(MotionParameters.newBuilder().setSpeedMps(0.2).setDurationMs(1000))).build();
    }

    @AfterEach
    void cleanup() {
        devices.forEach(device -> device.channel.finishAndReleaseAll());
        if (runtime != null) { runtime.close(); }
    }

    private final class Client implements ControlSink {
        final LinkedBlockingQueue<Envelope> messages = new LinkedBlockingQueue<>();
        final List<Envelope> delivered = new CopyOnWriteArrayList<>();
        final ControlRuntime.Peer peer = runtime.connected(this);
        volatile boolean active = true;
        volatile boolean writable = true;
        volatile boolean hold;
        public boolean active() { return active; }
        public boolean writable() { return writable; }
        public void close() { active = false; }
        public void write(Envelope message, Consumer<Boolean> completed) {
            delivered.add(message);
            messages.add(message);
            if (!hold) { completed.accept(true); }
        }
        void send(Envelope message) { runtime.receive(peer, message); }
        Envelope next(Predicate<Envelope> condition) throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (System.nanoTime() < deadline) {
                var message = messages.poll(20, TimeUnit.MILLISECONDS);
                if (message != null && condition.test(message)) { return message; }
            }
            throw new AssertionError("No expected control response");
        }
        Envelope phase(String id, CommandPhase phase) throws Exception {
            return next(message -> message.hasCommandStatus() && message.getCommandStatus().getCommandId().equals(id)
                    && message.getCommandStatus().getPhase() == phase);
        }
        void snapshot() throws Exception {
            send(Envelope.newBuilder().setProtocolVersion(1).setSubscribe(Subscribe.getDefaultInstance()).build());
            next(Envelope::hasSnapshot);
        }
    }

    private final class Device {
        final String id;
        final EmbeddedChannel channel = new EmbeddedChannel();
        final String session;
        long seq;
        Device(String id) throws Exception {
            this.id = id;
            DeviceConnectionPipeline.install(channel.pipeline(), new GatewayConfig("127.0.0.1", 0, 5000, 3000, 10000),
                    DeviceCredentials.of(Map.of(id, "device-test")), sessions);
            feed(Envelope.newBuilder().setProtocolVersion(1).setRobotId(id)
                    .setRegister(Register.newBuilder().setCredential("device-test")).build());
            session = read().getRegisterResult().getAssignedSessionId();
        }
        void feed(Envelope message) {
            byte[] body = message.toByteArray();
            channel.writeInbound(Unpooled.wrappedBuffer(ByteBuffer.allocate(4 + body.length).putInt(body.length).put(body).array()));
        }
        Envelope read() throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (System.nanoTime() < deadline) {
                channel.runPendingTasks();
                var bytes = new ByteArrayOutputStream();
                ByteBuf part;
                while ((part = channel.readOutbound()) != null) {
                    try { byte[] chunk = new byte[part.readableBytes()]; part.readBytes(chunk); bytes.writeBytes(chunk); }
                    finally { part.release(); }
                }
                if (bytes.size() > 0) {
                    byte[] wire = bytes.toByteArray();
                    assertEquals(wire.length - 4, ByteBuffer.wrap(wire).getInt());
                    return Envelope.parser().parseFrom(wire, 4, wire.length - 4);
                }
                Thread.sleep(2);
            }
            throw new AssertionError("No device command");
        }
        void noCommand() throws Exception {
            for (int i = 0; i < 20; i++) { channel.runPendingTasks(); Thread.sleep(2); }
            assertNull(channel.readOutbound());
        }
        Envelope.Builder message() { return Envelope.newBuilder().setProtocolVersion(1).setRobotId(id).setSessionId(session).setSeq(++seq); }
        void ack(String id, boolean accepted) { feed(message().setCommandAck(CommandAck.newBuilder().setCommandId(id).setAccepted(accepted)).build()); }
        void result(String id, CommandOutcome outcome) { feed(message().setCommandResult(CommandResult.newBuilder().setCommandId(id).setOutcome(outcome)).build()); }
        void telemetry(int battery) { feed(message().setTelemetry(Telemetry.newBuilder().setBatteryPercent(battery)
                .setPose(Pose.getDefaultInstance()).setState(RobotState.ROBOT_STATE_IDLE)).build()); }
    }

    @Test
    void routesCommandAndOnlyOwnerReceivesAckAndSuccessfulResult() throws Exception {
        var owner = client(); var other = client(); var robot = device("RB-001");
        owner.send(move("cmd-1", robot.id));
        assertEquals(robot.session, robot.read().getSessionId());
        owner.phase("cmd-1", CommandPhase.COMMAND_PHASE_SENT);
        robot.ack("cmd-1", true);
        owner.phase("cmd-1", CommandPhase.COMMAND_PHASE_ACKED);
        robot.result("cmd-1", CommandOutcome.COMMAND_OUTCOME_SUCCEEDED);
        owner.phase("cmd-1", CommandPhase.COMMAND_PHASE_SUCCEEDED);
        other.snapshot();
        assertTrue(other.delivered.stream().noneMatch(message -> message.hasCommandStatus() || message.hasCommandAck() || message.hasCommandResult()));
    }

    @Test
    void sameIdReplaysStateWithoutExecutingAgainAndConflictingReuseIsRejected() throws Exception {
        var owner = client(); var robot = device("RB-001"); var request = move("repeat", robot.id);
        owner.send(request); robot.read(); owner.phase("repeat", CommandPhase.COMMAND_PHASE_SENT);
        owner.send(request); owner.phase("repeat", CommandPhase.COMMAND_PHASE_SENT); robot.noCommand();
        owner.send(request.toBuilder().setCommandRequest(request.getCommandRequest().toBuilder()
                .setMotion(request.getCommandRequest().getMotion().toBuilder().setSpeedMps(0.3))).build());
        assertEquals("COMMAND_ID_IN_USE", owner.next(Envelope::hasError).getError().getCode());
        robot.ack("repeat", true); owner.phase("repeat", CommandPhase.COMMAND_PHASE_ACKED);
        robot.result("repeat", CommandOutcome.COMMAND_OUTCOME_SUCCEEDED); owner.phase("repeat", CommandPhase.COMMAND_PHASE_SUCCEEDED);
        owner.send(request); owner.phase("repeat", CommandPhase.COMMAND_PHASE_SUCCEEDED); robot.noCommand();
    }

    @Test
    void deviceRejectionAndFailureRemainDistinct() throws Exception {
        var owner = client(); var robot = device("RB-001");
        owner.send(move("reject", robot.id)); robot.read(); robot.ack("reject", false);
        owner.phase("reject", CommandPhase.COMMAND_PHASE_REJECTED);
        owner.send(move("failure", robot.id)); robot.read(); robot.ack("failure", true);
        owner.phase("failure", CommandPhase.COMMAND_PHASE_ACKED);
        robot.result("failure", CommandOutcome.COMMAND_OUTCOME_FAILED);
        owner.phase("failure", CommandPhase.COMMAND_PHASE_FAILED);
    }

    @Test
    void offlineAndOutOfScopeDevicesAreRejectedBeforeDispatch() throws Exception {
        var owner = client("limited", "limited-test"); var robot = device("RB-002");
        owner.send(move("forbidden", robot.id));
        assertEquals("FORBIDDEN", owner.phase("forbidden", CommandPhase.COMMAND_PHASE_REJECTED).getCommandStatus().getReason());
        robot.noCommand();
        owner.send(move("offline", "RB-001"));
        assertEquals("OFFLINE", owner.phase("offline", CommandPhase.COMMAND_PHASE_REJECTED).getCommandStatus().getReason());
    }

    @Test
    void invalidActionParametersNeverReachDevice() throws Exception {
        var owner = client(); var robot = device("RB-001");
        var invalid = List.of(
                CommandRequest.newBuilder().setAction(Action.ACTION_MOVE_FORWARD).build(),
                CommandRequest.newBuilder().setAction(Action.ACTION_UNSPECIFIED).build(),
                CommandRequest.newBuilder().setAction(Action.ACTION_STOP).setMotion(MotionParameters.getDefaultInstance()).build(),
                move("unused", robot.id).getCommandRequest().toBuilder().setMotion(MotionParameters.newBuilder().setSpeedMps(Double.NaN).setDurationMs(1000)).build(),
                move("unused", robot.id).getCommandRequest().toBuilder().setMotion(MotionParameters.newBuilder().setSpeedMps(0.6).setDurationMs(1000)).build(),
                move("unused", robot.id).getCommandRequest().toBuilder().setMotion(MotionParameters.newBuilder().setSpeedMps(0.2).setDurationMs(-1)).build());
        for (int i = 0; i < invalid.size(); i++) {
            String id = "invalid-" + i;
            owner.send(move(id, robot.id).toBuilder().setCommandRequest(invalid.get(i).toBuilder().setCommandId(id)).build());
            assertEquals("INVALID_PARAMETERS", owner.phase(id, CommandPhase.COMMAND_PHASE_REJECTED).getCommandStatus().getReason());
        }
        robot.noCommand();
    }

    @Test
    void routingMetadataCannotTurnAValidSizeRequestIntoAnOversizedDeviceFrame() throws Exception {
        var owner = client(); var robot = device("RB-001"); var base = move("wire-size", robot.id);
        Envelope request = null;
        for (int padding = 65400; padding < 65536; padding++) {
            var unknown = com.google.protobuf.UnknownFieldSet.newBuilder().addField(100,
                    com.google.protobuf.UnknownFieldSet.Field.newBuilder().addLengthDelimited(
                            com.google.protobuf.ByteString.copyFrom(new byte[padding])).build()).build();
            var candidate = base.toBuilder().setCommandRequest(base.getCommandRequest().toBuilder().setUnknownFields(unknown)).build();
            if (candidate.getSerializedSize() <= 65536) { request = candidate; }
            else { break; }
        }
        assertNotNull(request);
        assertTrue(request.getSerializedSize() <= 65536);
        assertTrue(request.toBuilder().setSessionId(robot.session).setSeq(-1L).build().getSerializedSize() > 65536);
        owner.send(request);
        assertEquals("COMMAND_TOO_LARGE", owner.phase("wire-size", CommandPhase.COMMAND_PHASE_REJECTED).getCommandStatus().getReason());
        robot.noCommand();
        assertTrue(robot.channel.isActive());
    }

    @Test
    void ackTimeoutIsTerminalAndNewMotionRequiresStopOrReconnect() throws Exception {
        start(300, 300, 8, 32, 60000);
        var owner = client(); var robot = device("RB-001"); var request = move("timeout", robot.id);
        owner.send(request); robot.read(); owner.phase("timeout", CommandPhase.COMMAND_PHASE_ACK_TIMEOUT);
        robot.ack("timeout", true); robot.result("timeout", CommandOutcome.COMMAND_OUTCOME_SUCCEEDED);
        owner.send(request); owner.phase("timeout", CommandPhase.COMMAND_PHASE_ACK_TIMEOUT); robot.noCommand();
        owner.send(move("unsafe-repeat", robot.id)); owner.phase("unsafe-repeat", CommandPhase.COMMAND_PHASE_REJECTED);
        owner.send(move("stop", robot.id).toBuilder().setCommandRequest(CommandRequest.newBuilder().setCommandId("stop").setAction(Action.ACTION_STOP)).build());
        assertEquals(Action.ACTION_STOP, robot.read().getCommandRequest().getAction());
        robot.ack("stop", true); owner.phase("stop", CommandPhase.COMMAND_PHASE_ACKED);
        robot.result("stop", CommandOutcome.COMMAND_OUTCOME_SUCCEEDED); owner.phase("stop", CommandPhase.COMMAND_PHASE_SUCCEEDED);
        owner.send(move("safe-again", robot.id)); assertEquals("safe-again", robot.read().getCommandRequest().getCommandId());
    }

    @Test
    void resultTimeoutIsSeparateFromAckTimeout() throws Exception {
        start(1000, 200, 8, 32, 60000);
        var owner = client(); var robot = device("RB-001");
        owner.send(move("result-timeout", robot.id)); robot.read(); robot.ack("result-timeout", true);
        owner.phase("result-timeout", CommandPhase.COMMAND_PHASE_ACKED);
        owner.phase("result-timeout", CommandPhase.COMMAND_PHASE_RESULT_TIMEOUT);
    }

    @Test
    void resultBeforeAckIsUnknownInsteadOfSuccess() throws Exception {
        var owner = client(); var robot = device("RB-001");
        owner.send(move("early", robot.id)); robot.read(); robot.result("early", CommandOutcome.COMMAND_OUTCOME_SUCCEEDED);
        assertEquals("RESULT_BEFORE_ACK", owner.phase("early", CommandPhase.COMMAND_PHASE_UNKNOWN).getCommandStatus().getReason());
    }

    @Test
    void stopMayInterruptBusyMotionButSecondMotionIsRejected() throws Exception {
        var owner = client(); var robot = device("RB-001");
        owner.send(move("moving", robot.id)); robot.read(); robot.ack("moving", true); owner.phase("moving", CommandPhase.COMMAND_PHASE_ACKED);
        owner.send(move("busy", robot.id)); owner.phase("busy", CommandPhase.COMMAND_PHASE_REJECTED);
        owner.send(move("stop", robot.id).toBuilder().setCommandRequest(CommandRequest.newBuilder().setCommandId("stop").setAction(Action.ACTION_STOP)).build());
        robot.read(); robot.ack("stop", true); owner.phase("stop", CommandPhase.COMMAND_PHASE_ACKED);
        robot.result("moving", CommandOutcome.COMMAND_OUTCOME_INTERRUPTED); owner.phase("moving", CommandPhase.COMMAND_PHASE_INTERRUPTED);
        robot.result("stop", CommandOutcome.COMMAND_OUTCOME_SUCCEEDED); owner.phase("stop", CommandPhase.COMMAND_PHASE_SUCCEEDED);
    }

    @Test
    void ackFromAnotherRobotCannotCompleteCommand() throws Exception {
        var owner = client(); var robot = device("RB-001"); var other = device("RB-002");
        owner.send(move("target", robot.id)); robot.read(); owner.phase("target", CommandPhase.COMMAND_PHASE_SENT);
        other.ack("target", true);
        owner.snapshot();
        robot.ack("target", true); owner.phase("target", CommandPhase.COMMAND_PHASE_ACKED);
    }

    @Test
    void deviceReplacementMakesOldCommandUnknownAndNeverReplaysIt() throws Exception {
        var owner = client(); var old = device("RB-001");
        owner.send(move("old-command", old.id)); old.read();
        var current = device("RB-001");
        owner.phase("old-command", CommandPhase.COMMAND_PHASE_UNKNOWN);
        assertNotEquals(old.session, current.session); current.noCommand();
    }

    @Test
    void controllerDisconnectDoesNotTransferCommandToAnotherClient() throws Exception {
        var owner = client(); var robot = device("RB-001"); var request = move("private", robot.id);
        owner.send(request); robot.read(); owner.phase("private", CommandPhase.COMMAND_PHASE_SENT);
        runtime.disconnected(owner.peer);
        var other = client(); other.send(request);
        assertEquals("COMMAND_ID_IN_USE", other.next(Envelope::hasError).getError().getCode()); robot.noCommand();
    }

    @Test
    void pendingAndHistoryCapacityDoNotEvictUnexpiredDeduplication() throws Exception {
        start(1500, 2000, 1, 1, 60000);
        var owner = client(); var robot = device("RB-001");
        owner.send(move("first", robot.id)); robot.read();
        owner.send(move("second", robot.id)); assertEquals("CAPACITY", owner.phase("second", CommandPhase.COMMAND_PHASE_REJECTED).getCommandStatus().getReason());
        robot.ack("first", false); owner.phase("first", CommandPhase.COMMAND_PHASE_REJECTED);
        owner.send(move("third", robot.id)); assertEquals("CAPACITY", owner.phase("third", CommandPhase.COMMAND_PHASE_REJECTED).getCommandStatus().getReason());
        owner.send(move("first", robot.id)); owner.phase("first", CommandPhase.COMMAND_PHASE_REJECTED); robot.noCommand();
    }

    @Test
    void expiredHistoryReleasesCapacityAndDoesNotClaimPermanentDeduplication() throws Exception {
        start(1500, 2000, 1, 1, 300);
        var owner = client(); var robot = device("RB-001"); var request = move("short-history", robot.id);
        owner.send(request); robot.read(); robot.ack("short-history", false);
        owner.phase("short-history", CommandPhase.COMMAND_PHASE_REJECTED);
        owner.send(move("full-history", robot.id));
        assertEquals("CAPACITY", owner.phase("full-history", CommandPhase.COMMAND_PHASE_REJECTED).getCommandStatus().getReason());
        Thread.sleep(350);
        owner.send(request);
        assertEquals("short-history", robot.read().getCommandRequest().getCommandId());
    }

    @Test
    void nonIdleTelemetryBlocksNewMotionButAllowsStop() throws Exception {
        var owner = client(); var robot = device("RB-001");
        robot.feed(robot.message().setTelemetry(Telemetry.newBuilder().setBatteryPercent(87)
                .setPose(Pose.getDefaultInstance()).setState(RobotState.ROBOT_STATE_ERROR)).build());
        owner.snapshot();
        owner.send(move("not-idle", robot.id));
        assertEquals("DEVICE_NOT_IDLE", owner.phase("not-idle", CommandPhase.COMMAND_PHASE_REJECTED).getCommandStatus().getReason());
        owner.send(move("stop-error", robot.id).toBuilder().setCommandRequest(CommandRequest.newBuilder()
                .setCommandId("stop-error").setAction(Action.ACTION_STOP)).build());
        assertEquals(Action.ACTION_STOP, robot.read().getCommandRequest().getAction());
    }

    @Test
    void snapshotAndTelemetryAreScopedAndOfflineEventsArePublished() throws Exception {
        var owner = client("limited", "limited-test"); var robot = device("RB-001"); var other = device("RB-002");
        owner.snapshot();
        robot.telemetry(87); other.telemetry(63);
        assertEquals("RB-001", owner.next(Envelope::hasTelemetry).getRobotId());
        robot.channel.close();
        assertFalse(owner.next(Envelope::hasDeviceStatusChanged).getDeviceStatusChanged().getOnline());
        owner.send(Envelope.newBuilder().setProtocolVersion(1).setSubscribe(Subscribe.newBuilder().addRobotIds("RB-002")).build());
        assertEquals("FORBIDDEN", owner.next(Envelope::hasError).getError().getCode());
    }

    @Test
    void slowConsumerTelemetryCoalescesToLatestValue() throws Exception {
        var owner = client(); var barrier = client(); var robot = device("RB-001"); owner.snapshot();
        owner.writable = false;
        for (int battery = 1; battery <= 100; battery++) { robot.telemetry(battery); }
        // Another peer's snapshot proves prior publications were processed without clearing this peer's cache.
        barrier.snapshot();
        assertTrue(owner.messages.isEmpty());
        owner.writable = true;
        assertEquals(100, owner.next(Envelope::hasTelemetry).getTelemetry().getBatteryPercent());
        assertNull(owner.messages.poll(150, TimeUnit.MILLISECONDS));
    }

    @Test
    void reliableQueueOverflowDisconnectsInsteadOfDroppingResults() throws Exception {
        var owner = client(); owner.writable = false;
        for (int i = 0; i < 70; i++) { owner.send(Envelope.newBuilder().setProtocolVersion(1).setSubscribe(Subscribe.getDefaultInstance()).build()); }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (owner.active && System.nanoTime() < deadline) { Thread.sleep(5); }
        assertFalse(owner.active);
    }

    @Test
    void stalledInFlightWriteIsDisconnected() throws Exception {
        var owner = client(); owner.hold = true;
        owner.send(Envelope.newBuilder().setProtocolVersion(1).setSubscribe(Subscribe.getDefaultInstance()).build());
        owner.next(Envelope::hasSnapshot);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (owner.active && System.nanoTime() < deadline) { Thread.sleep(5); }
        assertFalse(owner.active);
    }

    @Test
    void diagnosticsRecordActualStagesAndStayWithOwningController() throws Exception {
        var owner = client(); var other = client(); var robot = device("RB-001");
        owner.send(Envelope.newBuilder().setProtocolVersion(1).setSubscribe(Subscribe.newBuilder().setIncludeDiagnostics(true)).build());
        owner.next(Envelope::hasSnapshot);
        other.send(Envelope.newBuilder().setProtocolVersion(1).setSubscribe(Subscribe.newBuilder().setIncludeDiagnostics(true)).build());
        other.next(Envelope::hasSnapshot);
        owner.send(move("observed", robot.id));
        robot.read();
        robot.ack("observed", true);
        owner.phase("observed", CommandPhase.COMMAND_PHASE_ACKED);
        robot.result("observed", CommandOutcome.COMMAND_OUTCOME_SUCCEEDED);
        owner.phase("observed", CommandPhase.COMMAND_PHASE_SUCCEEDED);
        other.snapshot(); // Coordinator barrier after previous callbacks.
        var traces = owner.delivered.stream().filter(Envelope::hasCommandTrace).toList();
        assertEquals(Set.of("GATEWAY_WS_RECEIVED", "GATEWAY_TCP_WRITE_COMPLETED", "GATEWAY_ACK_RECEIVED", "GATEWAY_RESULT_RECEIVED"),
                traces.stream().map(message -> message.getCommandTrace().getStage()).collect(java.util.stream.Collectors.toSet()));
        assertTrue(traces.stream().allMatch(message -> message.getRobotId().equals(robot.id)
                && message.getSessionId().equals(robot.session) && message.getCommandTrace().getObservedAtMs() > 0));
        assertTrue(other.delivered.stream().noneMatch(Envelope::hasCommandTrace));
    }

    @Test
    void diagnosticsAreOptInAndCannotBypassDeviceScope() throws Exception {
        var owner = client("limited", "limited-test"); var robot = device("RB-001"); device("RB-002");
        owner.snapshot();
        owner.send(move("plain", robot.id)); robot.read(); robot.ack("plain", false);
        owner.phase("plain", CommandPhase.COMMAND_PHASE_REJECTED);
        assertTrue(owner.delivered.stream().noneMatch(Envelope::hasCommandTrace));
        owner.send(Envelope.newBuilder().setProtocolVersion(1).setSubscribe(Subscribe.newBuilder()
                .addRobotIds("RB-002").setIncludeDiagnostics(true)).build());
        assertEquals("FORBIDDEN", owner.next(Envelope::hasError).getError().getCode());
        owner.send(move("forbidden-trace", "RB-002"));
        owner.phase("forbidden-trace", CommandPhase.COMMAND_PHASE_REJECTED);
        assertTrue(owner.delivered.stream().noneMatch(Envelope::hasCommandTrace));
    }
}
