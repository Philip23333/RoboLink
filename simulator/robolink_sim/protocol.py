"""Shared generated messages and the TCP envelope boundary."""
import asyncio
from pathlib import Path
import struct
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "generated"))
import robolink_pb2 as pb

MAX_BODY = 65536


def frame(message: pb.Envelope) -> bytes:
    body = message.SerializeToString()
    if not 0 < len(body) <= MAX_BODY:
        raise ValueError("Invalid frame size")
    return struct.pack("!I", len(body)) + body


async def read_frame(reader: asyncio.StreamReader) -> pb.Envelope:
    size, = struct.unpack("!I", await reader.readexactly(4))
    if not 0 < size <= MAX_BODY:
        raise ValueError("Invalid frame size")
    message = pb.Envelope.FromString(await reader.readexactly(size))
    if message.protocol_version != 1 or message.WhichOneof("payload") is None:
        raise ValueError("Invalid protocol envelope")
    return message

