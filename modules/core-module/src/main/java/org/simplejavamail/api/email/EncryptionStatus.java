package org.simplejavamail.api.email;

/** Incoming encryption coverage of selected content, independently of the bytes sent when it is reused. */
public enum EncryptionStatus {
    NOT_ENCRYPTED, DECRYPTED, ENCRYPTED, UNKNOWN
}
