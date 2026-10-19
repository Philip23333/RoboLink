"""Supervisor-owned child processes and bounded observations; no simulated PIDs."""
import asyncio
from collections import deque
from contextlib import suppress
import json
from pathlib import Path
import sys
import time


class ProcessFleet:
    def __init__(self, root: Path, credentials: Path):
        self.root, self.credentials = root, credentials
        self.slots = {f"RB-{i:03d}": {"id": f"RB-{i:03d}", "process": None, "reader": None,
            "started_at_ms": 0, "last_pid": None, "last_session": "", "updated_at_ms": 0, "error": "",
            "state": {"pose": {"x": (i - 1) * 2, "y": i - 1, "yaw": 0}, "battery": 88 - i},
            "lock": asyncio.Lock()} for i in range(1, 4)}
        self.events = deque(maxlen=600)
        self.closing = False

    async def start(self, identifier):
        slot = self.slots[identifier]
        async with slot["lock"]:
            if self.closing:
                raise RuntimeError("Fleet is shutting down")
            if slot["process"] and slot["process"].returncode is None:
                return
            if slot["reader"]:
                await slot["reader"]
            state = slot["state"]
            pose = state["pose"]
            process = await asyncio.create_subprocess_exec(sys._base_executable, str(self.root / "scripts/robot-worker.py"),
                "--robot-id", identifier, "--credential-file", str(self.credentials),
                "--x", str(pose["x"]), "--y", str(pose["y"]), "--yaw", str(pose["yaw"]),
                "--battery", str(state["battery"]), cwd=self.root,
                stdin=asyncio.subprocess.PIPE, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL)
            slot.update(process=process, last_pid=process.pid, started_at_ms=time.time_ns() // 1_000_000,
                        updated_at_ms=0, error="", state={"pose": pose, "battery": state["battery"],
                            "state": "starting", "session_id": "", "sent_messages": 0, "received_messages": 0})
            slot["reader"] = asyncio.create_task(self._read(slot, process))

    async def _read(self, slot, process):
        try:
            async for raw in process.stdout:
                value = json.loads(raw)
                if value.get("pid") != process.pid or value.get("robot_id") != slot["id"]:
                    raise ValueError("Worker identity mismatch")
                if value["kind"] == "state":
                    slot["state"] = value
                    slot["updated_at_ms"] = time.time_ns() // 1_000_000
                    if value.get("session_id"):
                        slot["last_session"] = value["session_id"]
                elif value["kind"] == "trace":
                    self.events.append({"source": "device", "robot": slot["id"], "pid": process.pid,
                        "command_id": value["command_id"], "stage": value["stage"],
                        "session": value["session_id"], "seq": str(value["seq"]), "at": value["observed_at_ms"]})
        except (ValueError, KeyError, OSError):
            slot["error"] = "工作进程观测格式或身份无效"
            if process.returncode is None:
                process.terminate()
        finally:
            await process.wait()

    async def stop(self, identifier):
        slot = self.slots[identifier]
        async with slot["lock"]:
            process = slot["process"]
            if process and process.returncode is None:
                with suppress(OSError, ConnectionError):
                    process.stdin.write(b"stop\n")
                    await process.stdin.drain()
                try:
                    await asyncio.wait_for(process.wait(), 5)
                except TimeoutError:
                    process.kill()
                    await process.wait()
            if slot["reader"]:
                await slot["reader"]

    def snapshot(self):
        now = time.time_ns() // 1_000_000
        robots = []
        for slot in self.slots.values():
            process = slot["process"]
            running = process is not None and process.returncode is None
            robots.append({"id": slot["id"], "running": running, "pid": process.pid if running else None,
                "last_pid": slot["last_pid"], "started_at_ms": slot["started_at_ms"],
                "updated_at_ms": slot["updated_at_ms"], "last_session": slot["last_session"],
                "state": slot["state"], "error": slot["error"]})
        return {"observed_at_ms": now, "robots": robots, "events": list(self.events)}

    async def close(self):
        self.closing = True
        await asyncio.gather(*(self.stop(identifier) for identifier in self.slots))
