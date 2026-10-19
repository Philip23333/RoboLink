package dev.robolink.gateway.control;

import static org.junit.jupiter.api.Assertions.*;

import dev.robolink.gateway.GatewayConfig;
import dev.robolink.gateway.GatewayServer;
import dev.robolink.gateway.session.DeviceCredentials;
import dev.robolink.protocol.v1.*;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(20)
class WebSocketBridgeTest {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final List<Ws> clients = new ArrayList<>();
    private final List<Socket> devices = new ArrayList<>();
    private GatewayServer gateway;
    @TempDir Path directory;

    private void start(int authMs) throws Exception {
        var file = directory.resolve("controllers.local.properties");
        Files.writeString(file, "operator.token=public-operator-fixture\noperator.robots=RB-001,RB-002\nlimited.token=limited-fixture\nlimited.robots=RB-001\n");
        gateway = new GatewayServer(new GatewayConfig("127.0.0.1", 0, 3000, 1000, 10000),
                DeviceCredentials.of(Map.of("RB-001", "device-fixture", "RB-002", "device-fixture")),
                new ControlConfig("127.0.0.1", 0, Set.of("http://localhost:5173"), 1500, 2000, authMs, 16, 64, 60000, 16),
                ControlAccess.fromProperties(file)).start();
    }

    @AfterEach
    void cleanup() throws Exception {
        for (var client : clients) { if (client.socket != null) { client.socket.abort(); } }
        for (var socket : devices) { socket.close(); }
        if (gateway != null) { gateway.close(); }
        http.close();
    }

    private Ws connect(String origin, String path) throws Exception {
        var listener = new Ws();
        clients.add(listener);
        var builder = http.newWebSocketBuilder();
        if (origin != null) { builder.header("Origin", origin); }
        listener.socket = builder.buildAsync(URI.create("ws://127.0.0.1:" + gateway.controlAddress().getPort() + path), listener).get(5, TimeUnit.SECONDS);
        return listener;
    }

    private Ws authorized() throws Exception {
        var client = connect("http://localhost:5173", "/control");
        client.send(auth("operator", "public-operator-fixture"));
        assertTrue(client.next(Envelope::hasControlAuthResult).getControlAuthResult().getAccepted());
        return client;
    }

    private static Envelope auth(String id, String token) {
        return Envelope.newBuilder().setProtocolVersion(1).setControlAuthenticate(ControlAuthenticate.newBuilder()
                .setControllerId(id).setCredential(token)).build();
    }

