"""Capture complete demo messages locally, or deliberately lose their final reply. Never forward Internet mail."""
import hashlib
import http.server
import json
import pathlib
import re
import socketserver
import threading
import urllib.parse
import uuid

from smtp_content import ContentLimitExceeded, IncompleteMessage, receive_message_data

DATA = pathlib.Path("/data/messages")
LOCK = threading.Lock()
MESSAGES = []


class SMTP(socketserver.StreamRequestHandler):
    def handle(self):
        self.reply("220 smtp.example.test local synthetic fixture")
        recipients, envelope_id = [], None
        sender_accepted = False
        while line := self.rfile.readline(4096):
            command = line.decode("utf-8", errors="replace").rstrip("\r\n")
            verb = command.split(" ", 1)[0].upper()
            if verb in ("EHLO", "HELO"):
                self.wfile.write(b"250-smtp.example.test\r\n250-DSN\r\n250-8BITMIME\r\n250-SIZE 10485760\r\n250 SMTPUTF8\r\n")
            elif verb == "MAIL":
                recipients = []
                sender_accepted = True
                found = re.search(r"\bENVID=([A-Za-z0-9-]+)", command, re.I)
                envelope_id = found.group(1) if found else str(uuid.uuid4())
                self.reply("250 sender accepted")
            elif verb == "RCPT":
                if not sender_accepted:
                    self.reply("503 present an accepted sender first")
                elif "reject@example.test" in command.lower():
                    self.reply("550 5.1.1 unknown local synthetic recipient")
                else:
                    recipients.append(command)
                    self.reply("250 recipient accepted")
            elif verb == "DATA":
                if not sender_accepted or not recipients:
                    self.reply("554 no accepted recipients")
                    continue
                self.reply("354 send content")
                try:
                    body = receive_message_data(self.rfile)
                except IncompleteMessage:
                    return  # An unfinished upload is neither captured nor counted as accepted.
                except ContentLimitExceeded:
                    self.reply("552 fixture content limit")
                    return
                capture = str(uuid.uuid4())
                (DATA / f"{capture}.eml").write_bytes(body)
                with LOCK:
                    MESSAGES.append({"capture": capture, "envelopeId": envelope_id, "recipients": recipients[:],
                                     "bytes": len(body), "sha256": hashlib.sha256(body).hexdigest()})
                if any("lost@example.test" in recipient.lower() for recipient in recipients):
                    return  # Content accepted by fixture, final reply deliberately lost: no automatic replay is safe.
                sender_accepted = False
                recipients, envelope_id = [], None
                self.reply("250 2.0.0 captured locally; not delivered to a mailbox")
            elif verb in ("RSET", "NOOP"):
                if verb == "RSET":
                    sender_accepted = False
                    recipients, envelope_id = [], None
                self.reply("250 reset")
            elif verb == "QUIT":
                self.reply("221 closing")
                return
            else:
                self.reply("502 command not implemented by fixture")

    def reply(self, text):
        self.wfile.write(text.encode("ascii") + b"\r\n")
        self.wfile.flush()


class HTTP(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        route = urllib.parse.urlparse(self.path)
        if route.path in ("/health", "/stats"):
            with LOCK:
                payload = json.dumps({"messages": list(MESSAGES), "count": len(MESSAGES)}).encode()
            return self.respond(payload, "application/json")
        if route.path.startswith("/messages/"):
            envelope_id = route.path.removeprefix("/messages/")
            with LOCK:
                matching = [message for message in MESSAGES if message["envelopeId"] == envelope_id]
            if matching:
                return self.respond((DATA / f"{matching[-1]['capture']}.eml").read_bytes(), "message/rfc822")
        if route.path == "/synthetic-report":
            query = urllib.parse.parse_qs(route.query)
            envelope_id = query.get("envelopeId", ["missing-correlation"])[0]
            action = query.get("action", ["failed"])[0]
            if not re.fullmatch(r"[A-Za-z0-9-]{1,100}", envelope_id) or action not in ("failed", "delayed", "delivered"):
                return self.send_error(400, "Invalid synthetic report input")
            code = {"failed": "5.1.1", "delayed": "4.4.1", "delivered": "2.0.0"}[action]
            report = ("From: fixture-postmaster@example.test\r\nTo: sender@example.test\r\nMIME-Version: 1.0\r\n"
                      "Subject: Synthetic delivery notification, not Internet delivery evidence\r\n"
                      "Content-Type: multipart/report; report-type=delivery-status; boundary=dsn\r\n\r\n"
                      "--dsn\r\nContent-Type: text/plain\r\n\r\nThis notification was explicitly injected by the local scenario.\r\n"
                      "--dsn\r\nContent-Type: message/delivery-status\r\n\r\nReporting-MTA: dns; smtp.example.test\r\n"
                      f"Original-Envelope-Id: {envelope_id}\r\n\r\nFinal-Recipient: rfc822; reader@example.test\r\n"
                      f"Action: {action}\r\nStatus: {code}\r\n\r\n--dsn--\r\n")
            return self.respond(report.encode(), "message/rfc822")
        self.send_error(404)

    def respond(self, payload, content_type):
        self.send_response(200)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, format, *args):
        pass  # No mail addresses/content in automatic fixture logs.


if __name__ == "__main__":
    DATA.mkdir(parents=True, exist_ok=True)
    smtp = socketserver.ThreadingTCPServer(("0.0.0.0", 2525), SMTP)
    smtp.daemon_threads = True
    threading.Thread(target=smtp.serve_forever, daemon=True).start()
    print("Local SMTP fixture ready; it has no onward-delivery implementation", flush=True)
    http.server.ThreadingHTTPServer(("0.0.0.0", 8025), HTTP).serve_forever()
