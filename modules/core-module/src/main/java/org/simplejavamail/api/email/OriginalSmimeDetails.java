package org.simplejavamail.api.email;

/** Serialization-only names for discarded protection fields in supported older Email snapshots. No runtime API or model. */
final class OriginalSmimeDetails {
    private OriginalSmimeDetails() { }
    enum SmimeMode { PLAIN, SIGNED, ENCRYPTED, SIGNED_ENCRYPTED, UNKNOWN }
    enum VerificationStatus { NOT_SIGNED, VALID, INVALID, ERROR, UNKNOWN }
    enum DecryptionStatus { NOT_ENCRYPTED, DECRYPTED, KEY_MISSING, FAILED, UNKNOWN }
}
