import math
import unittest

from robolink_sim.model import RobotModel
from robolink_sim.protocol import pb


def command(identifier="move-1", action=pb.ACTION_MOVE_FORWARD, duration=1000, speed=0.2, angular=0.0):
    result = pb.CommandRequest(command_id=identifier, action=action)
    if action != pb.ACTION_STOP:
        result.motion.CopyFrom(pb.MotionParameters(speed_mps=speed, angular_speed_rad_s=angular,
                                                   duration_ms=duration))
    return result


class ModelTests(unittest.TestCase):
    def test_elapsed_time_and_late_tick_cannot_overshoot(self):
        robot = RobotModel()
        self.assertTrue(robot.handle(command(), 10)[0].accepted)
        robot.advance(10.25)
        self.assertAlmostEqual(robot.x, 0.05)
        result = robot.advance(20)
        self.assertAlmostEqual(robot.x, 0.2)
        self.assertEqual(result[0].outcome, pb.COMMAND_OUTCOME_SUCCEEDED)
        self.assertIsNone(robot.motion)

    def test_motion_uses_heading_and_turn_sign(self):
        robot = RobotModel(yaw=math.pi / 2)
        robot.handle(command(), 0)
        robot.advance(1)
        self.assertAlmostEqual(robot.x, 0)
        self.assertAlmostEqual(robot.y, 0.2)
        robot.handle(command("left", pb.ACTION_TURN_LEFT, speed=0, angular=0.5), 1)
        robot.advance(2)
        self.assertAlmostEqual(robot.yaw, math.pi / 2 + 0.5)
        robot.handle(command("right", pb.ACTION_TURN_RIGHT, speed=0, angular=0.5), 2)
        robot.advance(3)
        self.assertAlmostEqual(robot.yaw, math.pi / 2)

    def test_devices_have_independent_state(self):
        first, second = RobotModel(), RobotModel(x=2, y=1)
        first.handle(command(), 0)
        first.advance(1)
        self.assertAlmostEqual(first.x, 0.2)
        self.assertEqual((second.x, second.y), (2, 1))
        self.assertEqual(len(second.history), 0)

    def test_duplicate_running_and_completed_do_not_restart(self):
        robot = RobotModel()
        request = command()
        robot.handle(request, 0)
        replay = robot.handle(request, 0.5)
        self.assertEqual(len(replay), 1)
        self.assertTrue(replay[0].accepted)
        robot.advance(1)
        replay = robot.handle(request, 2)
        self.assertEqual(len(replay), 2)
        self.assertEqual(replay[1].outcome, pb.COMMAND_OUTCOME_SUCCEEDED)
        robot.advance(3)
        self.assertAlmostEqual(robot.x, 0.2)

    def test_same_id_with_different_parameters_rejected(self):
        robot = RobotModel()
        robot.handle(command(), 0)
        ack = robot.handle(command(speed=0.3), 0.1)[0]
        self.assertFalse(ack.accepted)
        self.assertEqual(ack.reason, "COMMAND_ID_CONFLICT")
        robot.advance(1)
        self.assertAlmostEqual(robot.x, 0.2)

    def test_busy_command_rejected_and_remembered(self):
        robot = RobotModel()
        robot.handle(command(), 0)
        rejected = command("other")
        self.assertEqual(robot.handle(rejected, 0.1)[0].reason, "BUSY")
        robot.advance(1)
        self.assertEqual(robot.handle(rejected, 2)[0].reason, "BUSY")
        self.assertIsNone(robot.motion)

    def test_stop_ack_interrupt_and_stop_result_are_distinct(self):
        robot = RobotModel()
        robot.handle(command(), 0)
        outputs = robot.handle(command("stop", pb.ACTION_STOP), 0.4)
        self.assertTrue(outputs[0].accepted)
        self.assertEqual(outputs[1].command_id, "move-1")
        self.assertEqual(outputs[1].outcome, pb.COMMAND_OUTCOME_INTERRUPTED)
        self.assertEqual(outputs[2].command_id, "stop")
        self.assertEqual(outputs[2].outcome, pb.COMMAND_OUTCOME_SUCCEEDED)
        robot.advance(10)
        self.assertAlmostEqual(robot.x, 0.08)

    def test_disconnect_freezes_pose_and_does_not_resume(self):
        robot = RobotModel()
        robot.handle(command(), 0)
        result = robot.disconnect(0.3)
        self.assertEqual(result[0].reason, "DISCONNECTED")
        robot.advance(100)
        self.assertAlmostEqual(robot.x, 0.06)
        self.assertEqual(robot.telemetry().state, pb.ROBOT_STATE_IDLE)

    def test_stop_when_idle_succeeds(self):
        outputs = RobotModel().handle(command("stop", pb.ACTION_STOP), 0)
        self.assertTrue(outputs[0].accepted)
        self.assertEqual(outputs[1].outcome, pb.COMMAND_OUTCOME_SUCCEEDED)

    def test_parameter_boundaries_rechecked_at_device(self):
        invalid = [command(duration=0), command(duration=3001), command(speed=0),
                   command(speed=0.51), command(speed=float("nan")), command(speed=float("inf")),
                   command(angular=0.1), command(action=pb.ACTION_UNSPECIFIED),
                   command(action=pb.ACTION_TURN_LEFT, speed=0, angular=1.01),
                   command(action=pb.ACTION_TURN_RIGHT, speed=0, angular=-0.5)]
        missing = pb.CommandRequest(command_id="missing", action=pb.ACTION_MOVE_FORWARD)
        stop_with_motion = command("stop", pb.ACTION_STOP)
        stop_with_motion.motion.SetInParent()
        for request in invalid + [missing, stop_with_motion]:
            with self.subTest(request=request):
                robot = RobotModel()
                ack = robot.handle(request, 0)[0]
                self.assertFalse(ack.accepted)
                self.assertEqual(ack.reason, "INVALID_PARAMETERS")
                self.assertIsNone(robot.motion)

    def test_history_capacity_does_not_evict_unexpired_records(self):
        robot = RobotModel(history_limit=2, history_ttl=2)
        robot.handle(command("first", pb.ACTION_STOP), 0)
        robot.handle(command("second", pb.ACTION_STOP), 0)
        self.assertEqual(robot.handle(command("third"), 1)[0].reason, "CAPACITY")
        self.assertEqual(len(robot.history), 2)
        self.assertTrue(robot.handle(command("third"), 3)[0].accepted)
        self.assertEqual(len(robot.history), 1)

    def test_capacity_stop_still_stops_locally_without_untracked_execution(self):
        robot = RobotModel(history_limit=1)
        robot.handle(command(), 0)
        outputs = robot.handle(command("stop", pb.ACTION_STOP), 0.5)
        self.assertEqual(outputs[-1].reason, "CAPACITY")
        self.assertEqual(outputs[0].outcome, pb.COMMAND_OUTCOME_INTERRUPTED)
        self.assertIsNone(robot.motion)

    def test_running_record_cannot_expire_before_completion(self):
        robot = RobotModel(history_ttl=0.1)
        robot.handle(command(duration=3000), 0)
        self.assertTrue(robot.handle(command(duration=3000), 1)[0].accepted)
        self.assertEqual(robot.motion.end, 3)

    def test_battery_and_yaw_are_bounded(self):
        robot = RobotModel(battery=0.001, yaw=3)
        robot.handle(command("left", pb.ACTION_TURN_LEFT, duration=3000, speed=0, angular=1), 0)
        robot.advance(3)
        self.assertEqual(robot.telemetry().battery_percent, 0)
        self.assertTrue(-math.pi <= robot.yaw < math.pi)

