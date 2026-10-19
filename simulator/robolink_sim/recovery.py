"""Bounded retry delays and explicit application-level demo faults."""
from dataclasses import dataclass
import math
import random


def retry_delay(attempt: int, base=0.25, maximum=5.0, random_value=None):
    if attempt < 0 or not 0 < base <= maximum:
        raise ValueError("Invalid retry policy")
    ceiling = min(maximum, base * 2 ** min(attempt, 20))
    # Equal jitter: never hot-loop; never exceed maximum.
    return ceiling * (0.5 + 0.5 * (random.random() if random_value is None else random_value))


@dataclass
class FaultPlan:
    disconnect_after: float = 0
    ack_delay: float = 0
    drop_ack: bool = False
    drop_result: bool = False
    disconnected: bool = False
    command_id: str = ""
    release_at: float = 0

    def __post_init__(self):
        if any(not math.isfinite(value) or not 0 <= value <= 60 for value in (self.disconnect_after, self.ack_delay)):
            raise ValueError("Fault times must be in 0..60 seconds")

    def delivery_at(self, payload, now):
        from .protocol import pb
        if isinstance(payload, pb.CommandAck) and payload.accepted and not self.command_id:
            self.command_id = payload.command_id
            self.release_at = now + self.ack_delay
        if payload.command_id != self.command_id:
            return now
        if isinstance(payload, pb.CommandAck) and self.drop_ack:
            return None
        if isinstance(payload, pb.CommandResult) and self.drop_result:
            return None
        return max(now, self.release_at)
