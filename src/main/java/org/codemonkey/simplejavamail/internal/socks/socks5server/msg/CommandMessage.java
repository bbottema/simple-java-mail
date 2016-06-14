package org.codemonkey.simplejavamail.internal.socks.socks5server.msg;

import org.codemonkey.simplejavamail.internal.socks.common.SocksException;
import org.codemonkey.simplejavamail.internal.socks.socks5server.AddressType;
import org.codemonkey.simplejavamail.internal.socks.socks5server.SocksServerReplyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.codemonkey.simplejavamail.internal.socks.socks5server.StreamUtil.checkEnd;

public class CommandMessage {

	private static final Logger LOGGER = LoggerFactory.getLogger(CommandMessage.class);

	private InetAddress inetAddress;

	private int port;

	private int command;

	private SocksServerReplyException socksServerReplyException;

	public void read(InputStream inputStream)
			throws IOException {
		LOGGER.trace("CommandMessage.read");

		checkEnd(inputStream.read()); // version, unused
		command = checkEnd(inputStream.read());

		checkEnd(inputStream.read());
		int addressType = checkEnd(inputStream.read());

		if (!AddressType.isSupport(addressType) && socksServerReplyException == null) {
			socksServerReplyException = new SocksServerReplyException(ServerReply.ADDRESS_TYPE_NOT_SUPPORTED);
		}

		// read address
		switch (addressType) {
			case AddressType.IPV4:
				byte[] addressBytes = read(inputStream, 4);
				inetAddress = InetAddress.getByAddress(addressBytes);
				break;

			case AddressType.DOMAIN_NAME:
				int domainLength = checkEnd(inputStream.read());
				if (domainLength < 1) {
					throw new SocksException("Length of domain must great than 0");
				}
				byte[] domainBytes = read(inputStream, domainLength);
				String host = new String(domainBytes, UTF_8);
				try {
					inetAddress = InetAddress.getByName(host);
				} catch (UnknownHostException e) {
					if (socksServerReplyException == null) {
						socksServerReplyException = new SocksServerReplyException(ServerReply.HOST_UNREACHABLE);
					}
				}
				break;
			default:
				// TODO Implement later.
				break;
		}

		port = bytesToInt(read(inputStream, 2));
	}

	private int bytesToInt(byte[] portBytes) {
		if (portBytes.length != 2) {
			throw new IllegalArgumentException("byte array size must be 2");
		}
		return ((portBytes[0] & 0xFF) << 8) | portBytes[1] & 0xFF;
	}

	private static byte[] read(InputStream inputStream, int length)
			throws IOException {
		byte[] bytes = new byte[length];
		for (int i = 0; i < length; i++) {
			bytes[i] = (byte) checkEnd(inputStream.read());
		}
		return bytes;
	}

	public boolean hasSocksException() {
		return socksServerReplyException != null;
	}

	public InetAddress getInetAddress() {
		return inetAddress;
	}

	public int getPort() {
		return port;
	}

	public int getCommand() {
		return command;
	}

	public SocksServerReplyException getSocksServerReplyException() {
		return socksServerReplyException;
	}

}
