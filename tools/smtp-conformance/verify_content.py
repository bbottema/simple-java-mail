"""Independent content checks. Runs without network access or real credentials."""

import base64
from email.parser import BytesParser
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as XML


BODY = "Fixture body: café, Καλημέρα, 東京.".encode("utf-8")


def generate_fixtures():
    fixture = Path("/fixture")
    subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "7",
                    "-subj", "/CN=localhost/emailAddress=sender@conformance.test",
                    "-addext", "subjectAltName=DNS:localhost,DNS:postfix,DNS:exim,IP:127.0.0.1",
                    "-addext", "extendedKeyUsage=serverAuth,emailProtection",
                    "-keyout", str(fixture / "private-key.pem"), "-out", str(fixture / "certificate.pem")],
                   check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    subprocess.run(["openssl", "pkcs12", "-export", "-name", "fixture", "-passout", "pass:fixture-password",
                    "-inkey", str(fixture / "private-key.pem"), "-in", str(fixture / "certificate.pem"),
                    "-out", str(fixture / "identity.p12")], check=True)
    subprocess.run(["openssl", "pkey", "-in", str(fixture / "private-key.pem"), "-pubout",
                    "-out", str(fixture / "public-key.pem")], check=True)
    for path in fixture.iterdir():
        path.chmod(0o644)  # These generated test identities must be readable by both unprivileged MTAs.


def command(arguments, content=None):
    return subprocess.run(arguments, input=content, capture_output=True, timeout=30)


def assert_decoded_body(content):
    message = BytesParser().parsebytes(content)
    bodies = [part.get_payload(decode=True) for part in message.walk() if part.get_content_type() == "text/plain"]
    assert any(BODY in body for body in bodies if body), "Decoded fixture body was not preserved"


def signed_entity(content):
    """Read the signed entity verbatim, without reserializing MIME headers or transfer encoding."""
    message = BytesParser().parsebytes(content)
    boundary = b"--" + message.get_boundary().encode("ascii")
    entity = content.split(boundary + b"\r\n", 1)[1].split(b"\r\n" + boundary, 1)[0]
    return entity, message.get_payload(1).get_payload(decode=True)


def tamper_entity(entity):
    header, body = entity.split(b"\r\n\r\n", 1)
    # Change signed bytes, but keep the MIME structure intact so verification, not parsing, must reject it.
    return header + b"\r\n\r\nX" + body[1:]


def verify_dkim(content, fixture):
    import dkim
    public_key = command(["openssl", "pkey", "-pubin", "-in", str(fixture / "public-key.pem"), "-outform", "DER"])
    assert public_key.returncode == 0
    record = b"v=DKIM1; k=rsa; p=" + base64.b64encode(public_key.stdout)

    def fixture_dns(name, **_):
        assert name.rstrip(b".") == b"fixture._domainkey.supersecret-testing-domain.com", "Unexpected DKIM DNS query"
        return record

    assert dkim.verify(content, dnsfunc=fixture_dns), "DKIM signature failed"
    assert not dkim.verify(content + b"\r\nChanged body\r\n", dnsfunc=fixture_dns), "DKIM accepted tampered content"
    assert_decoded_body(content)


def verify_smime(content, fixture, encrypted):
    arguments = ["openssl", "cms", "-decrypt" if encrypted else "-verify", "-inform", "SMIME"]
    arguments += (["-recip", str(fixture / "certificate.pem"), "-inkey", str(fixture / "private-key.pem")]
                  if encrypted else ["-CAfile", str(fixture / "certificate.pem")])
    result = command(arguments, content)
    assert result.returncode == 0, "OpenSSL could not verify/decrypt the fixture"
    assert_decoded_body(result.stdout)
    if encrypted:
        # Encryption alone is not a signature. The negative control uses the wrong identity, not a tamper-proof claim.
        wrong_key = command(["openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048"])
        assert wrong_key.returncode == 0
        with tempfile.NamedTemporaryFile() as key:
            key.write(wrong_key.stdout)
            key.flush()
            assert command(arguments[:-1] + [key.name], content).returncode != 0, "S/MIME decrypted with an unrelated key"
    else:
        entity, _ = signed_entity(content)
        assert command(arguments, content.replace(entity, tamper_entity(entity), 1)).returncode != 0, "S/MIME accepted tampered content"


def pgp_command(home, *arguments, content=None):
    return command(["gpg", "--homedir", str(home), "--batch", "--no-auto-key-retrieve", "--pinentry-mode", "loopback",
                    "--passphrase", "openpgpjs-fixture-passphrase", "--status-fd", "2", *arguments], content)


