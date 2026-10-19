"""Local demo supervisor. Owns only the Java/Vite children it starts."""
import asyncio
from contextlib import suppress
import json
import os
from pathlib import Path
import secrets
import shutil
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "simulator"))
from robolink_sim.__main__ import credentials
from robolink_sim.client import RobotClient
from robolink_sim.model import RobotModel


async def main():
    devices, controllers = ROOT / "gateway/devices.local.properties", ROOT / "gateway/controllers.local.properties"
    if not devices.exists():
        devices.write_text("\n".join(f"RB-{i:03d}={secrets.token_hex(24)}" for i in range(1, 4)) + "\n", encoding="utf-8")
    if not controllers.exists():
        controllers.write_text(f"operator.token={secrets.token_hex(24)}\noperator.robots=RB-001,RB-002,RB-003\n", encoding="utf-8")
    tokens = credentials(devices)
    control_config = credentials(controllers)
    if not control_config.get("operator.token") or not {"RB-001", "RB-002", "RB-003"}.issubset(set(control_config.get("operator.robots", "").split(","))):
        raise ValueError("Local demo requires operator and three device scopes")
    directory = ROOT / "artifacts/demo"
    directory.mkdir(parents=True, exist_ok=True)
    ready_file = directory / "ready.json"
    ready_file.unlink(missing_ok=True)
    environment = dict(os.environ, ROBOLINK_CREDENTIALS_FILE=str(devices), ROBOLINK_CONTROL_CREDENTIALS_FILE=str(controllers),
        ROBOLINK_HOST="127.0.0.1", ROBOLINK_PORT="9000", ROBOLINK_CONTROL_HOST="127.0.0.1", ROBOLINK_CONTROL_PORT="9001",
        ROBOLINK_CONTROL_ORIGINS="http://127.0.0.1:5173,http://localhost:5173",
        ROBOLINK_HEARTBEAT_INTERVAL_MS="3000", ROBOLINK_HEARTBEAT_TIMEOUT_MS="10000",
        ROBOLINK_ACK_TIMEOUT_MS="2000", ROBOLINK_RESULT_TIMEOUT_MS="5000",
        ROBOLINK_DEMO_CONTROL_FILE=str(controllers))
    children, tasks = [], []
    stop = asyncio.Event()
    server = None
    try:
        classpath = os.pathsep.join((str(ROOT / "gateway/target/classes"), str(ROOT / "gateway/target/dependency/*")))
        java = await asyncio.create_subprocess_exec(str(Path(os.environ["JAVA_HOME"]) / "bin/java.exe"),
            "-cp", classpath, "dev.robolink.gateway.GatewayMain", env=environment,
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL)
        children.append(java)
        for _ in range(2):
            if not (await asyncio.wait_for(java.stdout.readline(), 10)).startswith(b"RoboLink"):
                raise RuntimeError("Gateway did not start; check ports 9000/9001")
        robots = [RobotClient(f"RB-{i:03d}", tokens[f"RB-{i:03d}"], RobotModel(x=(i - 1) * 2, y=i - 1, battery=88 - i)) for i in range(1, 4)]
        tasks = [asyncio.create_task(robot.run_forever()) for robot in robots]
        await asyncio.wait_for(asyncio.gather(*(robot.ready.wait() for robot in robots)), 8)
        vite = await asyncio.create_subprocess_exec(shutil.which("node"), str(ROOT / "console/node_modules/vite/bin/vite.js"),
            "--host", "127.0.0.1", "--port", "5173", "--strictPort", cwd=ROOT / "console", env=environment,
            stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.DEVNULL)
        children.append(vite)
        async with asyncio.timeout(15):
            while True:
                if vite.returncode is not None:
                    raise RuntimeError("Page did not start; check port 5173")
                try:
                    reader, writer = await asyncio.open_connection("127.0.0.1", 5173)
                    writer.write(b"GET /__demo/available HTTP/1.1\r\nHost: 127.0.0.1:5173\r\nConnection: close\r\n\r\n")
                    await writer.drain()
                    response = await asyncio.wait_for(reader.read(), 2)
                    writer.close()
                    await writer.wait_closed()
                    if b'"available":true' in response:
                        break
                except OSError:
                    pass
                await asyncio.sleep(0.1)

        async def admin(reader, writer):
            status, body = 200, {"ready": True, "url": "http://127.0.0.1:5173", "root": str(ROOT)}
            try:
                headers = await asyncio.wait_for(reader.readuntil(b"\r\n\r\n"), 2)
                first = headers.split(b"\r\n", 1)[0]
                if b"origin:" in headers.lower():
                    status, body = 403, {}
                elif first == b"POST /stop HTTP/1.1":
                    stop.set()
                elif first != b"GET /ready HTTP/1.1":
                    status, body = 404, {}
            except Exception:
                status, body = 400, {}
            data = json.dumps(body).encode()
            writer.write(f"HTTP/1.1 {status} OK\r\nContent-Type: application/json\r\nContent-Length: {len(data)}\r\nConnection: close\r\n\r\n".encode() + data)
            with suppress(OSError):
                await writer.drain()
            writer.close()

        server = await asyncio.start_server(admin, "127.0.0.1", 19006)
        ready_file.write_text(json.dumps({"pid": os.getpid(), "root": str(ROOT), "url": "http://127.0.0.1:5173",
                                         "children": [child.pid for child in children]}), encoding="utf-8")
        print("RoboLink demo ready: http://127.0.0.1:5173", flush=True)
        # Exit if a child service fails, rather than leaving a half-working demo.
        waiting = [asyncio.create_task(stop.wait()), *(asyncio.create_task(child.wait()) for child in children)]
        await asyncio.wait(waiting, return_when=asyncio.FIRST_COMPLETED)
        for task in waiting:
            task.cancel()
        await asyncio.gather(*waiting, return_exceptions=True)
    finally:
        if server:
            server.close()
            await server.wait_closed()
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        for child in reversed(children):
            if child.returncode is None:
                child.terminate()
                with suppress(TimeoutError):
                    await asyncio.wait_for(child.wait(), 5)
                if child.returncode is None:
                    child.kill()
                    await child.wait()
        ready_file.unlink(missing_ok=True)


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
    except Exception:
        print("Demo startup failed. Check local credentials and ports 9000, 9001, 5173, 19006.", file=sys.stderr)
        sys.exit(1)
