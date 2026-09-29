"""Run the checked-in SMTP matrix without changing the developer's Docker services or Maven configuration."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import shutil
import signal
import socket
import subprocess
import sys
import time
import uuid
import xml.etree.ElementTree as XML


TOOLS = Path(__file__).resolve().parent
ROOT = TOOLS.parents[1]
SERVERS = ("postfix", "exim")
EMBEDDED_TESTS = (
    "MailerSmtpIntegrationTest,MailerSocksIntegrationTest,EmailSerializationSmtpTest,ExactEmailSendingTest,"
    "SmtpSubmissionFaultBoundaryTest,SmtpProtocolConformanceTest,SmtpAuthenticationTlsCharacterizationTest,"
    "SmtpConnectionProbeTest,SmtpCapabilityProbeCharacterizationTest,SmtpEnvelopeIdTest,RecipientDsnSubmissionTest,SmtpRequireTlsTest,"
    "SmtpContentNegotiationCharacterizationTest,LegacySmtpContentSupportTest,SmtpMessageSizeTest,SmtpMessageSizeLifecycleTest,"
    "MailSubmissionPoolingTest,MailSendExecutionControlTest,MailSendQueueTest,MailSendObserverTest,MailSendObserverDispatchTest"
)


class InfrastructureFailure(RuntimeError):
    pass


class AssertionFailure(RuntimeError):
    pass


def checked_output(command, *, environment=None, timeout=60):
    completed = subprocess.run(command, cwd=ROOT, env=environment, capture_output=True, text=True,
                               encoding="utf-8", errors="replace", timeout=timeout)
    if completed.returncode:
        raise InfrastructureFailure(f"{Path(command[0]).name} failed ({completed.returncode}): {completed.stderr[-2000:]}")
    return completed.stdout.strip()


def stop_process_tree(process):
    if process.poll() is not None:
        return
    if os.name == "nt":
        subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], capture_output=True, timeout=30)
    else:
        os.killpg(process.pid, signal.SIGKILL)
    process.wait(timeout=30)


def execute_to_log(command, log, environment, timeout):
    print(f"Running {Path(command[0]).name}; log: {log}", flush=True)
    options = {"creationflags": subprocess.CREATE_NEW_PROCESS_GROUP} if os.name == "nt" else {"start_new_session": True}
    with log.open("w", encoding="utf-8") as output:
        process = subprocess.Popen(command, cwd=ROOT, env=environment, stdout=output, stderr=subprocess.STDOUT, **options)
        try:
            return process.wait(timeout=timeout)
        finally:
            stop_process_tree(process)


def wait_for_smtp(port, timeout=45):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            with socket.create_connection(("127.0.0.1", port), timeout=1) as connection:
                connection.settimeout(1)
                if connection.recv(1024).startswith(b"220"):
                    return
        except OSError:
            pass
        time.sleep(0.1)
    raise InfrastructureFailure(f"SMTP listener on allocated port {port} did not become ready")


def read_test_results(paths):
    results = []
    for path in sorted(paths):
        for case in XML.parse(path).getroot().iter("testcase"):
            status = "failed" if case.find("failure") is not None or case.find("error") is not None else "passed"
            if case.find("skipped") is not None:
                status = "skipped"
            result = {"class": case.get("classname"), "name": case.get("name"), "status": status,
                      "seconds": float(case.get("time", "0"))}
            if status == "failed":
                result["failureLocations"] = failure_source_locations(case)
            results.append(result)
    return results


def failure_source_locations(case):
    # Assertion messages can contain complete MIME or credentials. Only retain ordinary project stack-frame locations.
    locations = []
    for failure in (*case.findall("failure"), *case.findall("error")):
        for line in (failure.text or "").splitlines():
            match = re.fullmatch(r"\s*at ((?:org\.simplejavamail|testutil)\.[\w.$]+\([\w.$]+\.java:\d+\))\s*", line)
            if match and match[1] not in locations:
                locations.append(match[1])
                if len(locations) == 20:
                    return locations
    return locations


def write_sanitized_junit(results, destination):
    suite = XML.Element("testsuite", name="smtp-conformance", tests=str(len(results)),
                        failures=str(sum(result["status"] != "passed" for result in results)))
    for result in results:
        case = XML.SubElement(suite, "testcase", classname=result["class"], name=result["name"], time=str(result["seconds"]))
        if result["status"] != "passed":
            failure = XML.SubElement(case, "failure", message=f"Scenario {result['status']}; inspect the local run log")
            failure.text = "\n".join(result.get("failureLocations", []))
    XML.ElementTree(suite).write(destination, encoding="utf-8", xml_declaration=True)


def source_tree_digest():
    paths = checked_output(["git", "ls-files", "--cached", "--others", "--exclude-standard", "--",
                            "modules", "pom.xml", ".circleci", "tools/smtp-conformance"]).splitlines()
    digest = hashlib.sha256()
    for name in sorted(set(paths)):
        path = ROOT / name
        if path.is_file() and "__pycache__" not in path.parts:
            digest.update(name.encode("utf-8") + b"\0" + hashlib.sha256(path.read_bytes()).digest())
    return digest.hexdigest()


def source_provenance():
    return {
        "commit": checked_output(["git", "rev-parse", "HEAD"]),
        "dirty": bool(checked_output(["git", "status", "--porcelain"])),
        "sourceTreeSha256": source_tree_digest(),
        "configurationSha256": {str(path.relative_to(TOOLS)): hashlib.sha256(path.read_bytes()).hexdigest()
                                for path in sorted(TOOLS.rglob("*")) if path.is_file() and "__pycache__" not in path.parts},
    }


def require_scenarios(results, expected_classes):
    actual = {result["class"].rsplit(".", 1)[-1] for result in results}
    missing = set(expected_classes) - actual
    if missing:
        raise InfrastructureFailure("Missing scenario classes: " + ", ".join(sorted(missing)))
    if any(result["status"] != "passed" for result in results):
        raise AssertionFailure("A conformance scenario failed or was skipped")


def describe_artifact(path):
    return {"name": path.name, "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def read_test_runtime(reports):
    # The raw JUnit properties contain the entire JVM environment. Publish only this allowlist.
    for report in reports:
        properties = {item.get("name"): item.get("value") for item in XML.parse(report).getroot().findall("properties/property")}
        if "java.runtime.version" in properties:
            runtime = {name: properties[name] for name in ("java.runtime.version", "java.vendor", "os.name", "os.arch") if name in properties}
            paths = [Path(entry) for entry in properties.get("java.class.path", "").split(os.pathsep)]
            runtime["classpathJars"] = [describe_artifact(path) for path in paths if path.is_file() and path.suffix == ".jar"]
            return runtime
    raise InfrastructureFailure("JUnit did not report its actual runtime")


class ConformanceRun:
    def __init__(self, mode, test_jvm_image=None):
        self.mode = mode
        self.test_jvm_image = test_jvm_image
        run_id = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
        self.directory = ROOT / "target" / "smtp-conformance" / run_id
        self.evidence = self.directory / "evidence"
        self.environment = dict(os.environ, SJM_CONFORMANCE_RUN=self.directory.as_posix(), SJM_CONFORMANCE_ROOT=ROOT.as_posix())
        if test_jvm_image:
            self.environment["SJM_CONFORMANCE_JAVA_IMAGE"] = test_jvm_image
        self.compose = ["docker", "compose", "-f", str(TOOLS / "compose.yaml"), "-p", "sjm-conformance-" + run_id.lower()]
        self.started_compose = False
        self.results = []
        self.summary = {"schemaVersion": 1, "runId": run_id, "mode": mode, "status": "infrastructure-failure", "servers": {}}

    def prepare(self):
        for directory in (self.evidence, self.directory / "private", *(self.directory / "captures" / server for server in SERVERS)):
            directory.mkdir(parents=True)
        for server in SERVERS:
            (self.directory / "captures" / server).chmod(0o777)  # Per-run fixture delivery, never an application mailbox.
        self.summary["source"] = source_provenance()
        self.summary["host"] = {"system": platform.platform(), "python": platform.python_version()}
        self.maven = shutil.which("mvn")
        if not self.maven:
            raise InfrastructureFailure("Maven is not on PATH")
        self.summary["maven"] = checked_output([self.maven, "-version"])
        if self.test_jvm_image and "@sha256:" not in self.test_jvm_image:
            raise InfrastructureFailure("--test-jvm-image must identify an immutable image digest")

    def compose_output(self, *arguments, timeout=60):
        return checked_output(self.compose + list(arguments), environment=self.environment, timeout=timeout)

    def start_servers(self):
        self.summary["docker"] = checked_output(["docker", "version", "--format", "{{.Server.Version}}"])
        self.summary["compose"] = checked_output(["docker", "compose", "version", "--short"])
        self.started_compose = True
        if execute_to_log(self.compose + ["build"], self.directory / "images.log", self.environment, 1200):
            raise InfrastructureFailure("Container build failed; see images.log")
        self.compose_output("run", "--rm", "verifier", "python3", "/opt/conformance/verify_content.py", "generate", timeout=60)
        self.compose_output("up", "-d", *SERVERS, timeout=120)
        endpoints = []
        for server in SERVERS:
            for name, container_port in (("port", 2525), ("limited-port", 2526), ("smtps-port", 2465)):
                address = self.compose_output("port", server, str(container_port))
                port = int(address.rsplit(":", 1)[1])
                if port <= 0:
                    raise InfrastructureFailure(f"Docker did not publish {server}:{container_port} to a usable loopback port")
                endpoints.append(f"{server}.{name}={port}")
                if name != "smtps-port":
                    wait_for_smtp(port)
            container_id = self.compose_output("ps", "-q", server)
            self.summary["servers"][server] = {
                "imageId": checked_output(["docker", "inspect", "--format", "{{.Image}}", container_id]),
                "packages": self.compose_output("exec", "-T", server, "dpkg-query", "-W"),
            }
        if self.test_jvm_image:
            endpoints = [f"{server}.host={server}\n{server}.port=2525\n{server}.limited-port=2526\n{server}.smtps-port=2465" for server in SERVERS]
        (self.directory / "endpoints.properties").write_text("\n".join(endpoints) + "\n", encoding="utf-8")

    def compile_container_test_dependencies(self):
        classpath_file = self.directory / "test-classpath.txt"
        build = [self.maven, "-B", "-pl", "modules/simple-java-mail", "-am", "package", "dependency:build-classpath",
                 "-DskipTests", "-Dlicense.skip=true", "-Djacoco.skip=true", "-Dmaven.javadoc.skip=true",
                 f"-Dmdep.outputFile={classpath_file}", "-Dmdep.includeScope=test"]
        if execute_to_log(build, self.directory / "compile.log", self.environment, 1800):
            raise InfrastructureFailure("Could not compile the container-JVM matrix; see compile.log")
        return classpath_file

    def obtain_console_launcher(self):
        # Console Launcher is a test tool only; its version matches the project's current JUnit Platform.
        launcher_version = "1.14.4"
        launcher = f"junit-platform-console-standalone-{launcher_version}.jar"
        copy_launcher = [self.maven, "-B", "-N", "dependency:copy",
                         f"-Dartifact=org.junit.platform:junit-platform-console-standalone:{launcher_version}",
                         f"-DoutputDirectory={self.directory / 'private'}"]
        if execute_to_log(copy_launcher, self.directory / "launcher.log", self.environment, 300):
            raise InfrastructureFailure("Could not obtain the matching JUnit Console Launcher")
        self.summary["junitConsoleLauncher"] = launcher_version
        return launcher

    def copy_container_test_classpath(self, classpath_file):
        dependencies = self.directory / "private" / "classpath"
        dependencies.mkdir()
        paths = ["/checkout/modules/simple-java-mail/target/test-classes", "/checkout/modules/simple-java-mail/target/classes"]
        self.summary["containerTestDependencies"] = []
        for index, entry in enumerate(classpath_file.read_text().strip().split(os.pathsep)):
            jar = Path(entry)
            if not jar.is_file() or jar.suffix != ".jar":
                raise InfrastructureFailure(f"Expected a packaged test dependency: {jar.name}")
            destination = dependencies / f"{index:03}-{jar.name}"
            shutil.copy2(jar, destination)
            self.summary["containerTestDependencies"].append(describe_artifact(jar))
            paths.append("/run/private/classpath/" + destination.name)
        return ":".join(paths)

    def test_in_container_jvm(self, report_directory):
        """Run the same compiled suite off the host's TLS interception path; never replace its certificate checks."""
        classpath_file = self.compile_container_test_dependencies()
        launcher = self.obtain_console_launcher()
        classpath = self.copy_container_test_classpath(classpath_file)
        self.summary["testJvmImage"] = self.test_jvm_image
        self.compose_output("pull", "test-jvm", timeout=300)
        self.summary["testJvmImageId"] = checked_output(["docker", "image", "inspect", "--format", "{{.Id}}", self.test_jvm_image])
        command = self.compose + ["run", "--rm", "-T", "test-jvm", "java", "--add-opens", "java.base/java.lang=ALL-UNNAMED",
                                 "--add-opens", "java.base/java.util=ALL-UNNAMED", "-Dlog4j.configurationFile=log4j2-smtp-conformance.xml",
                                 "-jar", "/run/private/" + launcher, "execute", "--class-path", classpath,
                                 "--select-class", "org.simplejavamail.mailer.SmtpServerConformanceIT", "--fail-if-no-tests",
                                 "--disable-ansi-colors", "--details", "summary", "--reports-dir", "/run/" + report_directory.name]
        return execute_to_log(command, self.directory / "real.log", self.environment, 1800)

    def test(self, real_servers):
        report_directory = self.directory / ("real-results" if real_servers else "embedded-results")
        command = [self.maven, "-B", "-pl", "modules/simple-java-mail", "-am", "verify" if real_servers else "test",
                   "-Dlicense.skip=true", "-Djacoco.skip=true", "-Dmaven.javadoc.skip=true", "-Dspotbugs.skip=true",
                   "-Dsurefire.failIfNoSpecifiedTests=false"]
        if real_servers:
            command += ["-Psmtp-conformance", "-Dtest=SmtpProtocolConformanceTest", f"-Dsmtp.conformance.reportsDirectory={report_directory}"]
        else:
            command += [f"-Dtest={EMBEDDED_TESTS}", f"-Dsmtp.conformance.embeddedReports={report_directory}"]
        code = (self.test_in_container_jvm(report_directory) if real_servers and self.test_jvm_image else
                execute_to_log(command, self.directory / ("real.log" if real_servers else "embedded.log"), self.environment, 1800))
        reports = list(report_directory.glob("TEST-*.xml"))
        results = read_test_results(reports)
        self.results.extend(results)
        if not results:
            raise InfrastructureFailure("Maven produced no scenario results; see the local build log")
        self.summary["realTestRuntime" if real_servers else "embeddedTestRuntime"] = read_test_runtime(reports)
        require_scenarios(results, ["SmtpServerConformanceIT"] if real_servers else EMBEDDED_TESTS.split(","))
        if real_servers and len(results) != 24:
            raise InfrastructureFailure("The real-server matrix must report all 12 scenarios for both servers")
        if code:
            raise InfrastructureFailure("Maven failed outside the reported scenario assertions")

    def verify_content(self):
        self.compose_output("run", "--rm", "verifier", "python3", "/opt/conformance/verify_content.py", "verify", timeout=180)
        self.summary["contentVerification"] = json.loads((self.evidence / "content-verification.json").read_text(encoding="utf-8"))
        expected = {(server, scenario) for server in SERVERS
                    for scenario in ("dkim", "smime-signed", "smime-encrypted", "pgp-signed", "pgp-encrypted")}
        actual = [(item["server"], item["scenario"]) for item in self.summary["contentVerification"]["checks"]]
        if set(actual) != expected or len(actual) != len(expected):
            raise InfrastructureFailure("Independent verification did not report all ten unique protected-content cases")
        self.results.extend({"class": "independent-content." + item["server"], "name": item["scenario"],
                             "status": item["status"], "seconds": 0} for item in self.summary["contentVerification"]["checks"])
        if self.summary["contentVerification"]["status"] != "passed" or any(
                item["status"] != "passed" for item in self.summary["contentVerification"]["checks"]):
            raise AssertionFailure("Independent content verification failed")

    def cleanup(self):
        if self.started_compose:
            try:
                # Raw diagnostics stay local. Published evidence contains neither messages nor authentication exchanges.
                execute_to_log(self.compose + ["logs", "--no-color"], self.directory / "servers.log", self.environment, 30)
            finally:
                self.compose_output("down", "--volumes", "--remove-orphans", timeout=90)

    def publish_evidence(self):
        if self.summary["status"] == "infrastructure-failure":
            self.results.append({"class": "infrastructure", "name": "runner-and-cleanup", "status": "failed", "seconds": 0})
        self.summary["scenarios"] = self.results
        self.summary["finishedAt"] = datetime.now(timezone.utc).isoformat()
        (self.evidence / "summary.json").write_text(json.dumps(self.summary, indent=2), encoding="utf-8")
        write_sanitized_junit(self.results, self.evidence / "junit.xml")
        lines = ["# SMTP conformance", "", f"Result: **{self.summary['status']}**", f"Mode: {self.mode}",
                 f"Scenarios: {len(self.results)}", "", "SMTP acceptance is not a delivery guarantee.",
                 "Exact wire preservation is tested with scripted peers; MTAs may add transport headers.", "",
                 "| Scenario | Result |", "| --- | --- |"]
        lines += [f"| {result['class']}.{result['name']} | {result['status']} |" for result in self.results]
        (self.evidence / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
        print(f"Evidence: {self.evidence}", flush=True)

    def run(self):
        try:
            self.prepare()
            if self.mode in ("embedded", "all"):
                self.test(False)
            if self.mode in ("real", "all"):
                self.start_servers()
                self.test(True)
                self.verify_content()
            self.summary["status"] = "passed"
        except AssertionFailure as failure:
            self.summary["status"] = "assertion-failure"
            print(str(failure), file=sys.stderr)
        except (OSError, subprocess.SubprocessError, InfrastructureFailure, ValueError, KeyboardInterrupt) as failure:
            print(f"Infrastructure failure: {failure}", file=sys.stderr)
        finally:
            try:
                self.cleanup()
            except (OSError, subprocess.SubprocessError, InfrastructureFailure) as failure:
                self.summary["status"] = "infrastructure-failure"
                print(f"Cleanup failed: {failure}", file=sys.stderr)
            if self.evidence.exists():
                self.publish_evidence()
        return 0 if self.summary["status"] == "passed" else (1 if self.summary["status"] == "assertion-failure" else 2)


if __name__ == "__main__":
    def terminate(_signal, _frame):
        raise KeyboardInterrupt("Conformance run interrupted; cleaning up owned resources")

    signal.signal(signal.SIGTERM, terminate)
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("embedded", "real", "all"), default="all")
    parser.add_argument("--test-jvm-image", help="Optional digest-pinned Linux JDK image; run the real suite inside Docker, outside host TLS interception")
    arguments = parser.parse_args()
    raise SystemExit(ConformanceRun(arguments.mode, arguments.test_jvm_image).run())
