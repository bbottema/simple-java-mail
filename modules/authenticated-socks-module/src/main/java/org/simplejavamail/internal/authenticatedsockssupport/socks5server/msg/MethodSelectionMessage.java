package org.simplejavamail.internal.authenticatedsockssupport.socks5server.msg;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

public final class MethodSelectionMessage {

	private static final Logger LOGGER = LoggerFactory.getLogger(MethodSelectionMessage.class);

	public static int readVersion(final InputStream inputStream)
			throws IOException {
		LOGGER.trace("MethodSelectionMessage.read");
		final int version = StreamUtil.checkEnd(inputStream.read());
		final int methodNum = StreamUtil.checkEnd(inputStream.read());
		for (int i = 0; i < methodNum; i++) {
			StreamUtil.checkEnd(inputStream.read()); // read method byte
		}
		return version;
	}
}
