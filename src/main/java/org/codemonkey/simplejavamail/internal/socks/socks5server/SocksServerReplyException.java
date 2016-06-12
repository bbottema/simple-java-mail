package org.codemonkey.simplejavamail.internal.socks.socks5server;

import org.codemonkey.simplejavamail.internal.socks.common.SocksException;
import org.codemonkey.simplejavamail.internal.socks.socks5server.msg.ServerReply;

public class SocksServerReplyException extends SocksException {

	private final ServerReply serverReply;

	public SocksServerReplyException(ServerReply serverReply) {
		super(serverReply.getErrorMessage());
		this.serverReply = serverReply;
	}

	public ServerReply getServerReply() {
		return serverReply;
	}

}
