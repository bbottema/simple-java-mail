package org.simplejavamail.api.email;

/** Serialization-only enum names used by discarded fields of earlier unreleased Email snapshots. */
final class OriginalOpenPgpDetails {
    private OriginalOpenPgpDetails() { }
    enum OpenPgpMode { PLAIN, SIGNED, ENCRYPTED, SIGNED_ENCRYPTED, UNKNOWN }
    enum SignatureStatus { NOT_PRESENT, VALID, INVALID, KEY_MISSING, ERROR, UNKNOWN }
    enum DecryptionStatus { NOT_ENCRYPTED, DECRYPTED, KEY_MISSING, FAILED, UNKNOWN }
}
