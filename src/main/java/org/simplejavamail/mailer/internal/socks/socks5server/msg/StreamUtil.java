package org.simplejavamail.mailer.internal.socks.socks5server.msg;

import java.io.IOException;

final class StreamUtil {

	public static int checkEnd(final int b)
			throws IOException {
		if (b < 0) {
			throw new IOException("End of stream");
		}
		return b;
	}

}
