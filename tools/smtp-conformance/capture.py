"""Capture one recipient delivery, not one SMTP transaction. No log scraping is involved."""

import email.parser
import hashlib
import os
from pathlib import Path
import sys
import uuid
import xml.etree.ElementTree as XML


def capture_delivery(server, queue_id, sender, recipient, content, destination, connection=""):
    capture_id = uuid.uuid4().hex
    message = email.parser.BytesHeaderParser().parsebytes(content)
    metadata = {
        "schemaVersion": 1,
        "server": server,
        "queueId": queue_id,
        "connection": connection,
        "sender": sender,
        "recipient": recipient,
        "messageId": message.get("Message-ID"),
        "file": capture_id + ".eml",
        "sha256": hashlib.sha256(content).hexdigest(),
    }
    (destination / metadata["file"]).write_bytes(content)
    pending = destination / (capture_id + ".pending")
    properties = XML.Element("properties")
    for key, value in metadata.items():
        XML.SubElement(properties, "entry", key=key).text = str(value or "")
    document = b'<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE properties SYSTEM "http://java.sun.com/dtd/properties.dtd">\n'
    pending.write_bytes(document + XML.tostring(properties, encoding="utf-8"))
    # The manifest is the completion marker: readers never observe an unfinished EML file.
    pending.replace(destination / (capture_id + ".xml"))


if __name__ == "__main__":
    server_name = sys.argv[1]
    if server_name == "postfix":
        queue, envelope_sender, envelope_recipient = sys.argv[2:5]
        peer_address, peer_port = sys.argv[5:7]
    else:
        queue, envelope_sender, envelope_recipient = (os.environ[key] for key in ("MESSAGE_ID", "SENDER", "RECIPIENT"))
        peer_address, peer_port = sys.argv[2:4]
    capture_delivery(server_name, queue, envelope_sender, envelope_recipient, sys.stdin.buffer.read(), Path("/captures"),
                     peer_address + ":" + peer_port)
