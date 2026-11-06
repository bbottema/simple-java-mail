package org.simplejavamail.internal.authenticatedsockssupport.common;

import org.simplejavamail.api.mailer.MailFailureKind;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;

@SuppressWarnings("serial")
public class SocksException extends org.simplejavamail.MailException {
	
	private static final String[] serverReplyMessage = {"General SOCKS server failure", "Connection not allowed by ruleset",
			"Network " + "unreachable", "Host unreachable", "Connection refused", "TTL expired", "Command not " + "supported",
			"Address type not supported"};

	public SocksException(final String msg) {
		this(MailFailureKind.CONNECTION, msg, null);
	}

	public SocksException(final String msg, final Exception e) {
		this(MailFailureKind.CONNECTION, msg, e);
	}

	public SocksException(final MailFailureKind kind, final String msg, final Exception e) {
		super(kind, msg, e, null);
	}

	private SocksException(final SocksException failure, final MailSubmissionReceipt receipt) {
		super(failure.getKind(), failure.getMessage(), failure.getCause(), receipt);
		setStackTrace(failure.getStackTrace());
		for (final Throwable suppressed : failure.getSuppressed()) { addSuppressed(suppressed); }
	}

	/** Internal receipt-aware construction stays with the authenticated-proxy producer. */
	public SocksException withSubmissionReceipt(final MailSubmissionReceipt receipt) {
		return getSubmissionReceipt().isEmpty() ? new SocksException(this, receipt) : this;
	}

	public static SocksException serverReplyException(final byte reply) {
		int code = reply & 0xff;
		if (code > 0x08) {
			return new SocksException("Unknown reply");
		}
		code = code - 1;
		return new SocksException(serverReplyMessage[code]);
	}

}
