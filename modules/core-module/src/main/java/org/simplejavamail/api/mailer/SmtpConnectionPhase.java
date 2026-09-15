package org.simplejavamail.api.mailer;

/** The connection step that failed; this is unrelated to a message's submission or delivery status. */
public enum SmtpConnectionPhase {
    /** Selecting/configuring the probe, before opening its connection. */
    SETUP,
    /** Opening a socket, including proxy negotiation and implicit TLS. */
    CONNECT,
    /** Reading the SMTP greeting. */
    GREETING,
    /** Discovering capabilities with EHLO, or falling back to HELO. */
    EHLO,
    /** Requesting STARTTLS or completing its handshake and configured verification. */
    STARTTLS,
    /** Resolving credentials or authenticating; successful connection alone does not prove this succeeded. */
    AUTHENTICATION,
    /** Closing the dedicated connection after collecting the other facts. */
    CLOSE
}
