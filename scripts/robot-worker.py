"""Launch the base interpreter directly so the owned PID is the actual robot PID.

Windows venv redirectors can otherwise add an extra launcher process. Load this
project's pinned virtualenv dependencies explicitly with the matching interpreter.
"""
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(root / ".venv/Lib/site-packages"))
sys.path.insert(0, str(root / "simulator"))
from robolink_sim.worker import main

sys.exit(main())
