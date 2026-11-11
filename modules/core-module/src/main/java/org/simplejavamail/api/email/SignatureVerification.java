package org.simplejavamail.api.email;

/** Verification of one recognized signature. UNVERIFIED includes deferred or unavailable verification, not established invalidity. */
public enum SignatureVerification {
    VERIFIED, INVALID, UNVERIFIED
}
