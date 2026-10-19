import asyncio
from contextlib import suppress
import unittest

from robolink_sim.client import RobotClient
from robolink_sim.protocol import frame, pb, read_frame
from robolink_sim.recovery import FaultPlan, retry_delay
from test_model import command


class RecoveryPolicyTests(unittest.TestCase):
    def test_retry_grows_with_bounded_jitter(self):
        for attempt in range(30):
            low, high = retry_delay(attempt, random_value=0), retry_delay(attempt, random_value=1)
            self.assertGreaterEqual(low, 0.125)
            self.assertLessEqual(high, 5)
            self.assertEqual(high, low * 2)
        self.assertGreater(retry_delay(2, random_value=0), retry_delay(0, random_value=0))

    def test_fault_applies_only_to_first_accepted_command_and_preserves_ack_order(self):
        fault = FaultPlan(ack_delay=2)
        ack = pb.CommandAck(command_id="first", accepted=True)
        self.assertEqual(fault.delivery_at(ack, 10), 12)
        self.assertEqual(fault.delivery_at(pb.CommandResult(command_id="first"), 11), 12)
        self.assertEqual(fault.delivery_at(pb.CommandAck(command_id="other", accepted=True), 11), 11)

    def test_drop_ack_and_result_are_explicit_independent_faults(self):
        fault = FaultPlan(drop_ack=True)
        self.assertIsNone(fault.delivery_at(pb.CommandAck(command_id="first", accepted=True), 1))
        self.assertEqual(fault.delivery_at(pb.CommandResult(command_id="first"), 2), 2)
        fault = FaultPlan(drop_result=True)
        self.assertEqual(fault.delivery_at(pb.CommandAck(command_id="first", accepted=True), 1), 1)
        self.assertIsNone(fault.delivery_at(pb.CommandResult(command_id="first"), 2))


class ReconnectTests(unittest.IsolatedAsyncioTestCase):
    async def test_new_session_resets_sequence_stops_old_motion_and_fault_runs_once(self):
        arrivals = asyncio.Queue()
        handlers = []
        connections = []

        async def handle(reader, writer):
            connections.append(writer)
            number = len(connections)
            try:
                registration = await read_frame(reader)
                self.assertEqual(registration.seq, 0)
                session = f"session-{number}"
                writer.write(frame(pb.Envelope(protocol_version=1, robot_id="RB-001", session_id=session, seq=1,
                    register_result=pb.RegisterResult(accepted=True, assigned_session_id=session,
                                                      heartbeat_interval_ms=50, heartbeat_timeout_ms=300))))
                await writer.drain()
                arrivals.put_nowait((session, reader, writer))
                gateway_seq = 1
                while True:
                    message = await read_frame(reader)
                    self.assertEqual(message.session_id, session)
                    if message.HasField("heartbeat"):
                        gateway_seq += 1
                        writer.write(frame(pb.Envelope(protocol_version=1, robot_id="RB-001", session_id=session,
                            seq=gateway_seq, heartbeat_reply=pb.HeartbeatReply(acknowledged_seq=message.seq))))
                        await writer.drain()
            except (asyncio.IncompleteReadError, OSError):
                pass
            finally:
                writer.close()
                with suppress(OSError):
                    await writer.wait_closed()

        server = await asyncio.start_server(lambda r, w: handlers.append(asyncio.create_task(handle(r, w))), "127.0.0.1", 0)
        robot = RobotClient("RB-001", "public-test-token", faults=FaultPlan(disconnect_after=0.15))
        task = asyncio.create_task(robot.run_forever(port=server.sockets[0].getsockname()[1]))
        try:
            first, _, first_writer = await asyncio.wait_for(arrivals.get(), 2)
            first_writer.write(frame(pb.Envelope(protocol_version=1, robot_id="RB-001", session_id=first,
                                     seq=2, command_request=command(duration=3000))))
            await first_writer.drain()
            second, _, _ = await asyncio.wait_for(arrivals.get(), 3)
            self.assertNotEqual(first, second)
            await asyncio.sleep(0.3)
            self.assertEqual(robot.connections, 2)
            self.assertIsNone(robot.model.motion)
            position = robot.model.x
            self.assertGreater(position, 0)
            await asyncio.sleep(0.15)
            self.assertEqual(robot.model.x, position)
            self.assertFalse(task.done())
        finally:
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)
            for writer in connections:
                writer.close()
            await asyncio.gather(*handlers, return_exceptions=True)
            server.close()
            await server.wait_closed()
