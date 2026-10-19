"""Deterministic planar motion. Callers supply monotonic seconds, never wall time."""
from collections import OrderedDict
from dataclasses import dataclass
import math
import re

from .protocol import pb

IDENTIFIER = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]{0,63}\Z")


@dataclass
class Record:
    request: bytes
    ack: pb.CommandAck
    result: pb.CommandResult | None = None
    expires_at: float = math.inf


@dataclass
class Motion:
    command: pb.CommandRequest
    end: float
    updated: float


class RobotModel:
    def __init__(self, x=0.0, y=0.0, yaw=0.0, battery=87.0,
                 history_limit=256, history_ttl=60.0):
        if (not all(math.isfinite(v) for v in (x, y, yaw, battery, history_ttl))
                or not 0 <= battery <= 100 or history_limit <= 0 or history_ttl <= 0):
            raise ValueError("Invalid robot model configuration")
        self.x, self.y, self.yaw, self.battery = x, y, yaw, battery
        self.history_limit, self.history_ttl = history_limit, history_ttl
        self.history: OrderedDict[str, Record] = OrderedDict()
        self.motion: Motion | None = None

    @staticmethod
    def valid(command: pb.CommandRequest) -> bool:
        if command.action == pb.ACTION_STOP:
            return not command.HasField("motion")
        if not command.HasField("motion"):
            return False
        motion = command.motion
        if (not math.isfinite(motion.speed_mps) or not math.isfinite(motion.angular_speed_rad_s)
                or not 0 < motion.duration_ms <= 3000):
            return False
        if command.action == pb.ACTION_MOVE_FORWARD:
            return 0 < motion.speed_mps <= 0.5 and motion.angular_speed_rad_s == 0
        return (command.action in (pb.ACTION_TURN_LEFT, pb.ACTION_TURN_RIGHT)
                and 0 < motion.angular_speed_rad_s <= 1 and motion.speed_mps == 0)

    def _finish(self, command_id, outcome, reason, now):
        record = self.history[command_id]
        record.result = pb.CommandResult(command_id=command_id, outcome=outcome, reason=reason)
        record.expires_at = now + self.history_ttl
        return record.result

    def advance(self, now: float) -> list:
        motion = self.motion
        if motion is None:
            return []
        until = min(now, motion.end)
        elapsed = max(0.0, until - motion.updated)
        command = motion.command
        if command.action == pb.ACTION_MOVE_FORWARD:
            distance = command.motion.speed_mps * elapsed
            self.x += math.cos(self.yaw) * distance
            self.y += math.sin(self.yaw) * distance
        else:
            sign = 1 if command.action == pb.ACTION_TURN_LEFT else -1
            self.yaw = (self.yaw + sign * command.motion.angular_speed_rad_s * elapsed + math.pi) % (2 * math.pi) - math.pi
        self.battery = max(0.0, self.battery - elapsed * 0.02)
        motion.updated = max(motion.updated, until)
        if now >= motion.end:
            self.motion = None
            return [self._finish(command.command_id, pb.COMMAND_OUTCOME_SUCCEEDED, "COMPLETED", now)]
        return []

    def handle(self, command: pb.CommandRequest, now: float) -> list:
        outputs = self.advance(now)
        if not IDENTIFIER.fullmatch(command.command_id):
            raise ValueError("Invalid command identifier")
        for key, record in list(self.history.items()):
            if record.expires_at <= now:
                del self.history[key]
        fingerprint = command.SerializeToString(deterministic=True)
        existing = self.history.get(command.command_id)
        if existing:
            if existing.request != fingerprint:
                return outputs + [pb.CommandAck(command_id=command.command_id, accepted=False,
                                                 reason="COMMAND_ID_CONFLICT")]
            return outputs + [existing.ack] + ([existing.result] if existing.result else [])
        # Never evict an unexpired record to make room: that would allow early re-execution.
        # At capacity STOP remains effective locally, but is rejected rather than promising deduplication.
        if len(self.history) >= self.history_limit:
            if command.action == pb.ACTION_STOP and self.valid(command):
                outputs += self.disconnect(now, "STOP_AT_CAPACITY")
            return outputs + [pb.CommandAck(command_id=command.command_id, accepted=False, reason="CAPACITY")]
        reason = ""
        if not self.valid(command):
            reason = "INVALID_PARAMETERS"
        elif command.action != pb.ACTION_STOP and self.motion:
            reason = "BUSY"
        ack = pb.CommandAck(command_id=command.command_id, accepted=not reason, reason=reason)
        self.history[command.command_id] = Record(fingerprint, ack)
        outputs.append(ack)
        if reason:
            self.history[command.command_id].expires_at = now + self.history_ttl
        elif command.action == pb.ACTION_STOP:
            outputs += self.disconnect(now, "STOPPED")
            outputs.append(self._finish(command.command_id, pb.COMMAND_OUTCOME_SUCCEEDED, "STOPPED", now))
        else:
            saved = pb.CommandRequest()
            saved.CopyFrom(command)
            self.motion = Motion(saved, now + command.motion.duration_ms / 1000, now)
        return outputs

    def disconnect(self, now, reason="DISCONNECTED") -> list:
        outputs = self.advance(now)
        if self.motion:
            command_id = self.motion.command.command_id
            self.motion = None
            outputs.append(self._finish(command_id, pb.COMMAND_OUTCOME_INTERRUPTED, reason, now))
        return outputs

    def telemetry(self) -> pb.Telemetry:
        return pb.Telemetry(battery_percent=math.floor(self.battery),
                            pose=pb.Pose(x_m=self.x, y_m=self.y, yaw_rad=self.yaw),
                            state=pb.ROBOT_STATE_MOVING if self.motion else pb.ROBOT_STATE_IDLE)

