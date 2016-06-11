package org.codemonkey.simplejavamail.internal.socks.socksrelayserver;

import java.io.IOException;

public class StreamUtil {

	public static int checkEnd(int b)
			throws IOException {
		if (b < 0) {
			throw new IOException("End of stream");
		}
		return b;
	}

}
