"""Check the capture server's evidence boundaries without sockets, certificates, files or Docker."""
import base64
import io
import unittest
from unittest.mock import Mock, patch

import relay
import server
from smtp_content import ContentLimitExceeded, IncompleteMessage, receive_message_data


class MessageContentTest(unittest.TestCase):
    def test_complete_data_keeps_mime_bytes_and_removes_only_dot_escaping(self):
        source = io.BytesIO(b"Subject: demo\r\n\r\n..leading dot\r\n.\r\nQUIT\r\n")
        self.assertEqual(b"Subject: demo\r\n\r\n.leading dot\r\n", receive_message_data(source))
        self.assertEqual(b"QUIT\r\n", source.readline())

    def test_eof_is_not_the_terminating_dot(self):
        with self.assertRaises(IncompleteMessage):
            receive_message_data(io.BytesIO(b"Subject: interrupted\r\n\r\npartial content"))

    def test_content_at_the_limit_is_allowed(self):
        self.assertEqual(b"abc\r\n", receive_message_data(io.BytesIO(b"abc\r\n.\r\n"), maximum_bytes=5))

    def test_content_over_the_limit_is_rejected(self):
        with self.assertRaises(ContentLimitExceeded):
            receive_message_data(io.BytesIO(b"abcd\r\n.\r\n"), maximum_bytes=5)

    def test_a_dot_in_a_long_physical_line_is_not_a_terminator(self):
        content = b"a" * 65536 + b".\r\nrest\r\n"
        self.assertEqual(content, receive_message_data(io.BytesIO(content + b".\r\n")))

    def test_a_double_dot_in_a_continuation_is_not_unescaped(self):
        content = b"a" * 65536 + b"..still-the-same-line\r\n"
        self.assertEqual(content, receive_message_data(io.BytesIO(content + b".\r\n")))


class MemoryConnection:
    """Only the stream handoff is needed here; real TLS is exercised by the Docker scenarios."""
    def __init__(self, incoming, outgoing):
        self.incoming = incoming
        self.outgoing = outgoing

    def settimeout(self, _seconds):
        pass

    def makefile(self, mode):
        return self.incoming if mode == "rb" else self.outgoing

    def close(self):
        self.closed = True