    private final class Ws implements WebSocket.Listener {
        WebSocket socket;
        final BlockingQueue<Envelope> messages = new LinkedBlockingQueue<>();
        final List<Envelope> delivered = new CopyOnWriteArrayList<>();
        final CompletableFuture<Void> closed = new CompletableFuture<>();
        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        public void onOpen(WebSocket socket) { socket.request(1); }
        public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
            byte[] bytes = new byte[data.remaining()]; data.get(bytes); buffer.writeBytes(bytes);
            if (last) {
                try { var message = Envelope.parseFrom(buffer.toByteArray()); delivered.add(message); messages.add(message); }
                catch (Exception invalid) { closed.completeExceptionally(invalid); }
                buffer.reset();
            }
            socket.request(1);
            return CompletableFuture.completedFuture(null);
        }
        public CompletionStage<?> onClose(WebSocket socket, int code, String reason) { closed.complete(null); return CompletableFuture.completedFuture(null); }
        public void onError(WebSocket socket, Throwable cause) { closed.complete(null); }
        void send(Envelope message) { socket.sendBinary(ByteBuffer.wrap(message.toByteArray()), true).join(); }
        Envelope next(Predicate<Envelope> condition) throws Exception {
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (System.nanoTime() < end) {
                var message = messages.poll(20, TimeUnit.MILLISECONDS);
                if (message != null && condition.test(message)) { return message; }
            }
            throw new AssertionError("Expected WebSocket response missing");
        }
        void phase(String id, CommandPhase phase) throws Exception {
            next(message -> message.hasCommandStatus() && message.getCommandStatus().getCommandId().equals(id)
                    && message.getCommandStatus().getPhase() == phase);
        }
        void subscribe() throws Exception {
            send(Envelope.newBuilder().setProtocolVersion(1).setSubscribe(Subscribe.getDefaultInstance()).build());
            next(Envelope::hasSnapshot);
        }
    }

    private Socket device(String id) throws Exception {
        var socket = new Socket(); socket.connect(gateway.localAddress(), 3000); socket.setSoTimeout(3000); devices.add(socket);
        send(socket, Envelope.newBuilder().setProtocolVersion(1).setRobotId(id)
                .setRegister(Register.newBuilder().setCredential("device-fixture")).build());
        return socket;
    }

    private static void send(Socket socket, Envelope message) throws Exception {
        byte[] body = message.toByteArray();
        socket.getOutputStream().write(ByteBuffer.allocate(4 + body.length).putInt(body.length).put(body).array());
        socket.getOutputStream().flush();
    }
    private static Envelope read(Socket socket) throws Exception {
        var input = new DataInputStream(socket.getInputStream()); int size = input.readInt();
        assertTrue(size > 0 && size <= 65536); byte[] bytes = new byte[size]; input.readFully(bytes); return Envelope.parseFrom(bytes);
    }

    @Test
    void realWebSocketToTcpToAckAndResultIsPrivateAndTelemetryIsSubscribed() throws Exception {
        start(5000);
        var first = device("RB-001"); String session = read(first).getSessionId();
        var second = device("RB-002"); read(second);
        var owner = authorized(); var other = authorized(); owner.subscribe(); other.subscribe();
        send(first, Envelope.newBuilder().setProtocolVersion(1).setRobotId("RB-001").setSessionId(session).setSeq(1)
                .setTelemetry(Telemetry.newBuilder().setBatteryPercent(87).setPose(Pose.newBuilder().setXM(10)).setState(RobotState.ROBOT_STATE_IDLE)).build());
        assertEquals(10, owner.next(Envelope::hasTelemetry).getTelemetry().getPose().getXM());
        owner.send(Envelope.newBuilder().setProtocolVersion(1).setRobotId("RB-001").setCommandRequest(CommandRequest.newBuilder()
                .setCommandId("wire-command").setAction(Action.ACTION_MOVE_FORWARD)
                .setMotion(MotionParameters.newBuilder().setSpeedMps(0.2).setDurationMs(1000))).build());
        var command = read(first); assertEquals("wire-command", command.getCommandRequest().getCommandId()); assertEquals(session, command.getSessionId());
        second.setSoTimeout(100); assertThrows(SocketTimeoutException.class, () -> second.getInputStream().read()); second.setSoTimeout(3000);
        owner.phase("wire-command", CommandPhase.COMMAND_PHASE_SENT);
        send(first, Envelope.newBuilder().setProtocolVersion(1).setRobotId("RB-001").setSessionId(session).setSeq(2)
                .setCommandAck(CommandAck.newBuilder().setCommandId("wire-command").setAccepted(true)).build());
        owner.phase("wire-command", CommandPhase.COMMAND_PHASE_ACKED);
        send(first, Envelope.newBuilder().setProtocolVersion(1).setRobotId("RB-001").setSessionId(session).setSeq(3)
                .setCommandResult(CommandResult.newBuilder().setCommandId("wire-command").setOutcome(CommandOutcome.COMMAND_OUTCOME_SUCCEEDED)).build());
        owner.phase("wire-command", CommandPhase.COMMAND_PHASE_SUCCEEDED);
        other.subscribe();
        assertTrue(other.delivered.stream().noneMatch(message -> message.hasCommandAck() || message.hasCommandResult() || message.hasCommandStatus()));
    }

    @Test
    void fragmentedBinaryAuthenticationWorksWithoutTcpLengthHeader() throws Exception {
        start(5000); var client = connect("http://localhost:5173", "/control"); byte[] bytes = auth("limited", "limited-fixture").toByteArray();
        client.socket.sendBinary(ByteBuffer.wrap(Arrays.copyOf(bytes, 3)), false).join();
        client.socket.sendBinary(ByteBuffer.wrap(Arrays.copyOfRange(bytes, 3, bytes.length)), true).join();
        var result = client.next(Envelope::hasControlAuthResult).getControlAuthResult();
        assertTrue(result.getAccepted()); assertEquals(List.of("RB-001"), result.getAllowedRobotIdsList());
        client.send(Envelope.newBuilder().setProtocolVersion(1).setSubscribe(Subscribe.getDefaultInstance()).build());
        var snapshot = client.next(Envelope::hasSnapshot).getSnapshot();
        assertEquals(1, snapshot.getRobotsCount()); assertFalse(snapshot.getRobots(0).getOnline());
    }

    @Test
    void originAndQueryStringAreRejectedBeforeUpgrade() throws Exception {
        start(5000);
        for (var input : List.of(new String[]{"http://untrusted.invalid", "/control"}, new String[]{"http://localhost:5173", "/control?x=1"},
                new String[]{null, "/control"})) {
            var failure = assertThrows(ExecutionException.class, () -> connect(input[0], input[1]));
            assertInstanceOf(WebSocketHandshakeException.class, failure.getCause());
            assertEquals(403, ((WebSocketHandshakeException) failure.getCause()).getResponse().statusCode());
        }
    }

    @Test
    void badCredentialsAreDeniedAndPreAuthenticationMessagesCannotSubscribe() throws Exception {
        start(5000);
        var bad = connect("http://localhost:5173", "/control"); bad.send(auth("operator", "wrong"));
        assertFalse(bad.next(Envelope::hasControlAuthResult).getControlAuthResult().getAccepted()); bad.closed.get(3, TimeUnit.SECONDS);
        var unauthenticated = connect("http://localhost:5173", "/control");
        unauthenticated.send(Envelope.newBuilder().setProtocolVersion(1).setSubscribe(Subscribe.getDefaultInstance()).build());
        unauthenticated.closed.get(3, TimeUnit.SECONDS); assertTrue(unauthenticated.delivered.isEmpty());
    }

    @Test
    void authenticationDeadlineClosesSilentController() throws Exception {
        start(150); var client = connect("http://localhost:5173", "/control"); client.closed.get(3, TimeUnit.SECONDS);
    }

    @Test
    void incompleteHttpHandshakeCannotHoldAControlConnectionForever() throws Exception {
        start(150);
        try (var socket = new Socket()) {
            socket.connect(gateway.controlAddress(), 3000); socket.setSoTimeout(3000);
            socket.getOutputStream().write("GET /control HTTP/1.1\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            assertEquals(-1, socket.getInputStream().read());
        }
    }

    @Test
    void textMalformedProtobufAndUnsupportedVersionCloseConnection() throws Exception {
        start(5000);
        var text = authorized(); text.socket.sendText("{}", true).join(); text.closed.get(3, TimeUnit.SECONDS);
        var corrupt = authorized(); corrupt.socket.sendBinary(ByteBuffer.wrap(new byte[]{(byte) 0x80}), true).join(); corrupt.closed.get(3, TimeUnit.SECONDS);
        var version = authorized(); version.send(Envelope.newBuilder().setProtocolVersion(2).setSubscribe(Subscribe.getDefaultInstance()).build()); version.closed.get(3, TimeUnit.SECONDS);
    }

    @Test
    void aggregateMessageLimitAppliesAcrossFragments() throws Exception {
        start(5000); var client = authorized();
        client.socket.sendBinary(ByteBuffer.wrap(new byte[40000]), false).join();
        client.socket.sendBinary(ByteBuffer.wrap(new byte[30000]), true).join(); client.closed.get(3, TimeUnit.SECONDS);
    }

    @Test
    void gatewayShutdownClosesAuthenticatedWebSocket() throws Exception {
        start(5000); var client = authorized(); gateway.close(); client.closed.get(3, TimeUnit.SECONDS);
    }
}
