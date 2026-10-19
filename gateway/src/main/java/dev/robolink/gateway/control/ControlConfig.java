package dev.robolink.gateway.control;

import java.util.Map;
import java.util.Set;

public record ControlConfig(String host, int port, Set<String> origins, int ackTimeoutMs,
                            int resultTimeoutMs, int authTimeoutMs, int maxPending,
                            int maxHistory, int historyTtlMs, int maxClients) {
    public ControlConfig {
        origins = Set.copyOf(origins);
        if (host == null || host.isBlank() || port < 0 || port > 65535 || origins.isEmpty()
                || ackTimeoutMs <= 0 || resultTimeoutMs <= 0 || authTimeoutMs <= 0
                || maxPending <= 0 || maxHistory < maxPending || historyTtlMs <= 0 || maxClients <= 0) {
            throw new IllegalArgumentException("Invalid control configuration");
        }
    }

    public static ControlConfig fromEnvironment(Map<String, String> env) {
        return new ControlConfig(env.getOrDefault("ROBOLINK_CONTROL_HOST", "127.0.0.1"),
                integer(env, "ROBOLINK_CONTROL_PORT", 9001),
                Set.of(env.getOrDefault("ROBOLINK_CONTROL_ORIGINS", "http://localhost:5173,http://127.0.0.1:5173").split(",")),
                integer(env, "ROBOLINK_ACK_TIMEOUT_MS", 2000), integer(env, "ROBOLINK_RESULT_TIMEOUT_MS", 5000),
                5000, 256, 1024, 60000, 32);
    }

    private static int integer(Map<String, String> env, String key, int fallback) {
        try { return Integer.parseInt(env.getOrDefault(key, Integer.toString(fallback))); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid integer setting: " + key); }
    }
}
