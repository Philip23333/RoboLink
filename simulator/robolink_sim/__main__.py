import argparse
import asyncio
from pathlib import Path
import sys

from .client import RobotClient
from .model import RobotModel
from .recovery import FaultPlan


def credentials(path: Path) -> dict[str, str]:
    """Read the documented ID=token subset, not Java Properties escaping/continuations."""
    result = {}
    for line in path.read_text(encoding="utf-8-sig").splitlines():
        line = line.strip()
        if not line or line.startswith(("#", "!")):
            continue
        key, separator, value = line.partition("=")
        key, value = key.strip(), value.strip()
        if not separator or key in result or not value or "\\" in line:
            raise ValueError("Use unique ID=token lines without Properties escapes")
        result[key] = value
    return result


async def fleet(args):
    tokens = credentials(args.credential_file)
    robots = []
    for index in range(args.count):
        robot_id = f"{args.id_prefix}{index + 1:03d}"
        if robot_id not in tokens:
            raise ValueError(f"Missing device configuration for {robot_id}")
        faults = FaultPlan(disconnect_after=args.disconnect_after, ack_delay=args.ack_delay_ms / 1000,
                           drop_ack=args.drop_next_ack, drop_result=args.drop_next_result) if robot_id == args.fault_robot else None
        robots.append(RobotClient(robot_id, tokens[robot_id],
                                  RobotModel(x=index * 2.0, y=index * 1.0, battery=87 - index % 30),
                                  args.telemetry_hz, faults))
    if args.fault_robot and args.fault_robot not in [robot.robot_id for robot in robots]:
        raise ValueError("Fault robot must belong to this fleet")
    async with asyncio.TaskGroup() as group:
        for robot in robots:
            group.create_task(robot.run_forever(args.host, args.port,
                              lambda identifier, state, delay: print(f"{identifier}: {state}, retry in {delay}s", flush=True)))
        await asyncio.gather(*(robot.ready.wait() for robot in robots))
        print(f"Connected {len(robots)} simulated robots at {args.telemetry_hz:g} Hz; Ctrl+C to stop.", flush=True)
        print("Software simulation; devices reconnect independently. Old motion is never replayed.", flush=True)


def main():
    parser = argparse.ArgumentParser(description="RoboLink TCP multi-robot simulator")
    parser.add_argument("--credential-file", type=Path, required=True)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=9000)
    parser.add_argument("--count", type=int, default=3)
    parser.add_argument("--id-prefix", default="RB-")
    parser.add_argument("--telemetry-hz", type=float, default=10)
    parser.add_argument("--fault-robot", default="")
    parser.add_argument("--disconnect-after", type=float, default=0, help="one disconnect, seconds after first registration")
    parser.add_argument("--ack-delay-ms", type=float, default=0, help="delay ACK/result of first accepted command")
    parser.add_argument("--drop-next-ack", action="store_true")
    parser.add_argument("--drop-next-result", action="store_true")
    args = parser.parse_args()
    if not 1 <= args.count <= 1000 or not 1 <= args.port <= 65535:
        parser.error("count must be 1..1000 and port 1..65535")
    if (args.disconnect_after or args.ack_delay_ms or args.drop_next_ack or args.drop_next_result) and not args.fault_robot:
        parser.error("fault options require --fault-robot")
    try:
        asyncio.run(fleet(args))
    except KeyboardInterrupt:
        print("Simulator stopped; local motions stopped.")
    except Exception:
        # Do not print arbitrary exception payloads containing credentials or registration messages.
        print("Simulator failed: check gateway availability, local config and device access. Fleet stopped.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())

