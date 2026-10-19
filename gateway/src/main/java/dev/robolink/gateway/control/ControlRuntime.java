package dev.robolink.gateway.control;

import dev.robolink.gateway.session.DeviceSession;
import dev.robolink.gateway.session.SessionRegistry;
import dev.robolink.protocol.v1.*;
import io.netty.util.concurrent.DefaultEventExecutor;
import io.netty.util.concurrent.DefaultThreadFactory;
import io.netty.util.concurrent.RejectedExecutionHandlers;
import java.util.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/** One bounded coordinator owns command states, subscriptions and controller mailboxes. */
public final class ControlRuntime implements SessionRegistry.Observer, AutoCloseable {
    static final int RELIABLE_LIMIT = 64;
    static final long STALL_NANOS = TimeUnit.SECONDS.toNanos(3);
    private final ControlConfig config;
    private final ControlAccess access;
    private final SessionRegistry sessions;
    private final DefaultEventExecutor executor = new DefaultEventExecutor(null,
            new DefaultThreadFactory("robolink-control"), 8192, RejectedExecutionHandlers.reject());
    private final Map<String, Peer> peers = new HashMap<>();
    private final Map<String, Pending> pending = new HashMap<>();
    private final Map<String, Completed> history = new HashMap<>();
    private final Map<String, DeviceSession> uncertain = new HashMap<>();
    private final io.netty.util.concurrent.ScheduledFuture<?> tick;

    public ControlRuntime(ControlConfig config, ControlAccess access, SessionRegistry sessions) {
        this.config = config;
        this.access = access;
        this.sessions = sessions;
        sessions.observe(this);
        tick = executor.scheduleAtFixedRate(this::maintain, 50, 50, TimeUnit.MILLISECONDS);
    }

    public static final class Peer {
        private final String id = UUID.randomUUID().toString();
        private final ControlSink sink;
        private final long connectedAt = System.nanoTime();
        private Set<String> allowed;
        private Set<String> subscribed = Set.of();
        private final ArrayDeque<Envelope> reliable = new ArrayDeque<>();
        private final LinkedHashMap<String, Envelope> telemetry = new LinkedHashMap<>();
        private boolean inFlight;
        private boolean closed;
        private boolean closing;
        private long blockedSince;
        private Peer(ControlSink sink) { this.sink = sink; }
    }

    private static final class Pending {
        final Peer owner;
        final Envelope request;
        final DeviceSession device;
        Envelope latest;
        io.netty.util.concurrent.ScheduledFuture<?> deadline;
        // Only these two fields cross executor boundaries, under this object's monitor.
        boolean allowDispatch = true;
        boolean attempted;
        Pending(Peer owner, Envelope request, DeviceSession device) {
            this.owner = owner; this.request = request; this.device = device;
        }
    }

    private record Completed(String ownerId, String robotId, CommandRequest request, Envelope status, long expiresAt) {}

    public Peer connected(ControlSink sink) {
        var peer = new Peer(sink);
        post(() -> {
            if (peers.size() >= config.maxClients()) { disconnect(peer); }
            else { peers.put(peer.id, peer); }
        }, sink::close);
        return peer;
    }

    public void disconnected(Peer peer) { post(() -> disconnect(peer), peer.sink::close); }

    public void receive(Peer peer, Envelope message) {
        post(() -> {
            if (peer.closed || peer.closing) { return; }
            if (message.getProtocolVersion() != 1 || !message.getSessionId().isEmpty()) { disconnect(peer); return; }
            if (peer.allowed == null) {
                if (!message.hasControlAuthenticate() || !message.getRobotId().isEmpty()) { disconnect(peer); return; }
                var auth = message.getControlAuthenticate();
                peer.allowed = access.authenticate(auth.getControllerId(), auth.getCredential());
                var result = ControlAuthResult.newBuilder().setAccepted(peer.allowed != null);
                if (peer.allowed == null) { result.setReason("AUTHENTICATION_REJECTED"); peer.closing = true; }
                else { result.setControlSessionId(peer.id).addAllAllowedRobotIds(new TreeSet<>(peer.allowed)); }
                reliable(peer, base().setControlAuthResult(result).build());
            } else if (message.hasSubscribe() && message.getRobotId().isEmpty()) {
                subscribe(peer, message.getSubscribe());
            } else if (message.hasCommandRequest()) {
                submit(peer, message);
            } else { disconnect(peer); }
        }, peer.sink::close);
    }