class CaptureBoundaryTest(unittest.TestCase):
    def setUp(self):
        server.MESSAGES.clear()
        relay.STATE.update(online=True, connections=0, active=0, authFailures=0, dataStarted=0, messages=[])
        relay.CONNECTIONS.clear()
        relay.CONNECTION_REVISION = 0
        relay.RELEASE.set()

    def run_exchange(self, fixture, commands, content_reader=None):
        handler = object.__new__(fixture.SMTP)
        handler.rfile = io.BytesIO(commands)
        handler.wfile = io.BytesIO()
        handler.connection = MemoryConnection(handler.rfile, handler.wfile)
        handler.connection_revision = relay.CONNECTION_REVISION
        context = Mock()
        context.wrap_socket.return_value = handler.connection
        with patch.object(fixture, "DATA") as directory, patch.object(relay.ssl, "SSLContext", return_value=context):
            if content_reader is None:
                self.invoke_handler(fixture, handler)
            else:
                with patch.object(fixture, "receive_message_data", content_reader):
                    self.invoke_handler(fixture, handler)
            saved = directory.__truediv__.return_value.write_bytes.call_count
        facts = server.MESSAGES if fixture is server else relay.STATE["messages"]
        return handler.wfile.getvalue(), saved, list(facts)

    @staticmethod
    def invoke_handler(fixture, handler):
        if fixture is server:
            handler.handle()
        else:
            handler.exchange(True)

    @staticmethod
    def authentication(fixture):
        if fixture is server:
            return b""
        credentials = base64.b64encode(("\0" + relay.USERNAME + "\0" + relay.PASSWORD).encode())
        return b"STARTTLS\r\nAUTH PLAIN " + credentials + b"\r\n"

    def submission(self, fixture, recipient="reader@example.test"):
        return (self.authentication(fixture) + f"MAIL FROM:<sender@{relay.DOMAIN}>\r\nRCPT TO:<{recipient}>\r\nDATA\r\n".encode())

    def test_complete_message_is_captured_once(self):
        for fixture in (server, relay):
            with self.subTest(fixture=fixture.__name__):
                response, saved, facts = self.run_exchange(fixture, self.submission(fixture) + b"Subject: demo\r\n\r\nbody\r\n.\r\nQUIT\r\n")
                self.assertEqual(1, saved)
                self.assertEqual(1, len(facts))
                self.assertIn(b"250 2.0.0 captured", response)

    def test_interrupted_message_is_neither_saved_nor_accepted(self):
        for fixture in (server, relay):
            with self.subTest(fixture=fixture.__name__):
                response, saved, facts = self.run_exchange(fixture, self.submission(fixture) + b"Subject: demo\r\n\r\npartial")
                self.assertEqual(0, saved)
                self.assertEqual([], facts)
                self.assertNotIn(b"250 2.0.0 captured", response)

    def test_deliberately_lost_reply_still_has_one_complete_capture(self):
        for fixture in (server, relay):
            with self.subTest(fixture=fixture.__name__):
                commands = self.submission(fixture, "lost@example.test") + b"Subject: demo\r\n\r\nbody\r\n.\r\n"
                response, saved, facts = self.run_exchange(fixture, commands)
                self.assertEqual(1, saved)
                self.assertEqual(1, len(facts))
                self.assertNotIn(b"250 2.0.0 captured", response)

    def test_size_refusal_cannot_create_submission_evidence(self):
        for fixture in (server, relay):
            with self.subTest(fixture=fixture.__name__):
                response, saved, facts = self.run_exchange(fixture, self.submission(fixture), Mock(side_effect=ContentLimitExceeded()))
                self.assertIn(b"552 fixture content limit", response)
                self.assertEqual(0, saved)
                self.assertEqual([], facts)

    def test_rejected_sender_does_not_leave_an_earlier_envelope_usable(self):
        commands = self.authentication(relay) + (f"MAIL FROM:<sender@{relay.DOMAIN}>\r\n"
                   "MAIL FROM:<sender@another-tenant.test>\r\nRCPT TO:<reader@example.test>\r\nDATA\r\n").encode()
        response, saved, facts = self.run_exchange(relay, commands)
        self.assertIn(b"553 sender belongs to another tenant", response)
        self.assertIn(b"503 present an authenticated, accepted sender first", response)
        self.assertEqual(0, saved)
        self.assertEqual([], facts)

    def test_reset_requires_another_sender(self):
        for fixture in (server, relay):
            with self.subTest(fixture=fixture.__name__):
                commands = self.authentication(fixture) + f"MAIL FROM:<sender@{relay.DOMAIN}>\r\nRSET\r\nRCPT TO:<reader@example.test>\r\nDATA\r\n".encode()
                response, saved, facts = self.run_exchange(fixture, commands)
                self.assertIn(b"503 present", response)
                self.assertEqual(0, saved)
                self.assertEqual([], facts)

    def test_successful_content_does_not_authorize_a_second_data_command(self):
        for fixture in (server, relay):
            with self.subTest(fixture=fixture.__name__):
                commands = self.submission(fixture) + b"Subject: demo\r\n\r\nbody\r\n.\r\nDATA\r\nQUIT\r\n"
                response, saved, facts = self.run_exchange(fixture, commands)
                self.assertEqual(1, saved)
                self.assertEqual(1, len(facts))
                self.assertIn(b"554", response)

    def test_tenant_match_uses_the_actual_domain(self):
        self.assertTrue(relay.belongs_to_fixture_tenant(f"MAIL FROM:<sender@{relay.DOMAIN.upper()}" + "> SIZE=12"))
        self.assertFalse(relay.belongs_to_fixture_tenant(f"MAIL FROM:<sender@{relay.DOMAIN}.another.test>"))
        self.assertFalse(relay.belongs_to_fixture_tenant(f"MAIL FROM:<{relay.DOMAIN}@another.test>"))

    def test_drop_during_starttls_also_retires_the_replacement_socket(self):
        handler = object.__new__(relay.SMTP)
        handler.rfile = io.BytesIO(b"STARTTLS\r\n")
        handler.wfile = io.BytesIO()
        handler.connection = MemoryConnection(handler.rfile, handler.wfile)
        replacement = MemoryConnection(handler.rfile, handler.wfile)

        def wrap_after_drop(_socket, **_options):
            with relay.LOCK:
                relay.CONNECTION_REVISION += 1
            return replacement

        context = Mock()
        context.wrap_socket.side_effect = wrap_after_drop
        with patch.object(relay.ssl, "SSLContext", return_value=context):
            handler.handle()
        self.assertTrue(replacement.closed)
        self.assertNotIn(replacement, relay.CONNECTIONS)


if __name__ == "__main__":
    unittest.main()
