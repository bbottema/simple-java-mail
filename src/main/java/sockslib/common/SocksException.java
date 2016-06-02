package sockslib.common;

import sockslib.server.msg.ServerReply;

import java.io.IOException;

public class SocksException extends IOException {

	/**
	 * Messages that server will reply.
	 */
	private static final String serverReplyMessage[] = { "General SOCKS server failure", "Connection not allowed by ruleset",
			"Network " + "unreachable", "Host unreachable", "Connection refused", "TTL expired", "Command not " + "supported",
			"Address type not supported" };
	private ServerReply serverReply;

	public SocksException(String msg) {
		super(msg);
	}

	public static SocksException serverReplyException(ServerReply reply) {
		SocksException ex = serverReplyException(reply.getValue());
		ex.setServerReply(reply);
		return ex;
	}

	private static SocksException serverReplyException(byte reply) {
		int code = reply;
		code = code & 0xff;
		if (code < 0 || code > 0x08) {
			return new SocksException("Unknown reply");
		}
		code = code - 1;
		return new SocksException(serverReplyMessage[code]);
	}

	public ServerReply getServerReply() {
		return serverReply;
	}

	private void setServerReply(ServerReply serverReply) {
		this.serverReply = serverReply;
	}

}
