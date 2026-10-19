package dev.robolink.gateway;

import java.util.Map;

public record GatewayConfig(String host, int port, int registrationTimeoutMs,
                            int heartbeatIntervalMs, int heartbeatTimeoutMs) {
    public GatewayConfig {
        if (host == null || host.isBlank() || port < 0 || port > 65535) {
            throw new IllegalArgumentException("Invalid gateway bind address");
        }
        if (registrationTimeoutMs <= 0 || heartbeatIntervalMs <= 0 || heartbeatTimeoutMs <= heartbeatIntervalMs) {
            throw new IllegalArgumentException("Timeouts must be positive; heartbeat timeout must exceed interval");
        }
    }

    public static GatewayConfig fromEnvironment(Map<String, String> environment) {
        return new GatewayConfig(environment.getOrDefault("ROBOLINK_HOST", "127.0.0.1"),
                integer(environment, "ROBOLINK_PORT", 9000),
                integer(environment, "ROBOLINK_REGISTRATION_TIMEOUT_MS", 5000),
                integer(environment, "ROBOLINK_HEARTBEAT_INTERVAL_MS", 3000),
                integer(environment, "ROBOLINK_HEARTBEAT_TIMEOUT_MS", 10000));
    }

    private static int integer(Map<String, String> environment, String name, int fallback) {
        try {
            return Integer.parseInt(environment.getOrDefault(name, Integer.toString(fallback)));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Invalid integer setting: " + name);
        }
    }
}
