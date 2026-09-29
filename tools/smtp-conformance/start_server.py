"""Start a real MTA with a deliberately isolated synthetic mailbox domain."""

import os
import subprocess
import sys


def start_postfix():
    subprocess.run(["saslpasswd2", "-p", "-c", "-u", "conformance.test", "fixture"],
                   input=b"fixture-password\n", check=True)
    os.chmod("/etc/sasldb2", 0o644)  # Only synthetic credentials; smtpd runs unprivileged.
    os.execvp("postfix", ["postfix", "start-fg"])


def start_exim():
    os.makedirs("/run/exim4", exist_ok=True)
    os.execvp("exim4", ["exim4", "-bdf", "-q10s", "-d-receive"])


if __name__ == "__main__":
    if sys.argv[1] == "postfix":
        start_postfix()
    else:
        start_exim()
