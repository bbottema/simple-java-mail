package org.simplejavamail.email.internal;

import org.simplejavamail.MailException;
import org.simplejavamail.api.mailer.MailFailureKind;

/**
 * This exception is used to communicate errors during the creation of an email.
 */
@SuppressWarnings("serial")
class EmailException extends MailException {

    static final String NAME_MISSING_FOR_EMBEDDED_IMAGE = "No name given for embedded image nor passed inside the data source";
    static final String ERROR_READING_FROM_FILE = "Error reading from file: %s";
    static final String ERROR_RESOLVING_IMAGE_DATASOURCE = "Unable to dynamically resolve data source for the following image src: %s";
    static final String ERROR_PARSING_URL = "Unable to parse URL: %s";

    EmailException(@SuppressWarnings("SameParameterValue") final String message) {
        super(MailFailureKind.PREPARATION, message, null, null);
    }

    EmailException(@SuppressWarnings("SameParameterValue") final String message, final Exception cause) {
        super(MailFailureKind.PREPARATION, message, cause, null);
    }
}