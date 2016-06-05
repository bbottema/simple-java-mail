package org.codemonkey.simplejavamail.internal.socksrelayserver;

import org.codemonkey.simplejavamail.internal.socksrelayserver.msg.ServerReply;

import java.io.IOException;

public class SocksException extends IOException {

	private ServerReply serverReply;

	public SocksException(String msg) {
		super(msg);
	}

	public SocksException(ServerReply serverReply) {
		this(serverReply.getErrorMessage());
		this.serverReply = serverReply;
	}

	public ServerReply getServerReply() {
		return serverReply;
	}

}
