import asyncio
from contextlib import suppress
from pathlib import Path
import struct
import tempfile
import time
import unittest

from robolink_sim.__main__ import credentials
from robolink_sim.client import RobotClient
from robolink_sim.model import RobotModel
from robolink_sim.protocol import frame, pb, read_frame
from test_model import command


class FramingTests(unittest.IsolatedAsyncioTestCase):
    async def test_split_header_body_and_consecutive_frames(self):
        reader = asyncio.StreamReader()
        message = pb.Envelope(protocol_version=1, robot_id="RB-001", heartbeat=pb.Heartbeat())
        raw = frame(message) * 2

        async def feed():
            for byte in raw:
                reader.feed_data(bytes([byte]))
                await asyncio.sleep(0)
        feeding = asyncio.create_task(feed())
        self.assertEqual(await read_frame(reader), message)
        self.assertEqual(await read_frame(reader), message)
        await feeding

    async def test_invalid_size_payload_and_eof(self):
        for raw in (struct.pack("!I", 0), struct.pack("!I", 65537),
                    struct.pack("!I", 1) + b"\xff", b"\x00\x00"):
            reader = asyncio.StreamReader()
            reader.feed_data(raw)
            reader.feed_eof()
            with self.assertRaises(Exception):
                await read_frame(reader)

    async def test_unknown_version_or_empty_payload_rejected(self):
        for message in (pb.Envelope(protocol_version=2, heartbeat=pb.Heartbeat()),
                        pb.Envelope(protocol_version=1)):
            reader = asyncio.StreamReader()
            reader.feed_data(frame(message))
            with self.assertRaises(ValueError):
                await read_frame(reader)


class ConfigTests(unittest.TestCase):
    def test_config_accepts_bom_and_rejects_ambiguous_properties(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "devices.local.properties"
            path.write_text("\ufeff# local\nRB-001=public-test-token\n", encoding="utf-8")
            self.assertEqual(list(credentials(path)), ["RB-001"])
            for content in ("RB-001=a\nRB-001=b", "RB-001", "RB-001=", "RB-001=a\\b"):
                path.write_text(content, encoding="utf-8")
                with self.assertRaises(ValueError):
                    credentials(path)


class ClientTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.connections = asyncio.Queue()
        self.server = await asyncio.start_server(lambda r, w: self.connections.put_nowait((r, w)), "127.0.0.1", 0)
        self.port = self.server.sockets[0].getsockname()[1]
        self.robot = RobotClient("RB-001", "public-test-token", telemetry_hz=10)
        self.task = asyncio.create_task(self.robot.run(port=self.port))
        self.reader, self.writer = await asyncio.wait_for(self.connections.get(), 2)
        registration = await asyncio.wait_for(read_frame(self.reader), 2)
        self.assertEqual(registration.register.credential, "public-test-token")
        self.gateway_seq = 0

    async def asyncTearDown(self):
        self.task.cancel()
        await asyncio.gather(self.task, return_exceptions=True)
        self.writer.close()
        with suppress(OSError):
            await self.writer.wait_closed()
        self.server.close()
        await self.server.wait_closed()

    async def send(self, field, payload):
        self.gateway_seq += 1
        message = pb.Envelope(protocol_version=1, robot_id="RB-001", session_id="test-session", seq=self.gateway_seq)
        getattr(message, field).CopyFrom(payload)
        self.writer.write(frame(message))
        await self.writer.drain()

    async def register(self, timeout_ms=500):
        await self.send("register_result", pb.RegisterResult(accepted=True, assigned_session_id="test-session",
                                                            heartbeat_interval_ms=100, heartbeat_timeout_ms=timeout_ms))
        await asyncio.wait_for(self.robot.ready.wait(), 2)

    async def receive(self, field):
        async with asyncio.timeout(3):
            while True:
                message = await read_frame(self.reader)
                if message.HasField("heartbeat"):
                    await self.send("heartbeat_reply", pb.HeartbeatReply(acknowledged_seq=message.seq))
                if message.WhichOneof("payload") == field:
                    return message

    async def test_registration_rejection_exits_without_online_state(self):
        self.writer.write(frame(pb.Envelope(protocol_version=1,
                                register_result=pb.RegisterResult(accepted=False))))
        await self.writer.drain()
        with self.assertRaises(ConnectionError):
            await asyncio.wait_for(self.task, 2)
        self.assertFalse(self.robot.ready.is_set())
        self.assertEqual(self.robot.session_id, "")

    async def test_serial_writer_sequences_and_real_motion(self):
        await self.register()
        await self.send("command_request", command(duration=200))
        ack = await self.receive("command_ack")
        result = await self.receive("command_result")
        telemetry = await self.receive("telemetry")
        self.assertTrue(ack.command_ack.accepted)
        self.assertLess(ack.seq, result.seq)
        self.assertLess(result.seq, telemetry.seq)
        self.assertEqual(result.command_result.outcome, pb.COMMAND_OUTCOME_SUCCEEDED)
        self.assertAlmostEqual(telemetry.telemetry.pose.x_m, 0.04, places=6)

    async def test_heartbeat_silence_stops_motion_even_while_telemetry_flows(self):
        await self.register(timeout_ms=180)
        await self.send("command_request", command(duration=3000))
        with self.assertRaisesRegex(ConnectionError, "Heartbeat response timeout"):
            await asyncio.wait_for(self.task, 2)
        self.assertGreater(self.robot.sent_telemetry, 0)
        self.assertIsNone(self.robot.model.motion)
        position = self.robot.model.x
        self.robot.model.advance(time.monotonic() + 10)
        self.assertEqual(self.robot.model.x, position)

    async def test_tcp_eof_stops_active_motion(self):
        await self.register()
        await self.send("command_request", command(duration=3000))
        await self.receive("command_ack")
        self.writer.close()
        with self.assertRaises((asyncio.IncompleteReadError, ConnectionError)):
            await asyncio.wait_for(self.task, 2)
        self.assertIsNone(self.robot.model.motion)

    async def test_low_telemetry_rate_does_not_delay_motion_completion(self):
        self.robot.telemetry_hz = 0.2
        await self.register()
        started = time.monotonic()
        await self.send("command_request", command(duration=100))
        await self.receive("command_result")
        self.assertLess(time.monotonic() - started, 1)
        self.assertAlmostEqual(self.robot.model.x, 0.02, places=6)

    async def test_wrong_gateway_session_closes_device(self):
        await self.register()
        self.writer.write(frame(pb.Envelope(protocol_version=1, robot_id="RB-001", session_id="old-session",
                                seq=2, command_request=command())))
        await self.writer.drain()
        with self.assertRaisesRegex(ConnectionError, "identity mismatch"):
            await asyncio.wait_for(self.task, 2)
        self.assertIsNone(self.robot.model.motion)

