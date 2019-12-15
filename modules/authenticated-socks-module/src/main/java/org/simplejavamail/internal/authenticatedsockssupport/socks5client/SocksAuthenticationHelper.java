package org.simplejavamail.internal.authenticatedsockssupport.socks5client;

import org.simplejavamail.internal.authenticatedsockssupport.common.SocksException;
import org.simplejavamail.internal.util.MiscUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

import static java.nio.charset.StandardCharsets.UTF_8;

final class SocksAuthenticationHelper {
	private static final Logger LOGGER = LoggerFactory.getLogger(SocksAuthenticationHelper.class);

	private static final byte SOCKS_VERSION = 0x05;
	private static final int ACCEPTABLE_METHODS = 2; // anonymous & user / password

	private static final int NO_AUTHENTICATION_REQUIRED_METHOD = 0x00;
	private static final int USERNAME_PASSWORD_METHOD = 0x02;

	/**
	 * Performs an authentication method request to see how the proxy server wants to authenticate. GSSAPI is not supported, only anonymous
	 * and user / password authentication.
	 */
	public static boolean shouldAuthenticate(final Socket socket)
			throws IOException {
		// send data
		final byte[] bufferSent = new byte[4];
		bufferSent[0] = SOCKS_VERSION;
		bufferSent[1] = (byte) ACCEPTABLE_METHODS;
		bufferSent[2] = (byte) NO_AUTHENTICATION_REQUIRED_METHOD;
		bufferSent[3] = (byte) USERNAME_PASSWORD_METHOD;

		final OutputStream outputStream = socket.getOutputStream();
		outputStream.write(bufferSent);
		outputStream.flush();

		LOGGER.trace("{}", MiscUtil.buildLogStringForSOCKSCommunication(bufferSent, false));

		// Received data.
		final InputStream inputStream = socket.getInputStream();
		final byte[] receivedData = read2Bytes(inputStream);
		LOGGER.trace("{}", MiscUtil.buildLogStringForSOCKSCommunication(receivedData, true));
		if (receivedData[0] != (int) SOCKS_VERSION) {
			throw new SocksException("Remote server don't support SOCKS5");
		}
		final byte command = receivedData[1];
		if (command != NO_AUTHENTICATION_REQUIRED_METHOD && command != USERNAME_PASSWORD_METHOD) {
			throw new SocksException("requested authentication method not supported: " + command);
		}
		return command == USERNAME_PASSWORD_METHOD;
	}

	public static void performUserPasswordAuthentication(final Socks5 socksProxy)
			throws IOException {
		MiscUtil.checkNotNull(socksProxy, "Argument [socksProxy] may not be null");
		final ProxyCredentials credentials = socksProxy.getCredentials();
		if (credentials == null) {
			throw new SocksException("Need Username/Password authentication");
		}

		final String username = credentials.getUsername();
		final String password = credentials.getPassword();
		final InputStream inputStream = socksProxy.getInputStream();
		final OutputStream outputStream = socksProxy.getOutputStream();

		final int USERNAME_LENGTH = username.getBytes(UTF_8).length;
		final int PASSWORD_LENGTH = password.getBytes(UTF_8).length;
		final byte[] bytesOfUsername = username.getBytes(UTF_8);
		final byte[] bytesOfPassword = password.getBytes(UTF_8);
		final byte[] bufferSent = new byte[3 + USERNAME_LENGTH + PASSWORD_LENGTH];

		bufferSent[0] = 0x01; // VER
		bufferSent[1] = (byte) USERNAME_LENGTH; // ULEN
		System.arraycopy(bytesOfUsername, 0, bufferSent, 2, USERNAME_LENGTH);// UNAME
		bufferSent[2 + USERNAME_LENGTH] = (byte) PASSWORD_LENGTH; // PLEN
		System.arraycopy(bytesOfPassword, 0, bufferSent, 3 + USERNAME_LENGTH, PASSWORD_LENGTH); // PASSWD
		outputStream.write(bufferSent);
		outputStream.flush();
		// logger send bytes
		LOGGER.trace("{}", MiscUtil.buildLogStringForSOCKSCommunication(bufferSent, false));

		final byte[] authenticationResult = new byte[2];
		checkEnd(inputStream.read(authenticationResult));
		// logger
		LOGGER.trace("{}", MiscUtil.buildLogStringForSOCKSCommunication(authenticationResult, true));

		if (authenticationResult[1] != Socks5.AUTHENTICATION_SUCCEEDED) {
			// Close connection if authentication is failed.
			outputStream.close();
			inputStream.close();
			socksProxy.getProxySocket().close();
			throw new SocksException("Username or password error");
		}
	}

	private static byte[] read2Bytes(final InputStream inputStream)
			throws IOException {
		final byte[] bytes = new byte[2];
		bytes[0] = (byte) checkEnd(inputStream.read());
		bytes[1] = (byte) checkEnd(inputStream.read());
		return bytes;
	}

	private static int checkEnd(final int b)
			throws IOException {
		if (b < 0) {
			throw new IOException("End of stream");
		} else {
			return b;
		}
	}

}