    private void subscribe(Peer peer, Subscribe request) {
        var selected = request.getRobotIdsCount() == 0 ? peer.allowed : new HashSet<>(request.getRobotIdsList());
        if (!peer.allowed.containsAll(selected)) { error(peer, "", "FORBIDDEN"); return; }
        peer.subscribed = Set.copyOf(selected);
        peer.telemetry.clear();
        var snapshot = Snapshot.newBuilder().setObservedAtMs(System.currentTimeMillis());
        for (String robot : new TreeSet<>(selected)) {
            snapshot.addRobots(sessions.find(robot).map(DeviceSession::snapshot).orElseGet(() ->
                    RobotSnapshot.newBuilder().setRobotId(robot).setOnline(false).build()));
        }
        reliable(peer, base().setSnapshot(snapshot).build());
    }

    private void submit(Peer peer, Envelope request) {
        String id = request.getCommandRequest().getCommandId();
        if (!dev.robolink.gateway.session.DeviceCredentials.validRobotId(id)) { error(peer, "", "INVALID_COMMAND_ID"); return; }
        if (!peer.allowed.contains(request.getRobotId())) { reject(peer, request, "FORBIDDEN", false); return; }
        pruneHistory();
        var existing = pending.get(id);
        if (existing != null) {
            if (existing.owner == peer && same(existing.request, request)) { reliable(peer, existing.latest); }
            else { error(peer, id, "COMMAND_ID_IN_USE"); }
            return;
        }
        var completed = history.get(id);
        if (completed != null) {
            if (completed.ownerId.equals(peer.id) && completed.robotId.equals(request.getRobotId())
                    && completed.request.equals(request.getCommandRequest())) { reliable(peer, completed.status); }
            else { error(peer, id, "COMMAND_ID_IN_USE"); }
            return;
        }
        if (pending.size() >= config.maxPending() || pending.size() + history.size() >= config.maxHistory()) {
            reject(peer, request, "CAPACITY", false); return;
        }
        if (!validCommand(request.getCommandRequest())) { reject(peer, request, "INVALID_PARAMETERS", true); return; }
        var device = sessions.find(request.getRobotId()).orElse(null);
        if (device == null) { reject(peer, request, "OFFLINE", true); return; }
        boolean stop = request.getCommandRequest().getAction() == Action.ACTION_STOP;
        boolean busy = pending.values().stream().anyMatch(value -> value.device == device
                && (!stop || value.request.getCommandRequest().getAction() == Action.ACTION_STOP));
        if (busy || (!stop && uncertain.get(device.robotId()) == device)) { reject(peer, request, "BUSY_OR_UNCERTAIN", true); return; }
        if (!stop && device.snapshot().hasLatestTelemetry()
                && device.snapshot().getLatestTelemetry().getState() != RobotState.ROBOT_STATE_IDLE) {
            reject(peer, request, "DEVICE_NOT_IDLE", true); return;
        }
        // Routing adds a device session and gateway sequence; validate the resulting wire size too.
        var largestRouted = base().setRobotId(device.robotId()).setSessionId(device.sessionId()).setSeq(-1L)
                .setCommandRequest(request.getCommandRequest()).build();
        if (largestRouted.getSerializedSize() > 65536) { reject(peer, request, "COMMAND_TOO_LARGE", true); return; }
        var command = new Pending(peer, request, device);
        pending.put(id, command);
        command.latest = status(request, device.sessionId(), CommandPhase.COMMAND_PHASE_CREATED, "CREATED");
        reliable(peer, command.latest);
        // A full controller mailbox may already have disconnected the owner and removed this command.
        if (pending.get(id) != command) { return; }
        command.deadline = executor.schedule(() -> finish(command, CommandPhase.COMMAND_PHASE_ACK_TIMEOUT, "ACK_TIMEOUT", true),
                config.ackTimeoutMs(), TimeUnit.MILLISECONDS);
        try {
            device.channel().eventLoop().execute(() -> {
                synchronized (command) {
                    if (!command.allowDispatch) { return; }
                    if (!device.channel().isWritable()) {
                        post(() -> finish(command, CommandPhase.COMMAND_PHASE_UNKNOWN, "DEVICE_UNAVAILABLE", true), device.channel()::close);
                        return;
                    }
                    boolean dispatched = sessions.runIfCurrent(device, () -> {
                        command.attempted = true;
                        var routed = base().setRobotId(device.robotId()).setSessionId(device.sessionId())
                            .setSeq(device.nextGatewaySeq()).setCommandRequest(request.getCommandRequest()).build();
                    device.channel().writeAndFlush(routed).addListener(future -> post(() -> {
                        if (pending.get(id) != command) { return; }
                        if (!future.isSuccess()) { finish(command, CommandPhase.COMMAND_PHASE_UNKNOWN, "SEND_FAILED", true); }
                        else if (command.latest.getCommandStatus().getPhase() == CommandPhase.COMMAND_PHASE_CREATED) {
                            command.latest = status(request, device.sessionId(), CommandPhase.COMMAND_PHASE_SENT, "SENT");
                            reliable(peer, command.latest);
                        }
                        }, device.channel()::close));
                    });
                    if (!dispatched) {
                        post(() -> finish(command, CommandPhase.COMMAND_PHASE_UNKNOWN, "SESSION_CHANGED", true), device.channel()::close);
                    }
                }
            });
        } catch (RejectedExecutionException shutdown) {
            finish(command, CommandPhase.COMMAND_PHASE_UNKNOWN, "DEVICE_UNAVAILABLE", true);
        }
    }

