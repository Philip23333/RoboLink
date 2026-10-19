"""Keep concise, credential-free browser acceptance evidence."""
from datetime import datetime, timezone
import json
from pathlib import Path
import sys

root = Path(__file__).resolve().parents[1]
report = json.loads((root / "artifacts/browser/results.json").read_text(encoding="utf-8"))
stats = report["stats"]
if stats["unexpected"] or stats["skipped"] or stats["flaky"] or not stats["expected"]:
    raise SystemExit("Browser acceptance did not fully pass")


def cases(suites):
    for suite in suites:
        for spec in suite.get("specs", []):
            yield spec["title"]
        yield from cases(suite.get("suites", []))


path = root / f"docs/evidence/step-{sys.argv[1]}-browser-report.json"
path.write_text(json.dumps({"status": "PASS", "recorded_at_utc": datetime.now(timezone.utc).isoformat(),
    "browser": "Playwright Chromium", "stats": stats, "cases": list(cases(report["suites"])),
    "scope": "Real localhost Java gateway, Python devices and Vue browser; not hardware or performance test"},
    ensure_ascii=False, indent=2), encoding="utf-8")
print(f"PASS: {stats['expected']} browser cases; {path.name}")
