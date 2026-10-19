"""Independent TCP device sessions, local stop and bounded reconnect backoff."""
import asyncio
from contextlib import suppress
import heapq
import math
import time
from google.protobuf.message import DecodeError

from .model import IDENTIFIER, RobotModel
from .protocol import frame, pb, read_frame
from .recovery import FaultPlan, retry_delay


class RobotClient:
    def __init__(self, robot_id, credential, model=None, telemetry_hz=10.0, faults=None, observer=None):
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
        self.faults = faults if faults is not None else FaultPlan()
        self.connections = 0
        self.state = "disconnected"
        self._writer_stream = None
        self.observer = observer
        self.received_messages = self.sent_messages = 0
        self.related_command_id = ""

    def _observe(self, stage, command_id="", seq=0):
        if self.observer:
            self.observer({"stage": stage, "command_id": command_id, "seq": seq,
                           "robot_id": self.robot_id, "session_id": self.session_id,
                           "observed_at_ms": time.time_ns() // 1_000_000})

    def disconnect(self):
        """Fault/demo hook: stop locally before closing the live transport."""
        self.model.disconnect(time.monotonic())
        if self._writer_stream:
            self._writer_stream.close()

    async def run_forever(self, host="127.0.0.1", port=9000, on_state=None):
        attempt = 0
        while True:
            connected_before = self.connections
            self.state = "connecting"
            try:
                await self.run(host, port)
            except (OSError, ValueError, DecodeError, asyncio.IncompleteReadError, TimeoutError):
                self.state = "retrying"
                if self.connections != connected_before:
                    attempt = 0
                delay = retry_delay(attempt)
                attempt += 1
                if on_state:
                    on_state(self.robot_id, self.state, round(delay, 2))
                await asyncio.sleep(delay)

    async def run(self, host="127.0.0.1", port=9000):
        if self._running:
            raise RuntimeError("Device is already running")
        self._running = True
        self.ready.clear()
        self.seq = self.gateway_seq = 0
        self.replies = []
        self.delivery_order = 0
        self.wake = asyncio.Event()
        self.latest = None
        self.heartbeat_pending = None
        self.final_telemetry_traced = set()
        self.related_command_id = ""
        tasks = []
        writer = None
        try:
            reader, writer = await asyncio.wait_for(asyncio.open_connection(host, port), 5)
            self._writer_stream = writer
            registration = pb.Envelope(protocol_version=1, robot_id=self.robot_id,
                                       register=pb.Register(credential=self._credential))
            writer.write(frame(registration))
            await asyncio.wait_for(writer.drain(), 2)
            self.sent_messages += 1
            response = await asyncio.wait_for(read_frame(reader), 5)
            self.received_messages += 1
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
            self.connected_at = time.monotonic()
            self.connections += 1
            self.state = "online"
            self._observe("DEVICE_REGISTERED", seq=response.seq)
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
            self.state = "disconnected"
            self._writer_stream = None
            for task in tasks:
                task.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
            if writer:
                writer.close()
                with suppress(OSError, TimeoutError):
                    await asyncio.wait_for(writer.wait_closed(), 2)
            self._running = False

    def _enqueue(self, payload):
        delivery_at = self.faults.delivery_at(payload, time.monotonic())
        if delivery_at is None:
            return
        self.delivery_order += 1
        if len(self.replies) >= 64:
            raise ConnectionError("Device reply queue full")
        heapq.heappush(self.replies, (delivery_at, self.delivery_order, payload))
        self.wake.set()

    async def _reader(self, reader):
        while True:
            message = await read_frame(reader)
            self.received_messages += 1
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
                self._observe("DEVICE_COMMAND_RECEIVED", message.command_request.command_id, message.seq)
                for payload in self.model.handle(message.command_request, time.monotonic()):
                    if isinstance(payload, pb.CommandAck) and payload.accepted:
                        self.related_command_id = payload.command_id
                    self._enqueue(payload)
                self._publish()
            else:
                raise ConnectionError("Unexpected gateway message")

    def _publish(self):
        self.latest = self.model.telemetry()
        self.latest.related_command_id = self.related_command_id
        self.wake.set()

    async def _ticker(self):
        next_telemetry = time.monotonic()
        while True:
            now = time.monotonic()
            if (self.faults.disconnect_after and not self.faults.disconnected
                    and now - self.connected_at >= self.faults.disconnect_after):
                self.faults.disconnected = True
                raise ConnectionError("Injected device disconnect")
            if self.heartbeat_pending and now - self.heartbeat_pending[1] >= self.heartbeat_timeout:
                raise ConnectionError("Heartbeat response timeout")
            outputs = self.model.advance(now)
            for payload in outputs:
                self._enqueue(payload)
            if outputs:
                self._publish()
            if now >= next_telemetry:
                self._publish()
                next_telemetry = now + 1 / self.telemetry_hz
            # Independent of telemetry rate: low reporting rates must not prolong motion.
            await asyncio.sleep(min(0.02, 1 / self.telemetry_hz))

    async def _writer(self, writer):
        while True:
            self.wake.clear()
            now = time.monotonic()
            delivery_due = self.replies[0][0] if self.replies else math.inf
            if delivery_due <= now:
                _, _, payload = heapq.heappop(self.replies)
            elif self.heartbeat_pending is None and now >= self.next_heartbeat:
                payload = pb.Heartbeat()
            elif self.latest is not None:
                payload, self.latest = self.latest, None
            else:
                delay = max(0.001, self.next_heartbeat - now) if self.heartbeat_pending is None else self.heartbeat_interval
                delay = min(delay, max(0.001, delivery_due - now))
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
            self.sent_messages += 1
            if field == "command_ack":
                self._observe("DEVICE_ACK_WRITE_COMPLETED", payload.command_id, self.seq)
            elif field == "command_result":
                self._observe("DEVICE_RESULT_WRITE_COMPLETED", payload.command_id, self.seq)
            if field == "telemetry":
                self.sent_telemetry += 1
                if (payload.related_command_id and payload.state == pb.ROBOT_STATE_IDLE
                        and payload.related_command_id not in self.final_telemetry_traced):
                    self.final_telemetry_traced.add(payload.related_command_id)
                    if len(self.final_telemetry_traced) > 256:
                        self.final_telemetry_traced = {payload.related_command_id}
                    self._observe("DEVICE_FINAL_TELEMETRY_WRITE_COMPLETED", payload.related_command_id, self.seq)

