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
import static java.util.Objects.requireNonNull;

final class SocksAuthenticationHelper {
	private static final Logger LOGGER = LoggerFactory.getLogger(SocksAuthenticationHelper.class);

	private static final byte SOCKS_VERSION = 0x05;
	private static final byte USERNAME_PASSWORD_VERSION = 0x01;
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
        requireNonNull(socksProxy, "Argument [socksProxy] may not be null");
        final ProxyCredentials credentials = socksProxy.getCredentials();
		if (credentials == null) {
			throw new SocksException("Need Username/Password authentication");
		}

		final byte[] bytesOfUsername = credentialBytes(credentials.getUsername(), "username");
		final byte[] bytesOfPassword = credentialBytes(credentials.getPassword(), "password");
		final InputStream inputStream = socksProxy.getInputStream();
		final OutputStream outputStream = socksProxy.getOutputStream();

		final int USERNAME_LENGTH = bytesOfUsername.length;
		final int PASSWORD_LENGTH = bytesOfPassword.length;
		final byte[] bufferSent = new byte[3 + USERNAME_LENGTH + PASSWORD_LENGTH];

		bufferSent[0] = USERNAME_PASSWORD_VERSION;
		bufferSent[1] = (byte) USERNAME_LENGTH; // ULEN
		System.arraycopy(bytesOfUsername, 0, bufferSent, 2, USERNAME_LENGTH);// UNAME
		bufferSent[2 + USERNAME_LENGTH] = (byte) PASSWORD_LENGTH; // PLEN
		System.arraycopy(bytesOfPassword, 0, bufferSent, 3 + USERNAME_LENGTH, PASSWORD_LENGTH); // PASSWD
		outputStream.write(bufferSent);
		outputStream.flush();
		LOGGER.trace("Sent SOCKS username/password authentication request");

		try {
			final byte[] authenticationResult = read2Bytes(inputStream);
			LOGGER.trace("{}", MiscUtil.buildLogStringForSOCKSCommunication(authenticationResult, true));
			if (authenticationResult[0] != USERNAME_PASSWORD_VERSION) {
				throw new SocksException("Unsupported SOCKS username/password authentication version: " + (authenticationResult[0] & 0xff));
			}
			if (authenticationResult[1] != Socks5.AUTHENTICATION_SUCCEEDED) {
				throw new SocksException("Username or password error");
			}
		} catch (IOException | SocksException failure) {
			try {
				socksProxy.getProxySocket().close();
			} catch (IOException | RuntimeException | Error cleanupFailure) {
				if (cleanupFailure != failure) {
					failure.addSuppressed(cleanupFailure);
				}
			}
			throw failure;
		}
	}

	private static byte[] credentialBytes(final String value, final String member) {
		final byte[] bytes = value == null ? new byte[0] : value.getBytes(UTF_8);
		if (bytes.length < 1 || bytes.length > 255) {
			throw new SocksException("SOCKS proxy " + member + " must contain between 1 and 255 UTF-8 bytes");
		}
		return bytes;
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
