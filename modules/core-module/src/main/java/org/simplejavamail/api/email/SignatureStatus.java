package org.simplejavamail.api.email;

/** Aggregate signature facts for one selected value. MIXED retains differing outcomes or known partial coverage; UNKNOWN is unestablished scope. */
public enum SignatureStatus {
    VERIFIED, INVALID, UNVERIFIED, UNSIGNED, MIXED, UNKNOWN
}
