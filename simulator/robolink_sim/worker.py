"""A single OS process containing exactly one simulated TCP robot."""
import argparse
import asyncio
from contextlib import suppress
import json
import os
import sys
import time

from .__main__ import credentials
from .client import RobotClient
from .model import RobotModel


async def run(args):
    def output(value):
        print(json.dumps({**value, "pid": os.getpid(), "robot_id": args.robot_id}), flush=True)

    def trace(event):
        output({"kind": "trace", **event})

    robot = RobotClient(args.robot_id, credentials(args.credential_file)[args.robot_id],
        RobotModel(x=args.x, y=args.y, yaw=args.yaw, battery=args.battery), observer=trace)

    def state():
        output({"kind": "state", "session_id": robot.session_id, "state": robot.state,
                "seq": robot.seq, "sent_messages": robot.sent_messages,
                "received_messages": robot.received_messages, "observed_at_ms": time.time_ns() // 1_000_000,
                "pose": {"x": robot.model.x, "y": robot.model.y, "yaw": robot.model.yaw},
                "battery": robot.model.battery})

    async def report():
        while True:
            state()
            await asyncio.sleep(0.5)

    async def control():
        # Pipe commands come only from the owning supervisor. EOF also ends this child.
        await asyncio.to_thread(sys.stdin.buffer.readline)

    tasks = [asyncio.create_task(robot.run_forever(port=args.port)), asyncio.create_task(report()), asyncio.create_task(control())]
    try:
        done, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
        for task in done:
            task.result()
    finally:
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        state()  # Final stopped pose after run_forever's local-stop cleanup.


def main():
    from pathlib import Path
    parser = argparse.ArgumentParser()
    parser.add_argument("--robot-id", required=True)
    parser.add_argument("--credential-file", type=Path, required=True)
    parser.add_argument("--port", type=int, default=9000)
    parser.add_argument("--x", type=float, default=0)
    parser.add_argument("--y", type=float, default=0)
    parser.add_argument("--yaw", type=float, default=0)
    parser.add_argument("--battery", type=float, default=87)
    args = parser.parse_args()
    try:
        asyncio.run(run(args))
    except (KeyboardInterrupt, BrokenPipeError):
        pass
    except Exception:
        print("Robot worker exited: check local configuration and supervisor pipes.", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
