"""Record explicitly requested branch checks without uploading mail fixtures."""

import datetime
import hashlib
import json
import os
import fnmatch
from pathlib import Path
import platform
import re
import shutil
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET


def main():
    evidence = Path("target/ci-verification")
    evidence.mkdir(parents=True, exist_ok=True)
    scope = sys.argv[1] if len(sys.argv) > 1 else "full"
    if scope not in ("full", "failure-control"):
        raise ValueError("Unknown verification scope: " + scope)
    # The four-CPU VM runs one isolated test JVM per CPU; intra-test concurrency is unchanged.
    command = ["mvn", "--batch-mode", "--no-transfer-progress", "-Dtest.forkCount=4", "-Dlicense.skip=true"]
    selected = []
    if scope == "full":
        command += ["verify", "-Ppublish-cli"]
    else:
        # Fixed diagnostic selection from the failed CI run, not an automatic selection framework.
        selected = [
            "MailSendExecutionControlTest", "FailoverDirectClasspathTest", "AutomaticImageCidLocaleTest",
            "InheritedOAuth2TokenTest", "RecipientDsnSubmissionTest", "SmtpConnectionProbePortTest",
            "SmtpConnectionProbeTest", "SmtpMessageSizeTest", "SmtpProtocolConformanceTest",
            "OpenPgpHeaderSeparatorTest", "OpenPgpIndependentSmimeAttachmentTest", "OpenPgpTransferEncodingTest",
            "SmtpRecipientRepliesTest", "FailoverScopeContractTest", "LateCancellationCleanupFailureTest",
            "MailSendRetrySchedulingTest", "MailerConnectionTestLifecycleTest", "MailerLifecycleTest",
            "SmtpContentNegotiationCharacterizationTest", "SmtpMessageSizeLifecycleTest",
            "ExactEmailProtectionInspectionTest", "PreEncodedReplyResourceTest",
            "FailoverPoolContractTest",
            "MailSendRateLimiterTest",
        ]
        command += ["-pl", "modules/simple-java-mail", "-am", "test",
                    "-Dtest=" + ",".join(selected), "-Dsurefire.failIfNoSpecifiedTests=false"]
    environment = subprocess.run(
        ["mvn", "--version"], capture_output=True, text=True, check=True
    ).stdout.strip()
    # IPv6 socket tests need a real runner capability, not a skipped test group.
    with tempfile.TemporaryDirectory(prefix="sjm-ipv6-check-") as directory:
        probe = Path(directory) / "VerifyIpv6.java"
        probe.write_text(
            "import java.net.*;\n"
            "class VerifyIpv6 { public static void main(String[] args) throws Exception {\n"
            "  try (ServerSocket socket = new ServerSocket()) {\n"
            "    socket.bind(new InetSocketAddress(InetAddress.getByName(\"::1\"), 0));\n"
            "    System.out.println(\"Java IPv6 loopback is available\");\n"
            "  }\n"
            "}}\n", encoding="utf-8",
        )
        subprocess.run(["java", str(probe)], check=True)
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
    suite_counts = []
    executed = set()
    failed_classes = set()
    for report in Path("modules").glob("*/target/surefire-reports/TEST-*.xml"):
        suite = ET.parse(report).getroot()
        executed.add(suite.get("name", "").rsplit(".", 1)[-1])
        if int(suite.get("failures", "0")) + int(suite.get("errors", "0")):
            failed_classes.add(suite.get("name"))
        for name in counts:
            counts[name] += int(suite.get(name, "0"))
        suite_counts.append({"module": report.parts[1], "class": suite.get("name"),
                             **{name: int(suite.get(name, "0")) for name in counts}})
        report_count += 1
    missing = sorted(set(selected) - executed)
    if missing:
        exit_code = exit_code or 1
    pom = ET.parse("pom.xml").getroot()
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    filters = [node.text.strip().replace(".", "/") for node in pom.findall(
        ".//m:plugin[m:artifactId='jacoco-maven-plugin']/m:executions/"
        "m:execution[m:id='prepare-agent']/m:configuration/m:includes/m:include", ns)]
    outside_coverage_scope = []
    if filters:
        for module in Path("modules").iterdir():
            classes = module / "target/classes"
            for compiled in classes.rglob("*.class"):
                name = compiled.relative_to(classes).as_posix()[:-6]
                if name == "module-info" or name.startswith("META-INF/"):
                    continue
                if not any(fnmatch.fnmatchcase(name, pattern) for pattern in filters):
                    outside_coverage_scope.append(str(compiled))
        if outside_coverage_scope:
            exit_code = exit_code or 1
    snapshot_artifacts = []
    repository = Path.home() / ".m2/repository"
    for artifact in repository.glob("org/eclipse/angus/angus-mail/*SNAPSHOT/*.jar"):
        snapshot_artifacts.append({"path": str(artifact.relative_to(repository)),
                                   "sha256": hashlib.sha256(artifact.read_bytes()).hexdigest()})
    coverage = evidence / "coverage"
    coverage.mkdir(exist_ok=True)
    coverage_artifacts = []
    for pattern in ("*/target/jacoco*.exec", "*/target/site/**/jacoco.xml"):
        for source in Path("modules").glob(pattern):
            destination = coverage / (source.parts[1] + "-" + "-".join(source.parts[3:]))
            shutil.copyfile(source, destination)
            coverage_artifacts.append(str(destination.relative_to(evidence)))
    result = {
        "commit": os.environ.get("CIRCLE_SHA1"),
        "jobUrl": os.environ.get("CIRCLE_BUILD_URL"),
        "command": " ".join(command),
        "scope": scope,
        "platform": platform.platform(),
        "availableCpuCount": os.cpu_count(),
        "resourceClass": "large",
        "mavenEnvironment": environment,
        "snapshotArtifacts": snapshot_artifacts,
        "startedAtUtc": started.isoformat(),
        "finishedAtUtc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "wallSeconds": round(elapsed, 3),
        "mavenTotalTime": total_line,
        "exitCode": exit_code,
        "missingSelectedClasses": missing,
        "classesOutsideCoverageScope": sorted(outside_coverage_scope),
        "failedClasses": sorted(failed_classes),
        "reportFiles": report_count,
        "suiteCounts": sorted(suite_counts, key=lambda row: (row["module"], row["class"])),
        "coverageArtifacts": sorted(coverage_artifacts),
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
