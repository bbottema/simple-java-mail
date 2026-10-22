package org.simplejavamail.internal.util;

import org.simplejavamail.MailException;
import org.simplejavamail.api.mailer.MailFailureKind;

import java.io.IOException;

/** A known I/O failure while configuration reads key material or a certificate. */
public final class KeyMaterialReadException extends MailException {

    private static final long serialVersionUID = 1L;

    public KeyMaterialReadException(final String message, final IOException cause) {
        super(MailFailureKind.PREPARATION, message, cause, null);
    }
}