    private static boolean same(Envelope first, Envelope second) {
        return first.getRobotId().equals(second.getRobotId()) && first.getCommandRequest().equals(second.getCommandRequest());
    }

    private static boolean validCommand(CommandRequest command) {
        if (command.getAction() == Action.ACTION_STOP) { return !command.hasMotion(); }
        if (!command.hasMotion()) { return false; }
        var motion = command.getMotion();
        if (!Double.isFinite(motion.getSpeedMps()) || !Double.isFinite(motion.getAngularSpeedRadS())
                || motion.getDurationMs() <= 0 || motion.getDurationMs() > 3000) { return false; }
        return switch (command.getAction()) {
            case ACTION_MOVE_FORWARD -> motion.getSpeedMps() > 0 && motion.getSpeedMps() <= 0.5 && motion.getAngularSpeedRadS() == 0;
            case ACTION_TURN_LEFT, ACTION_TURN_RIGHT -> motion.getAngularSpeedRadS() > 0 && motion.getAngularSpeedRadS() <= 1
                    && motion.getSpeedMps() == 0;
            default -> false;
        };
    }

    private void reject(Peer peer, Envelope request, String reason, boolean remember) {
        var response = status(request, "", CommandPhase.COMMAND_PHASE_REJECTED, reason);
        if (remember) { remember(peer, request, response); }
        reliable(peer, response);
    }

