import asyncio
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import platform
import sys
import time
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "simulator"))


def main():
    directory = ROOT / "artifacts/simulator"
    directory.mkdir(parents=True, exist_ok=True)
    report = directory / "report.json"
    report.unlink(missing_ok=True)
    started = time.monotonic()
    suite = unittest.defaultTestLoader.discover(str(ROOT / "simulator/tests"), pattern="test_*.py")
    cases = []

    class Result(unittest.TextTestResult):
        def addSuccess(self, test):
            super().addSuccess(test)
            cases.append(test.id())

    result = unittest.TextTestRunner(verbosity=2, resultclass=Result).run(suite)
    if not result.wasSuccessful() or result.skipped or result.testsRun == 0:
        return 1
    from gateway_check import gateway_check
    integration = asyncio.run(gateway_check())
    from recovery_check import recovery_check
    recovery = asyncio.run(recovery_check())
    report.write_text(json.dumps({"status": "PASS", "recorded_at_utc": datetime.now(timezone.utc).isoformat(),
                     "duration_seconds": round(time.monotonic() - started, 3), "python": sys.version.split()[0],
                     "platform": platform.platform(), "logical_cpu_count": os.cpu_count(),
                     "unit_and_local_network_tests": result.testsRun, "cases": cases,
                     "integration": integration, "recovery": recovery}, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"PASS: {result.testsRun} Python tests + actual Java/WebSocket/three-robot/CLI integration. Report: {report}")
    return 0


if __name__ == "__main__":
    sys.exit(main())

