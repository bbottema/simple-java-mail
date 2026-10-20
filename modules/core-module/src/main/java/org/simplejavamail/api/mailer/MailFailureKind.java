package org.simplejavamail.api.mailer;

import org.simplejavamail.MailException;

/**
 * Describes a library-owned failure, independently of SMTP acceptance and retry safety.
 * Inspect {@link MailException#getSubmissionReceipt()} and its retry guidance before resending. A missing receipt or a kind
 * such as {@link #TIMEOUT} does not prove that the email was unsent, and SMTP acceptance is not final mailbox delivery.
 */
public enum MailFailureKind {
    /** Required configuration, optional support or provider capabilities are missing or incompatible. */
    CONFIGURATION,
    /** The message or its resolved envelope failed completeness, address, injection or policy checks. */
    VALIDATION,
    /** The prepared message exceeds a configured local maximum. SMTP SIZE refusal remains a submission failure with its receipt. */
    MESSAGE_SIZE,
    /** MIME rendering, content access, encoding or cryptographic preparation could not complete. */
    PREPARATION,
    /** Execution, connection-pool or sending-allowance capacity could not be obtained. */
    CAPACITY,
    /** A usable connection could not be established, including authentication or TLS verification failure. */
    CONNECTION,
    /** The sending provider reported failed, partial or uncertain submission. Local provider compatibility checks can also prevent submission. */
    SUBMISSION,
    /** The configured total-send deadline expired. This does not undo server acceptance. */
    TIMEOUT,
    /** A cancellation request ended the operation. This does not recall an accepted email. */
    CANCELLED,
    /** Owned connection or operation resources could not be released or shut down normally. */
    CLEANUP,
    /** Reading or converting a supplied message representation failed outside sending. */
    CONVERSION,
    /** No more specific kind was supplied, including application-defined subclasses using the legacy protected constructors. */
    OTHER
}
