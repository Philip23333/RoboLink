"""One independent TCP device session; automatic reconnection is a later step."""
import asyncio
from contextlib import suppress
import math
import time

from .model import IDENTIFIER, RobotModel
from .protocol import frame, pb, read_frame


class RobotClient:
    def __init__(self, robot_id, credential, model=None, telemetry_hz=10.0):
        if (not IDENTIFIER.fullmatch(robot_id) or not credential or len(credential.encode()) > 1024
                or not math.isfinite(telemetry_hz) or not 0 < telemetry_hz <= 100):
            raise ValueError("Invalid device configuration")
        self.robot_id, self._credential = robot_id, credential
        self.model = model if model is not None else RobotModel()
        self.telemetry_hz = telemetry_hz
        self.ready = asyncio.Event()
        self.session_id = ""
        self.sent_telemetry = 0
        self.seq = self.gateway_seq = 0
        self._running = False

    async def run(self, host="127.0.0.1", port=9000):
        if self._running:
            raise RuntimeError("Device is already running")
        self._running = True
        self.ready.clear()
        self.seq = self.gateway_seq = 0
        self.replies = asyncio.Queue(maxsize=64)
        self.wake = asyncio.Event()
        self.latest = None
        self.heartbeat_pending = None
        tasks = []
        writer = None
        try:
            reader, writer = await asyncio.wait_for(asyncio.open_connection(host, port), 5)
            registration = pb.Envelope(protocol_version=1, robot_id=self.robot_id,
                                       register=pb.Register(credential=self._credential))
            writer.write(frame(registration))
            await asyncio.wait_for(writer.drain(), 2)
            response = await asyncio.wait_for(read_frame(reader), 5)
            result = response.register_result
            if (response.WhichOneof("payload") != "register_result" or not result.accepted
                    or response.robot_id != self.robot_id or not response.session_id
                    or response.session_id != result.assigned_session_id
                    or not 0 < result.heartbeat_interval_ms < result.heartbeat_timeout_ms):
                raise ConnectionError("Device registration rejected or invalid response")
            self.session_id = response.session_id
            self.gateway_seq = response.seq
            self.heartbeat_interval = result.heartbeat_interval_ms / 1000
            self.heartbeat_timeout = result.heartbeat_timeout_ms / 1000
            self.next_heartbeat = time.monotonic()
            tasks = [asyncio.create_task(self._reader(reader)),
                     asyncio.create_task(self._writer(writer)),
                     asyncio.create_task(self._ticker())]
            self.ready.set()
            done, _ = await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
            for task in done:
                task.result()
            raise ConnectionError("Device session ended")
        finally:
            # Stop the local model before awaiting network cleanup or sibling cancellation.
            self.model.disconnect(time.monotonic())
            self.ready.clear()
            self.session_id = ""
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
            if writer:
                writer.close()
                with suppress(OSError, TimeoutError):
                    await asyncio.wait_for(writer.wait_closed(), 2)
            self._running = False

    def _enqueue(self, payload):
        try:
            self.replies.put_nowait(payload)
        except asyncio.QueueFull:
            raise ConnectionError("Device reply queue full") from None
        self.wake.set()

    async def _reader(self, reader):
        while True:
            message = await read_frame(reader)
            if message.robot_id != self.robot_id or message.session_id != self.session_id:
                raise ConnectionError("Gateway identity mismatch")
            if message.seq <= self.gateway_seq:
                continue
            self.gateway_seq = message.seq
            kind = message.WhichOneof("payload")
            if kind == "heartbeat_reply":
                if self.heartbeat_pending and message.heartbeat_reply.acknowledged_seq == self.heartbeat_pending[0]:
                    self.heartbeat_pending = None
                    self.wake.set()
            elif kind == "command_request":
                for payload in self.model.handle(message.command_request, time.monotonic()):
                    self._enqueue(payload)
                self._publish()
            else:
                raise ConnectionError("Unexpected gateway message")

    def _publish(self):
        self.latest = self.model.telemetry()
        self.wake.set()

    async def _ticker(self):
        next_telemetry = time.monotonic()
        while True:
            now = time.monotonic()
            if self.heartbeat_pending and now - self.heartbeat_pending[1] >= self.heartbeat_timeout:
                raise ConnectionError("Heartbeat response timeout")
            for payload in self.model.advance(now):
                self._enqueue(payload)
            if now >= next_telemetry:
                self._publish()
                next_telemetry = now + 1 / self.telemetry_hz
            # Independent of telemetry rate: low reporting rates must not prolong motion.
            await asyncio.sleep(min(0.02, 1 / self.telemetry_hz))

    async def _writer(self, writer):
        while True:
            self.wake.clear()
            now = time.monotonic()
            if not self.replies.empty():
                payload = self.replies.get_nowait()
            elif self.heartbeat_pending is None and now >= self.next_heartbeat:
                payload = pb.Heartbeat()
            elif self.latest is not None:
                payload, self.latest = self.latest, None
            else:
                delay = max(0.001, self.next_heartbeat - now) if self.heartbeat_pending is None else self.heartbeat_interval
                with suppress(TimeoutError):
                    await asyncio.wait_for(self.wake.wait(), delay)
                continue
            self.seq += 1
            message = pb.Envelope(protocol_version=1, robot_id=self.robot_id,
                                  session_id=self.session_id, seq=self.seq,
                                  timestamp_ms=time.time_ns() // 1_000_000)
            field = {pb.CommandAck: "command_ack", pb.CommandResult: "command_result",
                     pb.Telemetry: "telemetry", pb.Heartbeat: "heartbeat"}[type(payload)]
            getattr(message, field).CopyFrom(payload)
            if field == "heartbeat":
                self.heartbeat_pending = (self.seq, now)
                self.next_heartbeat = now + self.heartbeat_interval
            writer.write(frame(message))
            await asyncio.wait_for(writer.drain(), min(2.0, self.heartbeat_timeout))
            if field == "telemetry":
                self.sent_telemetry += 1

