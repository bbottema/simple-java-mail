"""Give each demo a controllable TLS relay. Capture complete messages, but never deliver them to real mailboxes."""
import base64
import email
import email.policy
import hashlib
import http.server
import json
import os
import pathlib
import re
import socketserver
import socket
import ssl
import threading
import urllib.parse
import uuid

from smtp_content import ContentLimitExceeded, IncompleteMessage, receive_message_data

LOCK = threading.Lock()
CONNECTIONS = set()
CONNECTION_REVISION = 0
RELEASE = threading.Event()
RELEASE.set()
STATE = {"online": True, "connections": 0, "active": 0, "authFailures": 0, "dataStarted": 0, "messages": []}
PASSWORD = os.environ.get("FIXTURE_PASSWORD", "local-demo-only")
USERNAME = os.environ.get("FIXTURE_USER", "platform")
DOMAIN = os.environ.get("FIXTURE_DOMAIN", "example.test")
DATA = pathlib.Path("/data/messages")


class SMTP(socketserver.StreamRequestHandler):
    def handle(self):
        with LOCK:
            STATE["connections"] += 1
            STATE["active"] += 1
            CONNECTIONS.add(self.connection)
            online = STATE["online"]
            self.connection_revision = CONNECTION_REVISION
        try:
            self.exchange(online)
        except (OSError, ssl.SSLError):
            pass  # Disconnects and cancellation are deliberately exercised; never log protocol buffers.
        finally:
            with LOCK:
                STATE["active"] -= 1
                CONNECTIONS.discard(self.connection)

    def exchange(self, online):
        if not online:
            self.reply("421 4.3.2 synthetic relay is temporarily unavailable")
            return
        self.connection.settimeout(20)
        self.reply("220 local synthetic TLS relay")
        secure, authenticated = False, False
        recipients, sender, envelope_id = [], "", ""
        while line := self.rfile.readline(4096):
            command = line.decode("utf-8", errors="replace").rstrip("\r\n")
            verb = command.split(" ", 1)[0].upper()
            if verb in ("EHLO", "HELO"):
                self.wfile.write(b"250-local.example.test\r\n250-STARTTLS\r\n250-AUTH PLAIN\r\n250-DSN\r\n250-SIZE 10485760\r\n250-8BITMIME\r\n250 SMTPUTF8\r\n")
                self.wfile.flush()
            elif verb == "STARTTLS":
                self.reply("220 ready for TLS")
                context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
                context.minimum_version = ssl.TLSVersion.TLSv1_2
                context.load_cert_chain("/certificates/server.pem")
                old_socket = self.connection
                self.connection = context.wrap_socket(self.connection, server_side=True)
                with LOCK:
                    CONNECTIONS.discard(old_socket)
                    abandoned = self.connection_revision != CONNECTION_REVISION
                    if not abandoned:
                        CONNECTIONS.add(self.connection)
                if abandoned:
                    # A drop request can arrive while TLS replaces the old socket. The new socket inherits that cancellation.
                    self.connection.close()
                    return
                self.rfile = self.connection.makefile("rb")
                self.wfile = self.connection.makefile("wb")
                secure, authenticated = True, False
                recipients, sender, envelope_id = [], "", ""
            elif verb == "AUTH":
                if not secure:
                    self.reply("530 use STARTTLS first")
                    continue
                encoded = command.split(" ", 2)[-1]
                if encoded.upper() == "PLAIN":
                    self.reply("334 ")
                    encoded = self.rfile.readline(4096).strip().decode("ascii")
                try:
                    fields = base64.b64decode(encoded, validate=True).decode().split("\0")
                    with LOCK:
                        authenticated = fields[-2:] == [USERNAME, PASSWORD]
                except (ValueError, UnicodeError):
                    authenticated = False
                if not authenticated:
                    with LOCK:
                        STATE["authFailures"] += 1
                self.reply("235 authenticated" if authenticated else "535 5.7.8 synthetic credential rejected")
            elif verb == "MAIL":
                # A rejected new envelope must not leave an earlier accepted sender usable on this connection.
                recipients, sender, envelope_id = [], "", ""
                if not authenticated:
                    self.reply("530 authenticate first")
                    continue
                if not belongs_to_fixture_tenant(command):
                    self.reply("553 sender belongs to another tenant")
                    continue
                sender = command
                found = re.search(r"\bENVID=([A-Za-z0-9-]+)", command, re.I)
                envelope_id = found.group(1) if found else str(uuid.uuid4())
                self.reply("250 sender accepted")
            elif verb == "RCPT":
                if not authenticated or not sender:
                    self.reply("503 present an authenticated, accepted sender first")
                elif "reject@example.test" in command.lower():
                    self.reply("550 5.1.1 synthetic recipient rejected")
                else:
                    recipients.append(command)
                    self.reply("250 recipient accepted")
            elif verb == "DATA":
                if not sender or not recipients or not authenticated:
                    self.reply("554 no authenticated envelope")
                    continue
                with LOCK:
                    STATE["dataStarted"] += 1
                if not RELEASE.wait(15):
                    self.reply("451 fixture barrier expired")
                    return
                self.reply("354 send content")
                try:
                    body = receive_message_data(self.rfile)
                except IncompleteMessage:
                    return
                except ContentLimitExceeded:
                    self.reply("552 fixture content limit")
                    return
                capture = str(uuid.uuid4())
                (DATA / f"{capture}.eml").write_bytes(body)
                parsed = email.message_from_bytes(body, policy=email.policy.default)
                with LOCK:
                    STATE["messages"].append({"capture": capture, "envelopeId": envelope_id,
                        "sender": sender, "recipients": recipients[:], "messageId": parsed.get("Message-ID"),
                        "bytes": len(body), "sha256": hashlib.sha256(body).hexdigest()})
                if any("lost@example.test" in recipient for recipient in recipients):
                    return
                recipients, sender, envelope_id = [], "", ""
                self.reply("250 2.0.0 captured locally; not delivered")
            elif verb in ("RSET", "NOOP"):
                if verb == "RSET":
                    recipients, sender, envelope_id = [], "", ""
                self.reply("250 reset")
            elif verb == "QUIT":
                self.reply("221 goodbye")
                return
            else:
                self.reply("502 unsupported fixture command")

    def reply(self, text):
        self.wfile.write(text.encode("ascii") + b"\r\n")
        self.wfile.flush()


