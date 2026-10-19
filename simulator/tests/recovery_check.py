"""Actual gateway restart and application fault integration, independent of Vue."""
import asyncio
from contextlib import suppress
import os
from pathlib import Path
import secrets
import tempfile
import time

from gateway_check import ControlProbe, ROOT
from robolink_sim.client import RobotClient
from robolink_sim.protocol import pb
from robolink_sim.recovery import FaultPlan


async def recovery_check():
    started = time.monotonic()
    with tempfile.TemporaryDirectory(prefix="recovery-", dir=ROOT / "artifacts/simulator") as temporary:
        token, operator = secrets.token_hex(24), secrets.token_hex(24)
        devices, controllers = Path(temporary) / "devices.local.properties", Path(temporary) / "controllers.local.properties"
        devices.write_text(f"RB-001={token}\nRB-002={token}\nRB-003={token}\n", encoding="utf-8")
        controllers.write_text(f"operator.token={operator}\noperator.robots=RB-001,RB-002,RB-003\n", encoding="utf-8")
        environment = dict(os.environ, ROBOLINK_CREDENTIALS_FILE=str(devices), ROBOLINK_CONTROL_CREDENTIALS_FILE=str(controllers),
            ROBOLINK_HOST="127.0.0.1", ROBOLINK_PORT="0", ROBOLINK_CONTROL_HOST="127.0.0.1", ROBOLINK_CONTROL_PORT="0",
            ROBOLINK_CONTROL_ORIGINS="http://localhost:5173", ROBOLINK_HEARTBEAT_INTERVAL_MS="100",
            ROBOLINK_HEARTBEAT_TIMEOUT_MS="800", ROBOLINK_ACK_TIMEOUT_MS="250", ROBOLINK_RESULT_TIMEOUT_MS="600")
        processes, peers, tasks = [], [], []

        async def start():
            classpath = os.pathsep.join((str(ROOT / "gateway/target/classes"), str(ROOT / "gateway/target/dependency/*")))
            process = await asyncio.create_subprocess_exec(str(Path(os.environ["JAVA_HOME"]) / "bin/java.exe"),
                "-cp", classpath, "dev.robolink.gateway.GatewayMain", env=environment,
                stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL)
            processes.append(process)
            tcp = (await asyncio.wait_for(process.stdout.readline(), 10)).decode().strip()
            ws = (await asyncio.wait_for(process.stdout.readline(), 5)).decode().strip()
            return process, int(tcp.rsplit(":", 1)[1]), int(ws.rsplit(":", 1)[1].split("/")[0])

        async def controller(port):
            peer = await ControlProbe.connect(port)
            peers.append(peer)
            await peer.send(pb.Envelope(protocol_version=1, control_authenticate=pb.ControlAuthenticate(controller_id="operator", credential=operator)))
            assert (await peer.until(lambda m: m.HasField("control_auth_result"))).control_auth_result.accepted
            await peer.send(pb.Envelope(protocol_version=1, subscribe=pb.Subscribe()))
            await peer.until(lambda m: m.HasField("snapshot"))
            return peer

        async def wait_for(predicate):
            async with asyncio.timeout(8):
                while not predicate():
                    await asyncio.sleep(0.02)

        try:
            process, tcp, ws = await start()
            environment["ROBOLINK_PORT"], environment["ROBOLINK_CONTROL_PORT"] = str(tcp), str(ws)
            robots = [RobotClient("RB-001", token), RobotClient("RB-002", token, faults=FaultPlan(drop_ack=True)),
                      RobotClient("RB-003", token, faults=FaultPlan(drop_result=True))]
            tasks = [asyncio.create_task(robot.run_forever(port=tcp)) for robot in robots]
            await wait_for(lambda: all(robot.ready.is_set() for robot in robots))
            peer = await controller(ws)
            await peer.submit("RB-001", "disconnect-motion", pb.ACTION_MOVE_FORWARD, duration=3000, speed=0.2)
            await peer.phase("disconnect-motion", pb.COMMAND_PHASE_ACKED)
            old_session = robots[0].session_id
            second_session = robots[1].session_id
            robots[0].disconnect()
            await peer.phase("disconnect-motion", pb.COMMAND_PHASE_UNKNOWN)
            await wait_for(lambda: robots[0].ready.is_set() and robots[0].session_id != old_session)
            assert robots[0].model.motion is None and robots[1].session_id == second_session
            frozen = robots[0].model.x
            await asyncio.sleep(0.12)
            assert robots[0].model.x == frozen

            await peer.submit("RB-002", "missing-ack", pb.ACTION_MOVE_FORWARD, duration=3000, speed=0.2)
            await peer.phase("missing-ack", pb.COMMAND_PHASE_ACK_TIMEOUT)
            await peer.submit("RB-002", "stop-after-ack", pb.ACTION_STOP)
            await peer.phase("stop-after-ack", pb.COMMAND_PHASE_SUCCEEDED)
            await peer.submit("RB-003", "missing-result", pb.ACTION_MOVE_FORWARD, duration=100, speed=0.2)
            await peer.phase("missing-result", pb.COMMAND_PHASE_RESULT_TIMEOUT)
            assert robots[2].model.motion is None

            await peer.submit("RB-001", "gateway-restart-motion", pb.ACTION_MOVE_FORWARD, duration=3000, speed=0.2)
            await peer.phase("gateway-restart-motion", pb.COMMAND_PHASE_ACKED)
            sessions = [robot.session_id for robot in robots]
            process.terminate()
            await asyncio.wait_for(process.wait(), 5)
            await wait_for(lambda: all(robot.model.motion is None for robot in robots))
            stopped_position = robots[0].model.x
            process, _, _ = await start()
            await wait_for(lambda: all(robot.ready.is_set() and robot.session_id != old for robot, old in zip(robots, sessions)))
            assert robots[0].model.x == stopped_position and all(robot.model.motion is None for robot in robots)
            peer = await controller(ws)
            await peer.submit("RB-001", "after-restart", pb.ACTION_MOVE_FORWARD, duration=100, speed=0.2)
            await peer.phase("after-restart", pb.COMMAND_PHASE_SUCCEEDED)
        finally:
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
            for peer in peers:
                peer.writer.close()
                with suppress(OSError):
                    await peer.writer.wait_closed()
            for process in processes:
                if process.returncode is None:
                    process.terminate()
                    await asyncio.wait_for(process.wait(), 5)
    return {"status": "PASS", "duration_seconds": round(time.monotonic() - started, 3),
            "checks": ["device reconnect changes session without replay", "other device session unaffected",
                       "missing ACK times out; STOP recovers", "missing result times out while robot stops locally",
                       "actual Java process restart; all three devices reconnect", "new motion after restart succeeds"],
            "scope": "localhost application faults and process restart, not real packet loss or load test"}
