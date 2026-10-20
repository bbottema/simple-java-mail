package org.simplejavamail.internal.authenticatedsockssupport.common;

import org.simplejavamail.api.mailer.MailFailureKind;

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

	public static SocksException serverReplyException(final byte reply) {
		int code = reply & 0xff;
		if (code > 0x08) {
			return new SocksException("Unknown reply");
		}
		code = code - 1;
		return new SocksException(serverReplyMessage[code]);
	}

}
