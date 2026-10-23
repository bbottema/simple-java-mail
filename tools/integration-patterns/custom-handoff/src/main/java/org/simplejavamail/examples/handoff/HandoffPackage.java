package org.simplejavamail.examples.handoff;

import java.util.List;

/**
 * Give the receiving application prepared bytes plus the information that cannot safely be inferred from visible mail headers.
 * Recipients include hidden envelope recipients; the application key and checksum help recognize an identical repeated handoff.
 * This is the example gateway's wire format, not serialization of an SJM object.
 */
record HandoffPackage(String id, String sender, List<String> recipients, byte[] mime, String sha256) {
}
