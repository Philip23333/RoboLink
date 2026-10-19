package dev.robolink.gateway.session;

import dev.robolink.protocol.v1.Envelope;
import dev.robolink.protocol.v1.RobotSnapshot;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Online index shared by connection event loops; no database work in the forwarding path. */
public final class SessionRegistry {
    private final ConcurrentHashMap<String, DeviceSession> sessions = new ConcurrentHashMap<>();
    private volatile Observer observer = new Observer() {};

    public interface Observer {
        default void attached(DeviceSession session) {}
        default void detached(DeviceSession session) {}
        default void received(DeviceSession session, Envelope message) {}
    }

    public void observe(Observer observer) { this.observer = observer; }

    void attach(DeviceSession session) {
        var previous = sessions.put(session.robotId(), session);
        observer.attached(session);
        if (previous != null) { previous.channel().close(); }
        if (!session.channel().isActive()) { remove(session); }
    }

    boolean accept(DeviceSession session, Envelope message, long observedAtMs) {
        var accepted = new AtomicBoolean();
        sessions.computeIfPresent(session.robotId(), (id, current) -> {
            if (current == session && current.channel().isActive()) {
                current.accept(message, observedAtMs);
                accepted.set(true);
            }
            return current;
        });
        if (accepted.get()) { observer.received(session, message); }
        return accepted.get();
    }

    void remove(DeviceSession session) {
        // An old connection must not remove a replacement connection's entry.
        if (sessions.remove(session.robotId(), session)) { observer.detached(session); }
    }

    public Optional<DeviceSession> find(String robotId) {
        return Optional.ofNullable(sessions.get(robotId)).filter(value -> value.channel().isActive());
    }

    /** Callback must be short and nonblocking; replacement cannot interleave with dispatch. */
    public boolean runIfCurrent(DeviceSession session, Runnable action) {
        var ran = new AtomicBoolean();
        sessions.computeIfPresent(session.robotId(), (id, current) -> {
            if (current == session && current.channel().isActive()) { action.run(); ran.set(true); }
            return current;
        });
        return ran.get();
    }

    public List<RobotSnapshot> onlineSnapshots() {
        return sessions.values().stream().filter(value -> value.channel().isActive())
                .map(DeviceSession::snapshot).toList();
    }

    public int onlineCount() { return onlineSnapshots().size(); }
}
