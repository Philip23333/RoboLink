"""Cross-language fixture utility; no simulator/network behavior yet."""
import argparse
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "generated"))
import robolink_pb2 as pb


def fixtures():
    common = dict(
        protocol_version=1,
        robot_id="RB-001",
        session_id="fixture-session",
        seq=18446744073709551615,
        timestamp_ms=9007199254740993,
    )
    telemetry = pb.Telemetry(
        battery_percent=87,
        pose=pb.Pose(x_m=1.25, y_m=-2.5, yaw_rad=0.5),
        state=pb.ROBOT_STATE_IDLE,
    )
    command = pb.CommandRequest(
        command_id="fixture-command",
        action=pb.ACTION_MOVE_FORWARD,
        motion=pb.MotionParameters(speed_mps=0.2, duration_ms=1000),
    )
    switched = pb.Envelope(**common, telemetry=telemetry)
    switched.command_request.CopyFrom(command)
    if switched.WhichOneof("payload") != "command_request" or switched.HasField("telemetry"):
        raise AssertionError("oneof branch was not replaced")
    return {
        "telemetry": pb.Envelope(**common, telemetry=telemetry),
        "command": pb.Envelope(**common, command_request=command),
        "control-auth": pb.Envelope(**{**common, "robot_id": "", "session_id": ""},
            control_authenticate=pb.ControlAuthenticate(controller_id="fixture-operator", credential="public-fixture-not-a-secret")),
        "control-auth-result": pb.Envelope(**{**common, "robot_id": "", "session_id": ""},
            control_auth_result=pb.ControlAuthResult(accepted=True, control_session_id="fixture-control-session",
                                                   allowed_robot_ids=["RB-001", "RB-002"])),
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("mode", choices=("encode", "verify"))
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    args.directory.mkdir(parents=True, exist_ok=True)
    for name, expected in fixtures().items():
        target = args.directory / f"{name}.bin"
        if args.mode == "encode":
            target.write_bytes(expected.SerializeToString())
        else:
            decoded = pb.Envelope.FromString(target.read_bytes())
            if decoded != expected:
                raise AssertionError(f"Fixture mismatch: {target}")
            if decoded.seq != 18446744073709551615:
                raise AssertionError("uint64 lost precision")
    print(f"Python {args.mode}: {len(fixtures())} fixtures PASS")


if __name__ == "__main__":
    main()
