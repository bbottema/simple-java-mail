package org.simplejavamail.api.mailer;

/**
 * Describes what an email send was doing when a progress snapshot was captured. Use these stages to explain a wait or update a dashboard,
 * not to reconstruct a fixed sequence: stages can repeat, some providers report less detail, and fast changes may be combined before you see them.
 * Leave room for additional enum values when displaying progress, since future providers and library updates may report more detail.
 */
public enum MailSendProgressStage {
    /** Waiting for asynchronous execution, including a wait for the executor to accept the work. */
    WAITING_FOR_EXECUTION,
    /** Applying the selected Mailer's defaults and overrides, and validating the email. */
    PREPARING,
    /** Waiting until the selected destination's sending limits allow this email to proceed. */
    WAITING_FOR_RATE_LIMIT,
    /** Obtaining a usable connection, including pool waiting, connection setup or proxy startup where needed. */
    ACQUIRING_CONNECTION,
    /** Preparing the MIME message and applying message protection. Some attachment reads may happen later, during submission. */
    PREPARING_MIME,
    /** Calling the provider or CustomMailer to submit the email, including its initial checks when no more detailed stage is available. */
    SUBMITTING,
    /** Writing the message to the provider's content stream. The server has not necessarily received or accepted it yet. */
    UPLOADING,
    /** Waiting for the final SMTP response after uploading content. Acceptance is not yet known. */
    WAITING_FOR_RESPONSE,
    /** Waiting before retrying this email after determining that the previous submission can safely be retried. */
    WAITING_TO_RETRY,
    /** Releasing, invalidating or closing this attempt's connection resources. */
    CLEANING_UP,
    /** Sending has ended, whether successfully or not. Read the final outcome to find out what happened. */
    FINISHED,
    /** Waiting for a newly connected server's initial greeting; reused connections do not repeat this stage. */
    WAITING_FOR_GREETING,
    /** Exchanging EHLO or HELO, including a repeated EHLO after STARTTLS. */
    NEGOTIATING_CAPABILITIES,
    /** Upgrading the connection to TLS, including the handshake and certificate verification, when the provider can observe that work. */
    NEGOTIATING_TLS,
    /** Performing SMTP authentication; no credentials or authentication payloads are exposed. */
    AUTHENTICATING,
    /** Presenting the SMTP envelope sender and processing the server's response. */
    SUBMITTING_SENDER,
    /** Presenting SMTP envelope recipients and processing their responses, not delivering to their mailboxes. */
    SUBMITTING_RECIPIENTS,
    /** Waiting for permission to upload after DATA. BDAT does not have this permission exchange. */
    WAITING_FOR_DATA_PERMISSION
}
