package dev.robolink.gateway;

import dev.robolink.gateway.session.DeviceCredentials;
import java.nio.file.Path;
import dev.robolink.gateway.control.ControlAccess;
import dev.robolink.gateway.control.ControlConfig;

public final class GatewayMain {
    private GatewayMain() {}

    public static void main(String[] args) throws Exception {
        var environment = System.getenv();
        String file = environment.get("ROBOLINK_CREDENTIALS_FILE");
        if (file == null || file.isBlank()) {
            throw new IllegalArgumentException("ROBOLINK_CREDENTIALS_FILE is required");
        }
        var config = GatewayConfig.fromEnvironment(environment);
        var credentials = DeviceCredentials.fromProperties(Path.of(file));
        String controlFile = environment.get("ROBOLINK_CONTROL_CREDENTIALS_FILE");
        var controlConfig = controlFile == null ? null : ControlConfig.fromEnvironment(environment);
        var controlAccess = controlFile == null ? null : ControlAccess.fromProperties(Path.of(controlFile));
        try (var gateway = new GatewayServer(config, credentials, controlConfig, controlAccess).start()) {
            Runtime.getRuntime().addShutdownHook(new Thread(gateway::close, "gateway-shutdown"));
            System.out.printf("RoboLink TCP listening on %s:%d%n", gateway.localAddress().getHostString(), gateway.localAddress().getPort());
            if (controlConfig != null) {
                System.out.printf("RoboLink WebSocket listening on %s:%d/control%n", gateway.controlAddress().getHostString(), gateway.controlAddress().getPort());
            }
            gateway.awaitClose();
        }
    }
}
