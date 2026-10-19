package dev.robolink.gateway.control;

import dev.robolink.gateway.session.DeviceCredentials;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Separate operator credentials and explicit device scopes; no device credential reuse. */
public final class ControlAccess {
    private final Map<String, byte[]> hashes = new HashMap<>();
    private final Map<String, Set<String>> scopes = new HashMap<>();

    public static ControlAccess of(Map<String, String> tokens, Map<String, Set<String>> scopes) {
        var access = new ControlAccess();
        if (tokens.isEmpty() || !tokens.keySet().equals(scopes.keySet())) {
            throw new IllegalArgumentException("Control credentials and scopes are required");
        }
        tokens.forEach((id, token) -> {
            var robots = scopes.get(id);
            if (!DeviceCredentials.validRobotId(id) || !validToken(token) || robots.isEmpty()
                    || robots.size() > 64 || robots.stream().anyMatch(robot -> !DeviceCredentials.validRobotId(robot))) {
                throw new IllegalArgumentException("Invalid control access configuration");
            }
            access.hashes.put(id, digest(token));
            access.scopes.put(id, Set.copyOf(robots));
        });
        return access;
    }

    public static ControlAccess fromProperties(Path path) throws IOException {
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) { properties.load(reader); }
        var tokens = new HashMap<String, String>();
        var scopes = new HashMap<String, Set<String>>();
        for (String key : properties.stringPropertyNames()) {
            if (key.endsWith(".token")) { tokens.put(key.substring(0, key.length() - 6), properties.getProperty(key)); }
            else if (key.endsWith(".robots")) {
                var robots = new HashSet<String>();
                for (String robot : properties.getProperty(key).split(",", -1)) { robots.add(robot.trim()); }
                scopes.put(key.substring(0, key.length() - 7), robots);
            } else { throw new IllegalArgumentException("Unknown control access setting"); }
        }
        return of(tokens, scopes);
    }

    Set<String> authenticate(String id, String token) {
        if (!validToken(token)) { return null; }
        byte[] expected = hashes.get(id);
        return expected != null && MessageDigest.isEqual(expected, digest(token)) ? scopes.get(id) : null;
    }

    private static boolean validToken(String token) {
        return token != null && !token.isBlank() && token.getBytes(StandardCharsets.UTF_8).length <= 1024;
    }

    private static byte[] digest(String token) {
        try { return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
