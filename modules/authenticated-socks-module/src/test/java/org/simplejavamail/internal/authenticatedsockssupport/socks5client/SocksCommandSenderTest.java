package org.simplejavamail.internal.authenticatedsockssupport.socks5client;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

public class SocksCommandSenderTest {

	@Test
	public void sendsPortAfterEncodedDomainBytes() throws Exception {
		final ByteArrayOutputStream request = new ByteArrayOutputStream();
		final byte[] host = "例.com".getBytes(UTF_8);

		SocksCommandSender.send(socket(request, successfulIpv4Reply()), "例.com", 443);

		final ByteArrayOutputStream expectedRequest = new ByteArrayOutputStream();
		expectedRequest.write(new byte[] { 5, 1, 0, 3, (byte) host.length });
		expectedRequest.write(host);
		expectedRequest.write(new byte[] { 1, (byte) 187 });
		assertArrayEquals(expectedRequest.toByteArray(), request.toByteArray());
	}

	private static Socket socket(final ByteArrayOutputStream request, final byte[] reply) {
		return new Socket() {
			@Override
			public InputStream getInputStream() {
				return new ByteArrayInputStream(reply);
			}

			@Override
			public OutputStream getOutputStream() {
				return request;
			}
		};
	}

	private static byte[] successfulIpv4Reply() {
		return new byte[] { 5, 0, 0, 1, 0, 0, 0, 0, 0, 0 };
	}
}
