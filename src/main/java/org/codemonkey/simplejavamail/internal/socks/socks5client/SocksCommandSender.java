

package org.codemonkey.simplejavamail.internal.socks.socks5client;

import org.codemonkey.simplejavamail.internal.socks.common.SocksException;
import org.codemonkey.simplejavamail.internal.Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;

import static java.nio.charset.StandardCharsets.UTF_8;

class SocksCommandSender {

	private static final Logger LOGGER = LoggerFactory.getLogger(SocksCommandSender.class);

	private static final byte SOCKS_VERSION = 0x05;
	private static final int ADDRESS_TYPE_IPV4 = 0x01;
	private static final int ADDRESS_TYPE_DOMAIN_NAME = 0x03;
	private static final int ADDRESS_TYPE_IPV6 = 0x04;

	private static final int COMMAND_CONNECT = 0x01;

	private static final int LENGTH_OF_IPV4 = 4;

	private static final int LENGTH_OF_IPV6 = 16;

	private static final int RESERVED = 0x00;
	private static final byte ATYPE_IPV4 = 0x01;
	private static final byte ATYPE_DOMAINNAME = 0x03;
	private static final byte ATYPE_IPV6 = 0x04;
	private static final int REP_SUCCEEDED = 0x00;

	public void send(Socket socket, InetAddress address, int port)
			throws IOException {
		send(socket, new InetSocketAddress(address, port));
	}

	public void send(Socket socket, SocketAddress socketAddress)
			throws IOException {
		if (!(socketAddress instanceof InetSocketAddress)) {
			throw new IllegalArgumentException("Unsupported address type");
		}

		final InputStream inputStream = socket.getInputStream();
		final OutputStream outputStream = socket.getOutputStream();
		final InetSocketAddress address = (InetSocketAddress) socketAddress;
		final byte[] bytesOfAddress = address.getAddress().getAddress();
		final int ADDRESS_LENGTH = bytesOfAddress.length;
		final int port = address.getPort();
		final byte addressType;
		final byte[] bufferSent;

		if (ADDRESS_LENGTH == LENGTH_OF_IPV4) {
			addressType = ATYPE_IPV4;
			bufferSent = new byte[6 + LENGTH_OF_IPV4];
		} else if (ADDRESS_LENGTH == LENGTH_OF_IPV6) {
			addressType = ATYPE_IPV6;
			bufferSent = new byte[6 + LENGTH_OF_IPV6];
		} else {
			throw new SocksException("Address error");// TODO
		}

		bufferSent[0] = SOCKS_VERSION;
		bufferSent[1] = (byte) COMMAND_CONNECT;
		bufferSent[2] = RESERVED;
		bufferSent[3] = addressType;
		System.arraycopy(bytesOfAddress, 0, bufferSent, 4, ADDRESS_LENGTH);// copy address bytes
		bufferSent[4 + ADDRESS_LENGTH] = (byte) ((port & 0xff00) >> 8);
		bufferSent[5 + ADDRESS_LENGTH] = (byte) (port & 0xff);

		outputStream.write(bufferSent);
		outputStream.flush();
		LOGGER.debug("{}", Util.buildLogString(bufferSent, false));

		checkServerReply(inputStream);
	}

	public void send(Socket socket, String host, int port)
			throws IOException {
		final InputStream inputStream = socket.getInputStream();
		final OutputStream outputStream = socket.getOutputStream();
		final int lengthOfHost = host.getBytes(UTF_8).length;
		final byte[] bufferSent = new byte[7 + lengthOfHost];

		bufferSent[0] = SOCKS_VERSION;
		bufferSent[1] = (byte) COMMAND_CONNECT;
		bufferSent[2] = RESERVED;
		bufferSent[3] = ATYPE_DOMAINNAME;
		bufferSent[4] = (byte) lengthOfHost;
		byte[] bytesOfHost = host.getBytes(UTF_8);
		System.arraycopy(bytesOfHost, 0, bufferSent, 5, lengthOfHost);// copy host bytes.
		bufferSent[5 + host.length()] = (byte) ((port & 0xff00) >> 8);
		bufferSent[6 + host.length()] = (byte) (port & 0xff);

		outputStream.write(bufferSent);
		outputStream.flush();
		LOGGER.debug("{}", Util.buildLogString(bufferSent, false));

		checkServerReply(inputStream);
	}

	private void checkServerReply(InputStream inputStream)
			throws IOException {
		ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
		int temp = 0;
		for (int i = 0; i < 4; i++) {
			temp = inputStream.read();
			byteArrayOutputStream.write(temp);
		}

		byte addressType = (byte) temp;
		switch (addressType) {
			case ADDRESS_TYPE_IPV4:
				for (int i = 0; i < 6; i++) {
					byteArrayOutputStream.write(inputStream.read());
				}
				break;
			case ADDRESS_TYPE_DOMAIN_NAME:
				temp = inputStream.read();
				byteArrayOutputStream.write(temp);
				for (int i = 0; i < temp + 2; i++) {
					byteArrayOutputStream.write(inputStream.read());
				}
				break;
			case ADDRESS_TYPE_IPV6:
				for (int i = 0; i < 18; i++) {
					byteArrayOutputStream.write(inputStream.read());
				}
				break;
			default:
				throw new SocksException("Address type not support, type value: " + addressType);
		}
		byte[] receivedData = byteArrayOutputStream.toByteArray();
		LOGGER.debug("{}", Util.buildLogString(receivedData, true));
		final byte[] addressBytes;
		byte[] portBytes = new byte[2];

		if (receivedData[3] == ADDRESS_TYPE_IPV4) {
			addressBytes = new byte[4];
			System.arraycopy(receivedData, 4, addressBytes, 0, addressBytes.length);
			int a = Util.toInt(addressBytes[0]);
			int b = Util.toInt(addressBytes[1]);
			int c = Util.toInt(addressBytes[2]);
			int d = Util.toInt(addressBytes[3]);
			portBytes[0] = receivedData[8];
			portBytes[1] = receivedData[9];

			LOGGER.debug("Server replied:Address as IPv4:{}.{}.{}.{}, port:{}", a, b, c, d,
					(Util.toInt(portBytes[0]) << 8) | (Util.toInt(portBytes[1])));

		} else if (receivedData[3] == ADDRESS_TYPE_DOMAIN_NAME) {
			int size = receivedData[4];
			size = size & 0xFF;
			addressBytes = new byte[size];
			System.arraycopy(receivedData, 4, addressBytes, 0, size);
			portBytes[0] = receivedData[4 + size];
			portBytes[1] = receivedData[5 + size];
			LOGGER.debug("Server replied:Address as host:{}, port:{}", new String(addressBytes, UTF_8),
					(Util.toInt(portBytes[0]) << 8) | (Util.toInt(portBytes[1])));
		} else if (receivedData[3] == ADDRESS_TYPE_IPV6) {
			addressBytes = new byte[16];
			System.arraycopy(receivedData, 4, addressBytes, 0, addressBytes.length);
			LOGGER.debug("Server replied:Address as IPv6:{}", new String(addressBytes, UTF_8));
		}

		final byte serverReply = receivedData[1];

		if (serverReply != REP_SUCCEEDED) {
			throw SocksException.serverReplyException(serverReply);
		}

		LOGGER.debug("SOCKS server response success");
	}

}
