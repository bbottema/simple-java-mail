"""Record one complete branch verification without uploading mail fixtures."""

import datetime
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import time
import xml.etree.ElementTree as ET


def main():
    evidence = Path("target/ci-verification")
    evidence.mkdir(parents=True, exist_ok=True)
    command = [
        "mvn", "--batch-mode", "--no-transfer-progress", "verify",
        "-Ppublish-cli", "-Dlicense.skip=true",
    ]
    environment = subprocess.run(
        ["mvn", "--version"], capture_output=True, text=True, check=True
    ).stdout.strip()
    started = datetime.datetime.now(datetime.timezone.utc)
    clock = time.monotonic()
    total_line = None
    with (evidence / "maven.log").open("w", encoding="utf-8") as log:
        process = subprocess.Popen(
            command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace",
        )
        for line in process.stdout:
            print(line, end="", flush=True)
            log.write(line)
            if "Total time:" in line:
                total_line = re.sub(r"\x1b\[[0-9;]*m", "", line).strip()
        exit_code = process.wait()
    elapsed = time.monotonic() - clock
    counts = dict(tests=0, failures=0, errors=0, skipped=0)
    report_count = 0
    for report in Path("modules").glob("*/target/surefire-reports/TEST-*.xml"):
        suite = ET.parse(report).getroot()
        for name in counts:
            counts[name] += int(suite.get(name, "0"))
        report_count += 1
    result = {
        "commit": os.environ.get("CIRCLE_SHA1"),
        "jobUrl": os.environ.get("CIRCLE_BUILD_URL"),
        "command": " ".join(command),
        "platform": platform.platform(),
        "availableCpuCount": os.cpu_count(),
        "resourceClass": "large",
        "mavenEnvironment": environment,
        "startedAtUtc": started.isoformat(),
        "finishedAtUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "wallSeconds": round(elapsed, 3),
        "mavenTotalTime": total_line,
        "exitCode": exit_code,
        "reportFiles": report_count,
        "reportedCases": counts.pop("tests"),
        **counts,
    }
    (evidence / "result.json").write_text(
        json.dumps(result, indent=2) + "\n", encoding="utf-8"
    )
    print("ROOT_VERIFICATION_RESULT " + json.dumps(result), flush=True)
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
