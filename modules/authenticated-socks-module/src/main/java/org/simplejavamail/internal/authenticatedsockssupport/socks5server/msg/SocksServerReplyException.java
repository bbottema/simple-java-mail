package org.simplejavamail.internal.authenticatedsockssupport.socks5server.msg;

import org.simplejavamail.internal.authenticatedsockssupport.common.SocksException;

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
