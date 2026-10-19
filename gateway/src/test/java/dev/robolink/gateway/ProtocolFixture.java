package dev.robolink.gateway;

import dev.robolink.protocol.v1.Action;
import dev.robolink.protocol.v1.CommandRequest;
import dev.robolink.protocol.v1.Envelope;
import dev.robolink.protocol.v1.MotionParameters;
import dev.robolink.protocol.v1.Pose;
import dev.robolink.protocol.v1.RobotState;
import dev.robolink.protocol.v1.Telemetry;
import dev.robolink.protocol.v1.ControlAuthenticate;
import dev.robolink.protocol.v1.ControlAuthResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Executed by scripts/verify-protocol.ps1; not a running gateway. */
public final class ProtocolFixture {
    private static Envelope.Builder base() {
        return Envelope.newBuilder()
                .setProtocolVersion(1)
                .setRobotId("RB-001")
                .setSessionId("fixture-session")
                .setSeq(Long.parseUnsignedLong("18446744073709551615"))
                .setTimestampMs(9007199254740993L);
    }

    private static Map<String, Envelope> fixtures() {
        var telemetry = Telemetry.newBuilder()
                .setBatteryPercent(87)
                .setPose(Pose.newBuilder().setXM(1.25).setYM(-2.5).setYawRad(0.5))
                .setState(RobotState.ROBOT_STATE_IDLE).build();
        var command = CommandRequest.newBuilder()
                .setCommandId("fixture-command")
                .setAction(Action.ACTION_MOVE_FORWARD)
                .setMotion(MotionParameters.newBuilder().setSpeedMps(0.2).setDurationMs(1000))
                .build();
        // Setting the second oneof branch must clear the previous branch.
        var switched = base().setTelemetry(telemetry).setCommandRequest(command).build();
        if (switched.hasTelemetry() || !switched.hasCommandRequest()) {
            throw new AssertionError("oneof branch was not replaced");
        }
        return Map.of(
                "telemetry", base().setTelemetry(telemetry).build(),
                "command", base().setCommandRequest(command).build(),
                "control-auth", base().clearRobotId().clearSessionId().setControlAuthenticate(ControlAuthenticate.newBuilder()
                        .setControllerId("fixture-operator").setCredential("public-fixture-not-a-secret")).build(),
                "control-auth-result", base().clearRobotId().clearSessionId().setControlAuthResult(ControlAuthResult.newBuilder()
                        .setAccepted(true).setControlSessionId("fixture-control-session")
                        .addAllowedRobotIds("RB-001").addAllowedRobotIds("RB-002")).build());
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !(args[0].equals("encode") || args[0].equals("verify"))) {
            throw new IllegalArgumentException("Usage: ProtocolFixture encode|verify DIRECTORY");
        }
        var directory = Path.of(args[1]);
        Files.createDirectories(directory);
        for (var entry : fixtures().entrySet()) {
            var file = directory.resolve(entry.getKey() + ".bin");
            if (args[0].equals("encode")) {
                Files.write(file, entry.getValue().toByteArray());
            } else {
                var decoded = Envelope.parseFrom(Files.readAllBytes(file));
                if (!decoded.equals(entry.getValue())) {
                    throw new AssertionError("Fixture mismatch: " + file);
                }
                if (!Long.toUnsignedString(decoded.getSeq()).equals("18446744073709551615")) {
                    throw new AssertionError("uint64 lost precision");
                }
            }
        }
        System.out.println("Java " + args[0] + ": " + fixtures().size() + " fixtures PASS");
    }
}
