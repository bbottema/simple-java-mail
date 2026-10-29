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
		final byte[] address = bindAddress.getAddress();
		final int addressType = address.length == 4 ? AddressType.IPV4 : AddressType.IPV6;
		final int version = Socks5Handler.VERSION;

		final byte[] bytes = new byte[6 + address.length];

		bytes[0] = (byte) version;
		bytes[1] = reply.getValue();
		bytes[2] = (byte) 0x00;
		bytes[3] = (byte) addressType;
		System.arraycopy(address, 0, bytes, 4, address.length);
		bytes[4 + address.length] = getFirstByteFromInt(bindPort);
		bytes[5 + address.length] = getSecondByteFromInt(bindPort);

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
