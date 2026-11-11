package org.simplejavamail.api.email;

/** Processing of one recognized encrypted component. Deferred work is not a fabricated processing failure. */
public enum DecryptionStatus {
    DECRYPTED, DEFERRED, UNAVAILABLE, FAILED
}
