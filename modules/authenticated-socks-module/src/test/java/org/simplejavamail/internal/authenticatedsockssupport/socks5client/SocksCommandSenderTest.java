package org.simplejavamail.internal.authenticatedsockssupport.socks5client;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

	@Test
	public void readsDomainReplyAfterLengthByte() throws Exception {
		final CapturingAppender appender = new CapturingAppender();
		final org.apache.logging.log4j.core.Logger logger =
				(org.apache.logging.log4j.core.Logger) LogManager.getLogger(SocksCommandSender.class);
		final Level previousLevel = logger.getLevel();
		appender.start();
		logger.addAppender(appender);
		logger.setLevel(Level.DEBUG);

		try {
			SocksCommandSender.send(socket(new ByteArrayOutputStream(), successfulDomainReply("example.com", 443)), "target", 25);

			assertTrue(appender.messages.contains("Server replied:Address as host:example.com, port:443"));
		} finally {
			logger.setLevel(previousLevel);
			logger.removeAppender(appender);
			appender.stop();
		}
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

	private static byte[] successfulDomainReply(final String host, final int port) throws Exception {
		final byte[] hostBytes = host.getBytes(UTF_8);
		final ByteArrayOutputStream reply = new ByteArrayOutputStream();
		reply.write(new byte[] { 5, 0, 0, 3, (byte) hostBytes.length });
		reply.write(hostBytes);
		reply.write(new byte[] { (byte) (port >> 8), (byte) port });
		return reply.toByteArray();
	}

	private static class CapturingAppender extends AbstractAppender {

		private final List<String> messages = new ArrayList<>();

		private CapturingAppender() {
			super("socks-command-sender-test", null, null, true, Property.EMPTY_ARRAY);
		}

		@Override
		public void append(final LogEvent event) {
			messages.add(event.getMessage().getFormattedMessage());
		}
	}
}
