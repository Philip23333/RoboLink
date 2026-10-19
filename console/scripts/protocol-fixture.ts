import { deepStrictEqual, strictEqual } from "node:assert";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { Action, Envelope, RobotState } from "../src/generated/robolink";

const base = {
  protocolVersion: 1,
  robotId: "RB-001",
  sessionId: "fixture-session",
  seq: 18446744073709551615n,
  timestampMs: 9007199254740993n,
};
const fixtures: Record<string, Envelope> = {
  telemetry: Envelope.create({
    ...base,
    payload: {
      $case: "telemetry",
      value: {
        batteryPercent: 87,
        pose: { xM: 1.25, yM: -2.5, yawRad: 0.5 },
        state: RobotState.ROBOT_STATE_IDLE,
      },
    },
  }),
  command: Envelope.create({
    ...base,
    payload: {
      $case: "commandRequest",
      value: {
        commandId: "fixture-command",
        action: Action.ACTION_MOVE_FORWARD,
        motion: { speedMps: 0.2, angularSpeedRadS: 0, durationMs: 1000 },
      },
    },
  }),
  "control-auth": Envelope.create({
    ...base, robotId: "", sessionId: "",
    payload: { $case: "controlAuthenticate", value: {
      controllerId: "fixture-operator", credential: "public-fixture-not-a-secret",
    } },
  }),
  "control-auth-result": Envelope.create({
    ...base, robotId: "", sessionId: "",
    payload: { $case: "controlAuthResult", value: {
      accepted: true, reason: "", controlSessionId: "fixture-control-session", allowedRobotIds: ["RB-001", "RB-002"],
    } },
  }),
};

const [mode, directory] = process.argv.slice(2);
if (!directory || !["encode", "verify"].includes(mode)) {
  throw new Error("Usage: protocol-fixture.ts encode|verify DIRECTORY");
}
// A discriminated union stores exactly one payload branch.
const switched = Envelope.create(fixtures.telemetry);
switched.payload = fixtures.command.payload;
strictEqual(switched.payload?.$case, "commandRequest");
strictEqual("telemetry" in switched, false);
mkdirSync(directory, { recursive: true });
for (const [name, expected] of Object.entries(fixtures)) {
  const target = join(directory, `${name}.bin`);
  if (mode === "encode") {
    writeFileSync(target, Envelope.encode(expected).finish());
  } else {
    const decoded = Envelope.decode(readFileSync(target));
    deepStrictEqual(decoded, expected, `Fixture mismatch: ${target}`);
    strictEqual(decoded.seq, 18446744073709551615n);
  }
}
console.log(`TypeScript ${mode}: ${Object.keys(fixtures).length} fixtures PASS`);
