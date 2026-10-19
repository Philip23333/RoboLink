package dev.robolink.gateway.session;

import dev.robolink.protocol.v1.Envelope;
import dev.robolink.protocol.v1.RobotSnapshot;
import io.netty.channel.Channel;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class DeviceSession {
    private final String robotId;
    private final String sessionId = UUID.randomUUID().toString();
    private final Channel channel;
    private final AtomicReference<RobotSnapshot> snapshot = new AtomicReference<>();
    private final AtomicLong gatewaySequence = new AtomicLong();

    DeviceSession(String robotId, Channel channel, long observedAtMs) {
        this.robotId = robotId;
        this.channel = channel;
        this.snapshot.set(RobotSnapshot.newBuilder().setRobotId(robotId).setSessionId(sessionId)
                .setOnline(true).setLastSeenAtMs(observedAtMs).setConnectedAtMs(observedAtMs)
                .setReceivedMessages(1).setRemoteAddress(String.valueOf(channel.remoteAddress())).build());
    }

    public String robotId() { return robotId; }
    public String sessionId() { return sessionId; }
    public Channel channel() { return channel; }
    public RobotSnapshot snapshot() { return snapshot.get(); }
    public void recordedWrite() { snapshot.updateAndGet(value -> value.toBuilder().setSentMessages(value.getSentMessages() + 1).build()); }
    public long nextGatewaySeq() { return gatewaySequence.incrementAndGet(); }

    // Called only under the registry's atomic per-robot operation.
    void accept(Envelope message, long observedAtMs) {
        snapshot.updateAndGet(value -> {
            var next = value.toBuilder().setLastSeenAtMs(observedAtMs).setReceivedMessages(value.getReceivedMessages() + 1);
            if (message.hasHeartbeat()) { next.setLastHeartbeatAtMs(observedAtMs); }
            if (message.hasTelemetry()) {
                next.setLatestTelemetry(message.getTelemetry()).setTelemetrySeq(message.getSeq()).setTelemetryObservedAtMs(observedAtMs);
            }
            return next.build();
        });
    }
}
