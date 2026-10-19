"""Isolated localhost fixture for browser acceptance. Not a production admin API."""
import asyncio
from contextlib import suppress
import json
import os
from pathlib import Path
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "simulator"))
from robolink_sim.client import RobotClient
from robolink_sim.model import RobotModel
from robolink_sim.recovery import FaultPlan

TOKEN = "public-browser-fixture-only"


async def main():
    processes, robot_tasks = [], {}
    robots = {}
    with tempfile.TemporaryDirectory(prefix="robolink-browser-") as directory:
        devices, controllers = Path(directory) / "devices.local.properties", Path(directory) / "controllers.local.properties"
        devices.write_text("\n".join(f"RB-{i:03d}={TOKEN}" for i in range(1, 4)), encoding="utf-8")
        controllers.write_text(f"operator.token={TOKEN}\noperator.robots=RB-001,RB-002,RB-003\n", encoding="utf-8")
        environment = dict(os.environ, ROBOLINK_CREDENTIALS_FILE=str(devices), ROBOLINK_CONTROL_CREDENTIALS_FILE=str(controllers),
            ROBOLINK_HOST="127.0.0.1", ROBOLINK_PORT="19000", ROBOLINK_CONTROL_HOST="127.0.0.1", ROBOLINK_CONTROL_PORT="19001",
            ROBOLINK_CONTROL_ORIGINS="http://127.0.0.1:5173,http://localhost:5173",
            ROBOLINK_HEARTBEAT_INTERVAL_MS="200", ROBOLINK_HEARTBEAT_TIMEOUT_MS="1000",
            ROBOLINK_ACK_TIMEOUT_MS="500", ROBOLINK_RESULT_TIMEOUT_MS="3500")

        async def start_gateway():
            classpath = os.pathsep.join((str(ROOT / "gateway/target/classes"), str(ROOT / "gateway/target/dependency/*")))
            process = await asyncio.create_subprocess_exec(str(Path(os.environ["JAVA_HOME"]) / "bin/java.exe"),
                "-cp", classpath, "dev.robolink.gateway.GatewayMain", env=environment,
                stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL)
            processes.append(process)
            for _ in range(2):
                line = await asyncio.wait_for(process.stdout.readline(), 10)
                if not line.startswith(b"RoboLink"):
                    raise RuntimeError("Browser fixture gateway failed to start (check ports 19000/19001)")
            return process

        gateway = await start_gateway()

        def start_robot(index):
            identifier = f"RB-{index:03d}"
            robot = robots.setdefault(identifier, RobotClient(identifier, TOKEN,
                                      RobotModel(x=(index - 1) * 2, y=index - 1)))
            robot_tasks[identifier] = asyncio.create_task(robot.run_forever(port=19000))

        for index in range(1, 4):
            start_robot(index)

        async def request(reader, writer):
            nonlocal gateway
            status, body = 200, {"ready": True}
            try:
                headers = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), 2)
                first = headers.split(b"\r\n", 1)[0].decode()
                method, path, _ = first.split(" ")
                # Requests come from the test runner, never cross-origin browser scripts.
                if b"origin:" in headers.lower():
                    status, body = 403, {"error": "test runner only"}
                elif method == "GET" and path == "/ready":
                    pass
                elif method == "POST" and path == "/restart":
                    gateway.terminate()
                    await gateway.wait()
                    await asyncio.sleep(0.4)
                    gateway = await start_gateway()
                elif method == "POST" and path.startswith("/device/"):
                    _, _, identifier, operation = path.split("/")
                    robot = robots[identifier]
                    if operation == "pause":
                        robot_tasks[identifier].cancel()
                        await asyncio.gather(robot_tasks[identifier], return_exceptions=True)
                    elif operation == "resume":
                        if robot_tasks[identifier].done():
                            robot_tasks[identifier] = asyncio.create_task(robot.run_forever(port=19000))
                    elif operation == "disconnect":
                        robot.disconnect()
                    elif operation == "drop-ack":
                        robot.faults = FaultPlan(drop_ack=True)
                    elif operation == "drop-result":
                        robot.faults = FaultPlan(drop_result=True)
                    elif operation == "reset":
                        robot.faults = FaultPlan()
                    else:
                        status = 404
                else:
                    status = 404
            except Exception:
                status, body = 500, {"error": "fixture operation failed"}
            data = json.dumps(body).encode()
            writer.write(f"HTTP/1.1 {status} OK\r\nContent-Type: application/json\r\nContent-Length: {len(data)}\r\nConnection: close\r\n\r\n".encode() + data)
            with suppress(OSError):
                await writer.drain()
            writer.close()
            with suppress(OSError):
                await writer.wait_closed()

        server = await asyncio.start_server(request, "127.0.0.1", 19005)
        print("Browser acceptance fixture ready on localhost", flush=True)
        try:
            async with server:
                await server.serve_forever()
        finally:
            for task in robot_tasks.values():
                task.cancel()
            await asyncio.gather(*robot_tasks.values(), return_exceptions=True)
            for process in processes:
                if process.returncode is None:
                    process.terminate()
                    await process.wait()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
