package dev.robolink.gateway.session;

import static org.junit.jupiter.api.Assertions.*;
import static dev.robolink.gateway.session.SessionTestSupport.*;

import dev.robolink.gateway.GatewayConfig;
import dev.robolink.gateway.GatewayServer;
import dev.robolink.protocol.v1.Register;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(15)
class GatewayLifecycleTest {
    private GatewayServer gateway;

    private GatewayServer start(GatewayConfig config) throws Exception {
        gateway = new GatewayServer(config, DeviceCredentials.of(Map.of("RB-001", TEST_CREDENTIAL))).start();
        return gateway;
    }

    private Socket connect() throws Exception {
        var socket = new Socket();
        socket.connect(gateway.localAddress(), 3000);
        socket.setSoTimeout(3000);
        return socket;
    }

    private String register(Socket socket) throws Exception {
        send(socket, registration("RB-001"));
        var result = readReply(socket).getRegisterResult();
        assertTrue(result.getAccepted());
        return result.getAssignedSessionId();
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) { Thread.sleep(5); }
        assertTrue(condition.getAsBoolean(), "Condition did not become true before timeout");
    }

    @AfterEach
    void closeGateway() { if (gateway != null) { gateway.close(); } }

    @Test
    void realRegistrationTelemetryHeartbeatAndDisconnect() throws Exception {
        start(CONFIG);
        try (var socket = connect()) {
            String session = register(socket);
            send(socket, telemetry("RB-001", session, 1));
            send(socket, heartbeat("RB-001", session, 2));
            assertEquals(2, readReply(socket).getHeartbeatReply().getAcknowledgedSeq());
            assertEquals(87, gateway.sessions().onlineSnapshots().getFirst().getLatestTelemetry().getBatteryPercent());
            assertEquals(1, gateway.sessions().onlineCount());
            var observed = gateway.sessions().onlineSnapshots().getFirst();
            assertTrue(observed.getRemoteAddress().contains(Integer.toString(socket.getLocalPort())));
            assertEquals(3, observed.getReceivedMessages());
            await(() -> gateway.sessions().onlineSnapshots().getFirst().getSentMessages() == 2);
            assertTrue(observed.getLastHeartbeatAtMs() > 0);
            assertTrue(observed.getTelemetryObservedAtMs() > 0);
        }
        await(() -> gateway.sessions().onlineCount() == 0);
    }

    @Test
    void failedAuthenticationCannotEvictOnlineDevice() throws Exception {
        start(CONFIG);
        try (var online = connect(); var intruder = connect()) {
            String session = register(online);
            send(intruder, registration("RB-001").toBuilder()
                    .setRegister(Register.newBuilder().setCredential("wrong")).build());
            assertFalse(readReply(intruder).getRegisterResult().getAccepted());
            assertEquals(-1, intruder.getInputStream().read());
            send(online, heartbeat("RB-001", session, 1));
            assertTrue(readReply(online).hasHeartbeatReply());
            assertEquals(session, gateway.sessions().find("RB-001").orElseThrow().sessionId());
        }
    }

    @Test
    void authenticatedReplacementClosesOldSocketWithoutLosingNewSession() throws Exception {
        start(CONFIG);
        try (var old = connect(); var current = connect()) {
            String previous = register(old);
            String session = register(current);
            assertNotEquals(previous, session);
            assertEquals(-1, old.getInputStream().read());
            send(current, heartbeat("RB-001", session, 1));
            assertTrue(readReply(current).hasHeartbeatReply());
            assertEquals(1, gateway.sessions().onlineCount());
            assertEquals(session, gateway.sessions().find("RB-001").orElseThrow().sessionId());
        }
    }

    @Test
    void incompleteRegistrationIsClosedAtDeadline() throws Exception {
        start(new GatewayConfig("127.0.0.1", 0, 200, 1000, 3000));
        try (var socket = connect()) {
            socket.getOutputStream().write(new byte[]{0, 0});
            socket.getOutputStream().flush();
            assertEquals(-1, socket.getInputStream().read());
            assertEquals(0, gateway.sessions().onlineCount());
        }
    }

    @Test
    void silentRegisteredDeviceTimesOutAndIsRemoved() throws Exception {
        start(new GatewayConfig("127.0.0.1", 0, 3000, 100, 350));
        try (var socket = connect()) {
            register(socket);
            assertEquals(-1, socket.getInputStream().read());
            await(() -> gateway.sessions().onlineCount() == 0);
        }
    }

    @Test
    void acceptedHeartbeatsKeepDeviceAlivePastIdleTimeout() throws Exception {
        start(new GatewayConfig("127.0.0.1", 0, 3000, 200, 1000));
        try (var socket = connect()) {
            String session = register(socket);
            for (int seq = 1; seq <= 6; seq++) {
                Thread.sleep(200);
                send(socket, heartbeat("RB-001", session, seq));
                assertEquals(seq, readReply(socket).getHeartbeatReply().getAcknowledgedSeq());
            }
            assertEquals(1, gateway.sessions().onlineCount());
        }
    }

    @Test
    void codecFailureAlsoCleansAuthenticatedSession() throws Exception {
        start(CONFIG);
        try (var socket = connect()) {
            register(socket);
            socket.getOutputStream().write(new byte[]{0, 0, 0, 1, (byte) 0x80});
            socket.getOutputStream().flush();
            assertEquals(-1, socket.getInputStream().read());
            await(() -> gateway.sessions().onlineCount() == 0);
        }
    }

    @Test
    void shutdownClosesRegisteredAndUnregisteredConnections() throws Exception {
        start(CONFIG);
        try (var online = connect(); var pending = connect()) {
            register(online);
            gateway.close();
            assertEquals(-1, online.getInputStream().read());
            assertEquals(-1, pending.getInputStream().read());
            assertEquals(0, gateway.sessions().onlineCount());
            gateway.close();
        }
    }

    @Test
    void failedBindReleasesResourcesAndCannotRestartFailedInstance() throws Exception {
        start(CONFIG);
        var config = new GatewayConfig("127.0.0.1", gateway.localAddress().getPort(), 5000, 3000, 10000);
        try (var failed = new GatewayServer(config, DeviceCredentials.of(Map.of("RB-001", TEST_CREDENTIAL)))) {
            assertThrows(Exception.class, failed::start);
            gateway.close();
            assertThrows(IllegalStateException.class, failed::start);
        }
    }
}
