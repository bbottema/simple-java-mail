package org.codemonkey.simplejavamail.internal.socks.socksrelayserver.msg;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

import static org.codemonkey.simplejavamail.internal.socks.socksrelayserver.StreamUtil.checkEnd;

public class MethodSelectionMessage {

	private static final Logger logger = LoggerFactory.getLogger(MethodSelectionMessage.class);

	public static int readVersion(InputStream inputStream)
			throws IOException {
		logger.trace("MethodSelectionMessage.read");
		int version = checkEnd(inputStream.read());
		int methodNum = checkEnd(inputStream.read());
		for (int i = 0; i < methodNum; i++) {
			checkEnd(inputStream.read()); // read method byte
		}
		return version;
	}
}
