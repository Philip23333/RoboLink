package dev.robolink.gateway.session;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/** Local development credentials; never expose a generated toString containing secrets. */
public final class DeviceCredentials {
    private static final Pattern ROBOT_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
    private final Map<String, byte[]> hashes;

    private DeviceCredentials(Map<String, byte[]> hashes) { this.hashes = Map.copyOf(hashes); }

    public static DeviceCredentials of(Map<String, String> credentials) {
        if (credentials.isEmpty()) { throw new IllegalArgumentException("At least one device credential is required"); }
        var hashes = new HashMap<String, byte[]>();
        credentials.forEach((id, credential) -> {
            if (!validRobotId(id) || !validCredential(credential)) {
                throw new IllegalArgumentException("Invalid device credential configuration");
            }
            hashes.put(id, digest(credential));
        });
        return new DeviceCredentials(hashes);
    }

    public static DeviceCredentials fromProperties(Path file) throws IOException {
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(reader); }
        var entries = new HashMap<String, String>();
        for (var id : properties.stringPropertyNames()) { entries.put(id, properties.getProperty(id)); }
        return of(entries);
    }

    public boolean authenticate(String robotId, String credential) {
        if (!validRobotId(robotId) || !validCredential(credential)) { return false; }
        byte[] expected = hashes.get(robotId);
        byte[] supplied = digest(credential);
        return expected != null && MessageDigest.isEqual(expected, supplied);
    }

    public static boolean validRobotId(String robotId) {
        return robotId != null && ROBOT_ID.matcher(robotId).matches();
    }

    private static boolean validCredential(String credential) {
        return credential != null && !credential.isBlank()
                && credential.getBytes(StandardCharsets.UTF_8).length <= 1024;
    }

    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
