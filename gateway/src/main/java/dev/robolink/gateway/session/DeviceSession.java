package dev.robolink.gateway.session;

import dev.robolink.protocol.v1.Envelope;
import dev.robolink.protocol.v1.RobotSnapshot;
import io.netty.channel.Channel;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public final class DeviceSession {
    private final String robotId;
    private final String sessionId = UUID.randomUUID().toString();
    private final Channel channel;
    private volatile RobotSnapshot snapshot;
    private final AtomicLong gatewaySequence = new AtomicLong();

    DeviceSession(String robotId, Channel channel, long observedAtMs) {
        this.robotId = robotId;
        this.channel = channel;
        this.snapshot = RobotSnapshot.newBuilder().setRobotId(robotId).setSessionId(sessionId)
                .setOnline(true).setLastSeenAtMs(observedAtMs).build();
    }

    public String robotId() { return robotId; }
    public String sessionId() { return sessionId; }
    public Channel channel() { return channel; }
    public RobotSnapshot snapshot() { return snapshot; }
    public long nextGatewaySeq() { return gatewaySequence.incrementAndGet(); }

    // Called only under the registry's atomic per-robot operation.
    void accept(Envelope message, long observedAtMs) {
        var next = snapshot.toBuilder().setLastSeenAtMs(observedAtMs);
        if (message.hasTelemetry()) {
            next.setLatestTelemetry(message.getTelemetry()).setTelemetrySeq(message.getSeq());
        }
        snapshot = next.build();
    }
}