    private void remember(Peer peer, Envelope request, Envelope response) {
        history.put(request.getCommandRequest().getCommandId(), new Completed(peer.id, request.getRobotId(),
                request.getCommandRequest(), response, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.historyTtlMs())));
    }

    private void finish(Pending command, CommandPhase phase, String reason, boolean unknown) {
        String id = command.request.getCommandRequest().getCommandId();
        if (pending.get(id) != command) { return; }
        pending.remove(id);
        synchronized (command) {
            command.allowDispatch = false;
            if (unknown && command.attempted) { uncertain.put(command.device.robotId(), command.device); }
        }
        if (command.deadline != null) { command.deadline.cancel(false); }
        var response = status(command.request, command.device.sessionId(), phase, reason);
        remember(command.owner, command.request, response);
        reliable(command.owner, response);
    }

    @Override
    public void attached(DeviceSession session) { post(() -> reconcile(session, true), session.channel()::close); }

    @Override
    public void detached(DeviceSession session) { post(() -> reconcile(session, false), session.channel()::close); }

    private void reconcile(DeviceSession event, boolean online) {
        var current = sessions.find(event.robotId()).orElse(null);
        for (var command : List.copyOf(pending.values())) {
            if (command.device.robotId().equals(event.robotId()) && command.device != current) {
                finish(command, CommandPhase.COMMAND_PHASE_UNKNOWN, "SESSION_CHANGED_OR_DISCONNECTED", true);
            }
        }
        uncertain.computeIfPresent(event.robotId(), (id, value) -> value == current ? value : null);
        if ((online && current != event) || (!online && current != null)) { return; }
        var notice = base().setRobotId(event.robotId()).setSessionId(event.sessionId())
                .setDeviceStatusChanged(DeviceStatusChanged.newBuilder().setOnline(online)
                        .setReason(online ? "CONNECTED" : "DISCONNECTED").setObservedAtMs(System.currentTimeMillis())).build();
        for (var peer : List.copyOf(peers.values())) {
            if (peer.subscribed.contains(event.robotId())) {
                peer.telemetry.remove(event.robotId());
                reliable(peer, notice);
            }
        }
    }

    @Override
    public void received(DeviceSession session, Envelope message) {
        if (!message.hasTelemetry() && !message.hasCommandAck() && !message.hasCommandResult()) { return; }
        post(() -> {
            if (sessions.find(session.robotId()).orElse(null) != session) { return; }
            if (message.hasTelemetry()) {
                for (var peer : List.copyOf(peers.values())) {
                    if (peer.subscribed.contains(session.robotId()) && !peer.closed) {
                        peer.telemetry.put(session.robotId(), message);
                        drain(peer);
                    }
                }
                return;
            }
            String id = message.hasCommandAck() ? message.getCommandAck().getCommandId() : message.getCommandResult().getCommandId();
            var command = pending.get(id);
            if (command == null || command.device != session) { return; }
            var phase = command.latest.getCommandStatus().getPhase();
            if (message.hasCommandAck()) {
                if (phase == CommandPhase.COMMAND_PHASE_ACKED) { return; }
                reliable(command.owner, message);
                if (!message.getCommandAck().getAccepted()) { finish(command, CommandPhase.COMMAND_PHASE_REJECTED, "DEVICE_REJECTED", false); return; }
                command.deadline.cancel(false);
                command.latest = status(command.request, session.sessionId(), CommandPhase.COMMAND_PHASE_ACKED, "ACKED");
                reliable(command.owner, command.latest);
                if (pending.get(id) == command) {
                    command.deadline = executor.schedule(() -> finish(command, CommandPhase.COMMAND_PHASE_RESULT_TIMEOUT, "RESULT_TIMEOUT", true),
                            config.resultTimeoutMs(), TimeUnit.MILLISECONDS);
                }
            } else {
                if (phase != CommandPhase.COMMAND_PHASE_ACKED) { finish(command, CommandPhase.COMMAND_PHASE_UNKNOWN, "RESULT_BEFORE_ACK", true); return; }
                var outcome = message.getCommandResult().getOutcome();
                if (outcome == CommandOutcome.COMMAND_OUTCOME_UNSPECIFIED || outcome == CommandOutcome.UNRECOGNIZED) {
                    finish(command, CommandPhase.COMMAND_PHASE_UNKNOWN, "INVALID_RESULT", true); return;
                }
                reliable(command.owner, message);
                CommandPhase terminal = switch (outcome) {
                    case COMMAND_OUTCOME_SUCCEEDED -> CommandPhase.COMMAND_PHASE_SUCCEEDED;
                    case COMMAND_OUTCOME_INTERRUPTED -> CommandPhase.COMMAND_PHASE_INTERRUPTED;
                    default -> CommandPhase.COMMAND_PHASE_FAILED;
                };
                if (terminal == CommandPhase.COMMAND_PHASE_SUCCEEDED && command.request.getCommandRequest().getAction() == Action.ACTION_STOP) {
                    uncertain.remove(session.robotId(), session);
                }
                finish(command, terminal, "DEVICE_RESULT", false);
            }
        }, session.channel()::close);
    }

    private void reliable(Peer peer, Envelope message) {
        if (peer.closed) { return; }
        if (message.getSerializedSize() > 65536 || peer.reliable.size() >= RELIABLE_LIMIT) { disconnect(peer); return; }
        peer.reliable.add(message);
        drain(peer);
    }

    private void drain(Peer peer) {
        if (peer.closed) { return; }
        if (!peer.sink.active()) { disconnect(peer); return; }
        if (peer.inFlight) { return; }
        if (!peer.sink.writable()) {
            if (peer.blockedSince == 0) { peer.blockedSince = System.nanoTime(); }
            return;
        }
        Envelope message = peer.reliable.poll();
        if (message == null && !peer.telemetry.isEmpty()) {
            var entry = peer.telemetry.entrySet().iterator();
            message = entry.next().getValue();
            entry.remove();
        }
        if (message == null) { if (peer.closing) { disconnect(peer); } return; }
        peer.inFlight = true;
        peer.blockedSince = System.nanoTime();
        peer.sink.write(message, success -> post(() -> {
            peer.inFlight = false;
            peer.blockedSince = 0;
            if (!success) { disconnect(peer); }
            else { drain(peer); }
        }, peer.sink::close));
    }

    private void disconnect(Peer peer) {
        if (peer.closed) { return; }
        peer.closed = true;
        peers.remove(peer.id);
        peer.reliable.clear();
        peer.telemetry.clear();
        peer.sink.close();
        for (var command : List.copyOf(pending.values())) {
            if (command.owner == peer) { finish(command, CommandPhase.COMMAND_PHASE_UNKNOWN, "CONTROLLER_DISCONNECTED", true); }
        }
    }

    private void maintain() {
        long now = System.nanoTime();
        pruneHistory();
        for (var peer : List.copyOf(peers.values())) {
            if ((peer.allowed == null && now - peer.connectedAt > TimeUnit.MILLISECONDS.toNanos(config.authTimeoutMs()))
                    || (peer.blockedSince != 0 && now - peer.blockedSince > STALL_NANOS)) { disconnect(peer); }
            else { drain(peer); }
        }
    }

    private void pruneHistory() { long now = System.nanoTime(); history.values().removeIf(value -> value.expiresAt <= now); }

    private void error(Peer peer, String requestId, String code) {
        reliable(peer, base().setError(dev.robolink.protocol.v1.Error.newBuilder().setRequestId(requestId).setCode(code).setMessage(code)).build());
    }

    private static Envelope status(Envelope request, String sessionId, CommandPhase phase, String reason) {
        return base().setRobotId(request.getRobotId()).setSessionId(sessionId).setCommandStatus(CommandStatus.newBuilder()
                .setCommandId(request.getCommandRequest().getCommandId()).setPhase(phase).setReason(reason)
                .setObservedAtMs(System.currentTimeMillis())).build();
    }

    private static Envelope.Builder base() { return Envelope.newBuilder().setProtocolVersion(1).setTimestampMs(System.currentTimeMillis()); }

    private void post(Runnable action, Runnable rejected) {
        try { executor.execute(action); }
        catch (RejectedExecutionException unavailable) { rejected.run(); }
    }

    @Override
    public void close() {
        executor.submit(() -> {
            tick.cancel(false);
            for (var peer : List.copyOf(peers.values())) { disconnect(peer); }
            for (var command : List.copyOf(pending.values())) { finish(command, CommandPhase.COMMAND_PHASE_UNKNOWN, "GATEWAY_SHUTDOWN", true); }
            history.clear(); uncertain.clear();
        }).syncUninterruptibly();
        executor.shutdownGracefully(0, 2, TimeUnit.SECONDS).syncUninterruptibly();
    }
}
