package org.simplejavamail.internal.socks.socks5server;

import org.simplejavamail.internal.socks.common.SocksException;
import org.simplejavamail.internal.socks.socks5server.msg.ServerReply;

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
