package org.simplejavamail.mailer.internal;

import org.simplejavamail.MailException;
import org.simplejavamail.api.mailer.MailFailureKind;

/**
 * This exception is used to communicate errors during the sending of email.
 */
@org.jetbrains.annotations.ApiStatus.Internal
public class MailerException extends MailException {

	static final String MISSING_OAUTH2_TOKEN = "TransportStrategy is OAUTH2 but no fixed access token or OAuth2 access-token provider was configured";
	static final String CONFLICTING_OAUTH2_CREDENTIALS = "Configure either a fixed OAuth2 access token or an OAuth2 access-token provider, not both";
	static final String MULTIPLE_OAUTH2_TOKEN_PROVIDERS = "Multiple OAuth2 access-token providers were configured";
	static final String INVALID_OAUTH2_TOKEN_PROVIDER = "The configured OAuth2 access-token provider is not a Supplier";
	static final String OAUTH2_PROVIDER_REQUIRES_OAUTH2_STRATEGY = "An OAuth2 access-token provider requires TransportStrategy.SMTP_OAUTH2 or TransportStrategy.SMTPS_OAUTH2";
	static final String OAUTH2_TOKEN_PROVIDER_FAILED = "The OAuth2 access-token provider failed while obtaining an access token";
	static final String BLANK_OAUTH2_TOKEN_FROM_PROVIDER = "The OAuth2 access-token provider returned a blank access token";
	static final String ERROR_CONNECTING_SMTP_SERVER = "Was unable to connect to SMTP server";
	static final String MAILER_ERROR = "Failed to send email [%s]";
	static final String GENERIC_ERROR = "Failed to send email [%s], reason: Third party error";
	static final String INVALID_ENCODING = "Failed to send email [%s], reason: Encoding not accepted";
	static final String VALIDATION_ERROR = "Failed to validate email [%s]";
	static final String UNKNOWN_ERROR = "Failed to send email [%s], reason: Unknown error";

	MailerException(@SuppressWarnings("SameParameterValue") final String message) {
		this(MailFailureKind.CONFIGURATION, message, null);
	}

	MailerException(final String message, final Exception cause) {
		this(MailFailureKind.CONFIGURATION, message, cause);
	}

	/** Internal operational producer; the specified kind describes the failure, never its retry safety. */
	public MailerException(final MailFailureKind kind, final String message, final Throwable cause) {
		super(kind, message, cause, cause instanceof MailException ? ((MailException) cause).getSubmissionReceipt().orElse(null) : null);
	}
}
