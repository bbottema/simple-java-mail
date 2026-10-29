package org.simplejavamail.internal.authenticatedsockssupport.socks5server.msg;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

public final class MethodSelectionMessage {

	private static final Logger LOGGER = LoggerFactory.getLogger(MethodSelectionMessage.class);

	private final int version;
	private final byte[] offeredMethods;

	private MethodSelectionMessage(final int version, final byte[] offeredMethods) {
		this.version = version;
		this.offeredMethods = offeredMethods;
	}

	public static MethodSelectionMessage read(final InputStream inputStream)
			throws IOException {
		LOGGER.trace("MethodSelectionMessage.read");
		final int version = StreamUtil.checkEnd(inputStream.read());
		final int methodCount = StreamUtil.checkEnd(inputStream.read());
		final byte[] methods = new byte[methodCount];
		for (int i = 0; i < methodCount; i++) {
			methods[i] = (byte) StreamUtil.checkEnd(inputStream.read());
		}
		return new MethodSelectionMessage(version, methods);
	}

	public int getVersion() {
		return version;
	}

	public boolean offersMethod(final int method) {
		for (final byte offered : offeredMethods) {
			if ((offered & 0xff) == method) {
				return true;
			}
		}
		return false;
	}
}
