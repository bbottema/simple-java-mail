package org.simplejavamail.mailer.internal.socks.socks5server.msg;

import org.simplejavamail.mailer.internal.socks.common.SocksException;

@SuppressWarnings("serial")
public class SocksServerReplyException extends SocksException {

	private final ServerReply serverReply;

	public SocksServerReplyException(final ServerReply serverReply) {
		super(serverReply.getErrorMessage());
		this.serverReply = serverReply;
	}

	public ServerReply getServerReply() {
		return serverReply;
	}

}
