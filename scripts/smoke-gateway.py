"""Start the actual Java main and probe it with Python; not the robot simulator."""
import json
import os
from pathlib import Path
import queue
import secrets
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import time

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "simulator" / "generated"))
import robolink_pb2 as pb


def send(connection, message):
    body = message.SerializeToString()
    connection.sendall(struct.pack("!I", len(body)) + body)


def read_exact(connection, size):
    result = bytearray()
    while len(result) < size:
        chunk = connection.recv(size - len(result))
        if not chunk:
            raise AssertionError("Unexpected EOF")
        result.extend(chunk)
    return bytes(result)


def receive(connection):
    size, = struct.unpack("!I", read_exact(connection, 4))
    if not 0 < size <= 65536:
        raise AssertionError("Invalid response frame length")
    return pb.Envelope.FromString(read_exact(connection, size))


def main():
    java_home = os.environ.get("JAVA_HOME")
    if not java_home:
        raise RuntimeError("JAVA_HOME is required; use the project's PowerShell entry")
    directory = ROOT / "artifacts" / "session"
    directory.mkdir(parents=True, exist_ok=True)
    report = directory / "smoke.json"
    report.unlink(missing_ok=True)
    started = time.monotonic()
    with tempfile.TemporaryDirectory(prefix="gateway-probe-", dir=directory) as temporary:
        credential = secrets.token_hex(24)
        credential_file = Path(temporary) / "devices.local.properties"
        credential_file.write_text(f"RB-SMOKE={credential}\n", encoding="utf-8")
        environment = dict(os.environ, ROBOLINK_HOST="127.0.0.1", ROBOLINK_PORT="0",
                           ROBOLINK_CREDENTIALS_FILE=str(credential_file),
                           ROBOLINK_REGISTRATION_TIMEOUT_MS="5000",
                           ROBOLINK_HEARTBEAT_INTERVAL_MS="500",
                           ROBOLINK_HEARTBEAT_TIMEOUT_MS="2000")
        # This device-only probe must not inherit another terminal's control listener configuration.
        environment.pop("ROBOLINK_CONTROL_CREDENTIALS_FILE", None)
        classpath = os.pathsep.join((str(ROOT / "gateway/target/classes"),
                                    str(ROOT / "gateway/target/dependency/*")))
        process = subprocess.Popen([str(Path(java_home) / "bin/java.exe"), "-cp", classpath,
                                    "dev.robolink.gateway.GatewayMain"], env=environment,
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        try:
            lines = queue.Queue()
            threading.Thread(target=lambda: lines.put(process.stdout.readline()), daemon=True).start()
            line = lines.get(timeout=15)
            prefix = "RoboLink TCP listening on 127.0.0.1:"
            if not line.startswith(prefix):
                raise AssertionError("Java main did not announce the expected localhost listener")
            port = int(line[len(prefix):].strip())
            with socket.create_connection(("127.0.0.1", port), timeout=3) as connection:
                send(connection, pb.Envelope(protocol_version=1, robot_id="RB-SMOKE",
                                             register=pb.Register(credential=credential)))
                response = receive(connection)
                if not response.register_result.accepted or not response.session_id:
                    raise AssertionError("Registration failed")
                if response.session_id != response.register_result.assigned_session_id:
                    raise AssertionError("Registration session mismatch")
                session = response.session_id
                send(connection, pb.Envelope(protocol_version=1, robot_id="RB-SMOKE", session_id=session,
                                             seq=1, telemetry=pb.Telemetry(battery_percent=87,
                                                 pose=pb.Pose(x_m=1.25, y_m=-2.5, yaw_rad=0.5),
                                                 state=pb.ROBOT_STATE_IDLE)))
                send(connection, pb.Envelope(protocol_version=1, robot_id="RB-SMOKE", session_id=session,
                                             seq=2, heartbeat=pb.Heartbeat()))
                response = receive(connection)
                if response.session_id != session or response.heartbeat_reply.acknowledged_seq != 2:
                    raise AssertionError("Heartbeat acknowledgement mismatch")
                send(connection, pb.Envelope(protocol_version=1, robot_id="OTHER", session_id=session,
                                             seq=3, heartbeat=pb.Heartbeat()))
                if connection.recv(1) != b"":
                    raise AssertionError("Identity spoof was not rejected")
            with socket.create_connection(("127.0.0.1", port), timeout=3) as connection:
                send(connection, pb.Envelope(protocol_version=1, robot_id="RB-SMOKE",
                                             register=pb.Register(credential="wrong-probe-credential")))
                if receive(connection).register_result.accepted or connection.recv(1) != b"":
                    raise AssertionError("Invalid credential was not rejected")
        finally:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
            process.stdout.close()
    report.write_text(json.dumps({
        "status": "PASS", "duration_seconds": round(time.monotonic() - started, 2),
        "checks": ["actual Java main startup", "local credential file", "Python TCP registration",
                   "telemetry then heartbeat acknowledgement", "identity mismatch EOF", "bad credential rejection"],
        "listener": "127.0.0.1:ephemeral",
        "cleanup": "test Java process exited and temporary credential file removed",
        "scope": "startup and protocol probe; no full simulator or motion; graceful shutdown covered separately by JUnit"
    }, ensure_ascii=False, indent=2), encoding="utf-8")
    print("Java main + Python TCP probe: PASS (temporary process and credentials cleaned)")


if __name__ == "__main__":
    main()
