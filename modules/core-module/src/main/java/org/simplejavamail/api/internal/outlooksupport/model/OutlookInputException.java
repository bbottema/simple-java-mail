package org.simplejavamail.api.internal.outlooksupport.model;

import org.jetbrains.annotations.ApiStatus;
import org.simplejavamail.MailException;
import org.simplejavamail.api.mailer.MailFailureKind;

/** A reader-owned Outlook input failure, recognizable without depending on the optional reader implementation. */
@ApiStatus.Internal
@SuppressWarnings("serial")
public final class OutlookInputException extends MailException {

	public OutlookInputException(final Exception cause) {
		super(MailFailureKind.CONVERSION, "Unable to parse Outlook message", cause, null);
	}
}