def verify_pgp(content, home, encrypted, fingerprint):
    if encrypted:
        ciphertext = BytesParser().parsebytes(content).get_payload(1).get_payload(decode=True)
        result = pgp_command(home, "--decrypt", content=ciphertext)
        assert result.returncode == 0, "GnuPG could not decrypt the fixture"
        assert b"[GNUPG:] DECRYPTION_OKAY" in result.stderr
        # The API signs a MIME entity, then encrypts that multipart/signed message. Its detached signature is inside the plaintext.
        assert BytesParser().parsebytes(result.stdout).get_content_type() == "multipart/signed"
        verify_pgp(result.stdout, home, False, fingerprint)
        # Damage the authenticated encrypted packet, not just its armor headers.
        packet = command(["gpg", "--batch", "--dearmor"], ciphertext)
        assert packet.returncode == 0
        changed = bytearray(packet.stdout)
        changed[-10] ^= 1
        assert pgp_command(home, "--decrypt", content=bytes(changed)).returncode != 0, "OpenPGP accepted tampered ciphertext"
    else:
        entity, signature = signed_entity(content)
        with tempfile.TemporaryDirectory() as directory:
            signed = Path(directory) / "signed.mime"
            detached = Path(directory) / "signature.asc"
            signed.write_bytes(entity)
            detached.write_bytes(signature)
            result = pgp_command(home, "--verify", str(detached), str(signed))
            assert result.returncode == 0 and b"[GNUPG:] VALIDSIG " + fingerprint in result.stderr, "OpenPGP signature failed"
            assert_decoded_body(entity)
            signed.write_bytes(tamper_entity(entity))
            assert pgp_command(home, "--verify", str(detached), str(signed)).returncode != 0, "OpenPGP accepted tampered content"


def captured_messages(server):
    messages = {}
    for manifest in (Path("/captures") / server).glob("*.xml"):
        facts = {entry.get("key"): entry.text or "" for entry in XML.parse(manifest).getroot().findall("entry")}
        content = manifest.with_name(facts["file"]).read_bytes()
        assert hashlib.sha256(content).hexdigest() == facts["sha256"], "Capture hash changed"
        messages.setdefault(facts["messageId"], []).append(content)
    return messages


def verify_all():
    fixture = Path("/fixture")
    results = []
    with tempfile.TemporaryDirectory() as directory:
        home = Path(directory)
        home.chmod(0o700)
        imported = pgp_command(home, "--import", "/openpgp/private-key.asc")
        assert imported.returncode == 0
        listed = pgp_command(home, "--with-colons", "--fingerprint", "--list-keys")
        fingerprint = next(line.split(b":")[9] for line in listed.stdout.splitlines() if line.startswith(b"fpr:"))
        for server in ("postfix", "exim"):
            messages = captured_messages(server)
            for scenario in ("dkim", "smime-signed", "smime-encrypted", "pgp-signed", "pgp-encrypted"):
                try:
                    deliveries = messages.get(f"<{server}-{scenario}@conformance.test>", [])
                    assert len(deliveries) == 1, "Expected exactly one protected fixture delivery"
                    content = deliveries[0]
                    if scenario == "dkim":
                        verify_dkim(content, fixture)
                    elif scenario.startswith("smime"):
                        verify_smime(content, fixture, scenario.endswith("encrypted"))
                    else:
                        verify_pgp(content, home, scenario.endswith("encrypted"), fingerprint)
                    results.append({"server": server, "scenario": scenario, "status": "passed", "negativeControl": "rejected"})
                except (AssertionError, ValueError, IndexError, subprocess.SubprocessError) as failure:
                    results.append({"server": server, "scenario": scenario, "status": "failed", "reason": str(failure)})
        command(["gpgconf", "--homedir", str(home), "--kill", "all"])
    report = {"status": "passed" if all(item["status"] == "passed" for item in results) else "failed", "checks": results,
              "verifiers": {"openssl": command(["openssl", "version"]).stdout.decode().strip(),
                            "gnupg": command(["gpg", "--version"]).stdout.decode().splitlines()[0],
                            "dkimpy": command(["dpkg-query", "-W", "-f=${Version}", "python3-dkim"]).stdout.decode()}}
    Path("/evidence/content-verification.json").write_text(json.dumps(report, indent=2), encoding="utf-8")


if __name__ == "__main__":
    if sys.argv[1] == "generate":
        generate_fixtures()
    elif sys.argv[1] == "verify":
        verify_all()
    else:
        raise SystemExit("Unknown verification command")
