package org.simplejavamail.internal.authenticatedsockssupport.socks5server.msg;

import org.simplejavamail.internal.authenticatedsockssupport.socks5server.Socks5Handler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;

public final class CommandResponseMessage {

	private static final Logger LOGGER = LoggerFactory.getLogger(CommandResponseMessage.class);

	public static byte[] getBytes(final ServerReply reply) {
		return getBytes(reply, new InetSocketAddress(0).getAddress(), 0);
	}

	public static byte[] getBytes(final ServerReply reply, final InetAddress bindAddress, final int bindPort) {
		final int addressType = (bindAddress.getAddress().length == 4) ? 0x01 : 0x0;
		final int version = Socks5Handler.VERSION;

		final byte[] bytes = new byte[10];

		bytes[0] = (byte) version;
		bytes[1] = reply.getValue();
		bytes[2] = (byte) 0x00;
		bytes[3] = (byte) addressType;
		bytes[4] = bindAddress.getAddress()[0];
		bytes[5] = bindAddress.getAddress()[1];
		bytes[6] = bindAddress.getAddress()[2];
		bytes[7] = bindAddress.getAddress()[3];
		bytes[8] = getFirstByteFromInt(bindPort);
		bytes[9] = getSecondByteFromInt(bindPort);

		LOGGER.trace("CommandResponseMessage.getBytes");
		return bytes;
	}

	private static byte getFirstByteFromInt(final int bindPort) {
		return (byte) ((bindPort & 0xff00) >> 8);
	}

	private static byte getSecondByteFromInt(final int bindPort) {
		return (byte) (bindPort & 0xff);
	}
}
