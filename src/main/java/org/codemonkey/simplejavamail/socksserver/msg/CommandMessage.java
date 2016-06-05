package org.codemonkey.simplejavamail.socksserver.msg;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.codemonkey.simplejavamail.socksserver.AddressType;
import org.codemonkey.simplejavamail.socksserver.SocksException;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.Charset;

import static org.codemonkey.simplejavamail.socksserver.StreamUtil.checkEnd;

public class CommandMessage {

	private static final Logger logger = LoggerFactory.getLogger(CommandMessage.class);

	private InetAddress inetAddress;

	private int port;

	private int command;

	private SocksException socksException;

	public void read(InputStream inputStream)
			throws IOException {
		logger.trace("CommandMessage.read");

		checkEnd(inputStream.read()); // version, unused
		command = checkEnd(inputStream.read());

		checkEnd(inputStream.read());
		int addressType = checkEnd(inputStream.read());

		if (!AddressType.isSupport(addressType) && socksException == null) {
			socksException = new SocksException(ServerReply.ADDRESS_TYPE_NOT_SUPPORTED);
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
				String host = new String(domainBytes, Charset.forName("UTF-8"));
				try {
					inetAddress = InetAddress.getByName(host);
				} catch (UnknownHostException e) {
					if (socksException == null) {
						socksException = new SocksException(ServerReply.HOST_UNREACHABLE);
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
		return socksException != null;
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

	public SocksException getSocksException() {
		return socksException;
	}

}
