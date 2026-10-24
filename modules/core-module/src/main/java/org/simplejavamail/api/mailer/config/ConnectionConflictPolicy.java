package org.simplejavamail.api.mailer.config;

import org.simplejavamail.api.mailer.MailerGenericBuilder;

/**
 * Advanced handling of conflicting declarations for the same supported connection setting during Mailer construction.
 * Configuration-source precedence is resolved first; ordinary builder editing does not create an assignment history to validate.
 * Configuration locks and mandatory transport requirements apply in both modes.
 *
 * @see MailerGenericBuilder#withConnectionConflictPolicy(ConnectionConflictPolicy)
 */
public enum ConnectionConflictPolicy {
    /** Reject different explicit declarations, including conflicts with visible supplied Session settings; accept equivalent parsed values. */
    REJECT_CONFLICTS,
    /** Allow deliberate customization, with explicit typed Java members taking precedence over their raw-property counterparts. */
    ALLOW_EXPLICIT_OVERRIDES
}