def belongs_to_fixture_tenant(command):
    """Accept only the demo tenant's exact domain, not a substring inside some other address.

    This deliberately handles the fixture's simple synthetic mailboxes, not the full SMTP address grammar.
    Production mailbox parsing belongs to the mail library, not to this local fault-injection server.
    """
    sender = re.fullmatch(r"MAIL\s+FROM:\s*<([^<>]+)>(?:\s+.*)?", command, re.IGNORECASE)
    return sender is not None and sender.group(1).rpartition("@")[2].lower() == DOMAIN.lower()


class HTTP(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        path = urllib.parse.urlparse(self.path).path
        with LOCK:
            snapshot = {**STATE, "messages": list(STATE["messages"]), "blocked": not RELEASE.is_set()}
        if path in ("/health", "/stats"):
            return self.respond(snapshot)
        if path.startswith("/messages/"):
            message_id = urllib.parse.unquote(path.removeprefix("/messages/"))
            matching = [item for item in snapshot["messages"] if message_id in (item["messageId"], item["envelopeId"])]
            if matching:
                return self.respond((DATA / (matching[-1]["capture"] + ".eml")).read_bytes(), "message/rfc822")
        self.send_error(404)

    def do_POST(self):
        global PASSWORD, CONNECTION_REVISION
        if self.path != "/control":
            return self.send_error(404)
        changes = json.loads(self.rfile.read(min(int(self.headers.get("Content-Length", 0)), 4096)))
        with LOCK:
            if "online" in changes:
                STATE["online"] = bool(changes["online"])
            if "password" in changes:
                PASSWORD = str(changes["password"])
            closing = list(CONNECTIONS) if changes.get("dropConnections") else []
            if changes.get("dropConnections"):
                CONNECTION_REVISION += 1
        for connection in closing:
            try:
                connection.shutdown(socket.SHUT_RDWR)
                connection.close()
            except OSError:
                pass
        if "blocked" in changes:
            RELEASE.clear() if changes["blocked"] else RELEASE.set()
        self.respond({"changed": True})

    def respond(self, result, content_type="application/json"):
        payload = result if isinstance(result, bytes) else json.dumps(result).encode()
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, *_):
        pass


if __name__ == "__main__":
    DATA.mkdir(parents=True, exist_ok=True)
    smtp = socketserver.ThreadingTCPServer(("0.0.0.0", 587), SMTP)
    smtp.daemon_threads = True
    threading.Thread(target=smtp.serve_forever, daemon=True).start()
    http.server.ThreadingHTTPServer(("0.0.0.0", 8025), HTTP).serve_forever()
