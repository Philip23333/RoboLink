"""Small, local-only WebSocket test driver; never used by the simulator runtime."""
import asyncio
import base64
from collections import Counter
from contextlib import suppress
import hashlib
import json
import os
from pathlib import Path
import secrets
import struct
import tempfile
import time

from robolink_sim.client import RobotClient
from robolink_sim.model import RobotModel
from robolink_sim.protocol import pb

ROOT = Path(__file__).resolve().parents[2]


class ControlProbe:
    def __init__(self, reader, writer):
        self.reader, self.writer = reader, writer
        self.messages = []
        self.telemetry = {}
        self.counts = Counter()

    @classmethod
    async def connect(cls, port):
        reader, writer = await asyncio.open_connection("127.0.0.1", port)
        try:
            key = base64.b64encode(secrets.token_bytes(16)).decode()
            writer.write((f"GET /control HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\n"
                          "Upgrade: websocket\r\nConnection: Upgrade\r\n"
                          f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\n"
                          "Origin: http://localhost:5173\r\n\r\n").encode())
            await writer.drain()
            response = (await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), 3)).decode("ascii")
            expected = base64.b64encode(hashlib.sha1((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").encode()).digest()).decode()
            headers = dict(line.split(": ", 1) for line in response.split("\r\n")[1:] if ": " in line)
            if not response.startswith("HTTP/1.1 101 ") or next((v for k, v in headers.items() if k.lower() == "sec-websocket-accept"), None) != expected:
                raise AssertionError("WebSocket upgrade failed")
            return cls(reader, writer)
        except BaseException:
            writer.close()
            await writer.wait_closed()
            raise

    async def _frame(self, body, opcode=2):
        mask = secrets.token_bytes(4)
        length = len(body)
        header = bytes([0x80 | opcode, 0x80 | length]) if length < 126 else bytes([0x80 | opcode, 0xFE]) + struct.pack("!H", length)
        self.writer.write(header + mask + bytes(value ^ mask[index % 4] for index, value in enumerate(body)))
        await self.writer.drain()

    async def send(self, message):
        await self._frame(message.SerializeToString())

    async def receive(self):
        body = bytearray()
        while True:
            first, second = await self.reader.readexactly(2)
            size = second & 127
            if size == 126:
                size, = struct.unpack("!H", await self.reader.readexactly(2))
            elif size == 127:
                size, = struct.unpack("!Q", await self.reader.readexactly(8))
            if size > 65536 or second & 128:
                raise AssertionError("Invalid server WebSocket frame")
            piece = await self.reader.readexactly(size)
            opcode = first & 15
            if opcode == 9:
                await self._frame(piece, 10)
                continue
            if opcode == 8:
                raise ConnectionError("Control socket closed")
            if opcode not in (0, 2):
                raise AssertionError("Expected binary data")
            body.extend(piece)
            if len(body) > 65536:
                raise AssertionError("Oversized aggregate")
            if first & 128:
                break
        message = pb.Envelope.FromString(body)
        self.messages.append(message)
        if message.HasField("telemetry"):
            self.telemetry[message.robot_id] = message.telemetry
            self.counts[message.robot_id] += 1
        if message.HasField("snapshot"):
            for robot in message.snapshot.robots:
                if robot.HasField("latest_telemetry"):
                    self.telemetry[robot.robot_id] = robot.latest_telemetry
        return message

    async def until(self, predicate, timeout=5):
        async with asyncio.timeout(timeout):
            while True:
                message = await self.receive()
                if predicate(message):
                    return message

    async def phase(self, command_id, phase):
        return await self.until(lambda m: m.HasField("command_status")
                                and m.command_status.command_id == command_id and m.command_status.phase == phase)

    async def submit(self, robot_id, command_id, action, duration=400, speed=0, angular=0):
        request = pb.CommandRequest(command_id=command_id, action=action)
        if action != pb.ACTION_STOP:
            request.motion.CopyFrom(pb.MotionParameters(duration_ms=duration, speed_mps=speed,
                                                        angular_speed_rad_s=angular))
        message = pb.Envelope(protocol_version=1, robot_id=robot_id, command_request=request)
        await self.send(message)
        return message


async def gateway_check():
    started = time.monotonic()
    directory = ROOT / "artifacts" / "simulator"
    directory.mkdir(parents=True, exist_ok=True)
    observations = {}
    with tempfile.TemporaryDirectory(prefix="gateway-", dir=directory) as temporary:
        ids = [f"RB-{index:03d}" for index in range(1, 4)]
        tokens = {identifier: secrets.token_hex(24) for identifier in ids}
        operator_token = secrets.token_hex(24)
        device_file, control_file = Path(temporary) / "devices.local.properties", Path(temporary) / "controllers.local.properties"
        device_file.write_text("\n".join(f"{key}={value}" for key, value in tokens.items()), encoding="utf-8")
        control_file.write_text(f"operator.token={operator_token}\noperator.robots={','.join(ids)}\n", encoding="utf-8")
        environment = dict(os.environ, ROBOLINK_CREDENTIALS_FILE=str(device_file),
                           ROBOLINK_CONTROL_CREDENTIALS_FILE=str(control_file),
                           ROBOLINK_HOST="127.0.0.1", ROBOLINK_PORT="0",
                           ROBOLINK_CONTROL_HOST="127.0.0.1", ROBOLINK_CONTROL_PORT="0",
                           ROBOLINK_CONTROL_ORIGINS="http://localhost:5173",
                           ROBOLINK_REGISTRATION_TIMEOUT_MS="5000", ROBOLINK_HEARTBEAT_INTERVAL_MS="200",
                           ROBOLINK_HEARTBEAT_TIMEOUT_MS="1500", ROBOLINK_ACK_TIMEOUT_MS="2000",
                           ROBOLINK_RESULT_TIMEOUT_MS="5000")
        classpath = os.pathsep.join((str(ROOT / "gateway/target/classes"), str(ROOT / "gateway/target/dependency/*")))
        process = await asyncio.create_subprocess_exec(str(Path(os.environ["JAVA_HOME"]) / "bin/java.exe"),
                    "-cp", classpath, "dev.robolink.gateway.GatewayMain", env=environment,
                    stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL)
        tasks, control = [], None
        cli = None
        try:
            tcp_line = (await asyncio.wait_for(process.stdout.readline(), 15)).decode().strip()
            ws_line = (await asyncio.wait_for(process.stdout.readline(), 5)).decode().strip()
            if not tcp_line.startswith("RoboLink TCP listening on 127.0.0.1:") or not ws_line.startswith("RoboLink WebSocket listening on 127.0.0.1:"):
                raise AssertionError("Java listener startup failed")
            tcp_port = int(tcp_line.rsplit(":", 1)[1])
            ws_port = int(ws_line.rsplit(":", 1)[1].split("/")[0])
            robots = [RobotClient(identifier, tokens[identifier], RobotModel(x=index * 2, y=index))
                      for index, identifier in enumerate(ids)]
            tasks = [asyncio.create_task(robot.run(port=tcp_port)) for robot in robots]
            await asyncio.wait_for(asyncio.gather(*(robot.ready.wait() for robot in robots)), 5)
            control = await ControlProbe.connect(ws_port)
            await control.send(pb.Envelope(protocol_version=1, control_authenticate=pb.ControlAuthenticate(
                                controller_id="operator", credential=operator_token)))
            auth = await control.until(lambda m: m.HasField("control_auth_result"))
            assert auth.control_auth_result.accepted
            await control.send(pb.Envelope(protocol_version=1, subscribe=pb.Subscribe()))
            snapshot = await control.until(lambda m: m.HasField("snapshot"))
            assert len(snapshot.snapshot.robots) == 3 and all(r.online for r in snapshot.snapshot.robots)
            await control.until(lambda _: len(control.telemetry) == 3)

            request = await control.submit(ids[0], "integration-forward", pb.ACTION_MOVE_FORWARD, speed=0.2)
            await control.phase("integration-forward", pb.COMMAND_PHASE_ACKED)
            await control.phase("integration-forward", pb.COMMAND_PHASE_SUCCEEDED)
            await control.until(lambda m: m.robot_id == ids[0] and m.HasField("telemetry")
                                and m.telemetry.state == pb.ROBOT_STATE_IDLE)
            x = control.telemetry[ids[0]].pose.x_m
            assert abs(x - 0.08) < 1e-6, x
            assert control.telemetry[ids[1]].pose.x_m == 2 and control.telemetry[ids[2]].pose.x_m == 4
            observations["forward_distance_m"] = round(x, 6)
            await control.send(request)
            await control.phase("integration-forward", pb.COMMAND_PHASE_SUCCEEDED)
            assert abs(robots[0].model.x - x) < 1e-6

            await control.submit(ids[1], "integration-left", pb.ACTION_TURN_LEFT, angular=0.5)
            await control.phase("integration-left", pb.COMMAND_PHASE_SUCCEEDED)
            await control.until(lambda m: m.robot_id == ids[1] and m.HasField("telemetry")
                                and m.telemetry.state == pb.ROBOT_STATE_IDLE)
            assert abs(control.telemetry[ids[1]].pose.yaw_rad - 0.2) < 1e-6
            await control.submit(ids[1], "integration-right", pb.ACTION_TURN_RIGHT, duration=200, angular=0.5)
            await control.phase("integration-right", pb.COMMAND_PHASE_SUCCEEDED)
            await control.until(lambda m: m.robot_id == ids[1] and m.HasField("telemetry")
                                and m.telemetry.state == pb.ROBOT_STATE_IDLE)
            observations["turn_net_yaw_rad"] = round(control.telemetry[ids[1]].pose.yaw_rad, 6)
            assert abs(observations["turn_net_yaw_rad"] - 0.1) < 1e-6

            await control.submit(ids[2], "integration-long", pb.ACTION_MOVE_FORWARD, duration=3000, speed=0.2)
            await control.phase("integration-long", pb.COMMAND_PHASE_ACKED)
            await control.until(lambda m: m.robot_id == ids[2] and m.HasField("telemetry")
                                and m.telemetry.pose.x_m > 4.01)
            await control.submit(ids[2], "integration-stop", pb.ACTION_STOP)
            await control.phase("integration-stop", pb.COMMAND_PHASE_SUCCEEDED)
            assert any(m.HasField("command_result") and m.command_result.command_id == "integration-long"
                       and m.command_result.outcome == pb.COMMAND_OUTCOME_INTERRUPTED for m in control.messages)
            await control.until(lambda m: m.robot_id == ids[2] and m.HasField("telemetry")
                                and m.telemetry.state == pb.ROBOT_STATE_IDLE)
            stop_x = control.telemetry[ids[2]].pose.x_m
            await asyncio.sleep(0.15)
            assert abs(robots[2].model.x - stop_x) < 1e-6
            observations["stop_distance_m"] = round(stop_x - 4, 6)

            await control.submit(ids[0], "integration-invalid", pb.ACTION_MOVE_FORWARD, speed=0.6)
            rejected = await control.phase("integration-invalid", pb.COMMAND_PHASE_REJECTED)
            assert rejected.command_status.reason == "INVALID_PARAMETERS"
            observations["received_telemetry_counts"] = dict(control.counts)
            observations["device_sent_telemetry_counts"] = {robot.robot_id: robot.sent_telemetry for robot in robots}
            assert all(count > 5 for count in observations["device_sent_telemetry_counts"].values())

            # Also exercise the actual user-facing CLI, not only in-process clients.
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
            tasks = []
            cli = await asyncio.create_subprocess_exec(str(ROOT / ".venv/Scripts/python.exe"),
                        "-m", "robolink_sim", "--credential-file", str(device_file), "--port", str(tcp_port),
                        cwd=ROOT / "simulator", stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL)
            line = await asyncio.wait_for(cli.stdout.readline(), 5)
            assert line.startswith(b"Connected 3 simulated robots at 10 Hz"), "CLI failed to start"
            await control.send(pb.Envelope(protocol_version=1, subscribe=pb.Subscribe()))
            snapshot = await control.until(lambda m: m.HasField("snapshot"))
            assert all(r.online for r in snapshot.snapshot.robots)
            await control.submit(ids[0], "cli-forward", pb.ACTION_MOVE_FORWARD, duration=200, speed=0.2)
            await control.phase("cli-forward", pb.COMMAND_PHASE_SUCCEEDED)
            observations["cli_three_robot_control"] = "PASS"
        finally:
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
            if cli and cli.returncode is None:
                cli.terminate()
                await asyncio.wait_for(cli.wait(), 5)
            if control:
                control.writer.close()
                with suppress(OSError):
                    await control.writer.wait_closed()
            if process.returncode is None:
                process.terminate()
                try:
                    await asyncio.wait_for(process.wait(), 5)
                except TimeoutError:
                    process.kill()
                    await process.wait()
    return {"status": "PASS", "duration_seconds": round(time.monotonic() - started, 3),
            "robot_count": 3, "configured_telemetry_hz": 10, "observations": observations,
            "checks": ["actual Java main and Python TCP devices", "authenticated binary WebSocket subscription",
                       "three independent devices", "forward ACK/result/telemetry", "left and right turn",
                       "duplicate controller request replay", "STOP interrupts active command",
                       "invalid parameters rejected", "CLI starts three devices and executes command"],
            "cleanup": "temporary Java/CLI processes, device tasks, sockets and credential files removed",
            "scope": "localhost motion/CLI smoke; automatic recovery checked separately; no hardware or throughput benchmark"}

