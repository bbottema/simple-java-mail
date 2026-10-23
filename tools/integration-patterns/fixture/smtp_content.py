"""Read the content of a local SMTP submission; an interrupted upload is not an accepted message."""

MAX_CONTENT_BYTES = 10 * 1024 * 1024


class IncompleteMessage(Exception):
    """The client disconnected before sending DATA's terminating dot."""


class ContentLimitExceeded(Exception):
    """The synthetic message exceeded the fixture's advertised size allowance."""


def receive_message_data(stream, maximum_bytes=MAX_CONTENT_BYTES):
    """Return only a complete message, removing SMTP's extra leading dot without changing its MIME bytes.

    The terminating dot is the acceptance boundary for these capture fixtures. EOF must stay distinguishable
    from that boundary, otherwise a cancelled or interrupted upload could become false submission evidence.
    """
    content = bytearray()
    # Read a whole physical line or enough to reject it. A smaller chunk could make a continuation dot
    # look like the terminating line. The extra two bytes also cover one escaped leading dot.
    while line := stream.readline(maximum_bytes + 2):
        if line == b".\r\n":
            return bytes(content)
        content.extend(line[1:] if line.startswith(b"..") else line)
        if len(content) > maximum_bytes:
            raise ContentLimitExceeded()
    raise IncompleteMessage()
